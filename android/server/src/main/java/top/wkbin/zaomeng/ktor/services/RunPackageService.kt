package top.wkbin.zaomeng.ktor.services

import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.Base64
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import top.wkbin.zaomeng.data.api.ImportRunPackageRequest
import top.wkbin.zaomeng.data.api.LibraryPackageImportDto

class RunPackageService(private val storage: StorageService) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    fun importPackage(request: ImportRunPackageRequest): JsonObject {
        val bytes = runCatching { Base64.getDecoder().decode(request.contentBase64) }
            .getOrElse { throw IllegalArgumentException("书卷包 Base64 内容无效。", it) }
        require(bytes.isNotEmpty()) { "导入的书卷包内容为空。" }

        val importRoot = File(storage.getStorageRoot(), "tmp-imports/import-${UUID.randomUUID()}")
        val newRunId = UUID.randomUUID().toString().replace("-", "")
        val target = File(storage.runsDir, newRunId)
        try {
            extractSafely(bytes, importRoot)
            validatePackageManifest(File(importRoot, "package_manifest.json"))
            val sourceRun = File(importRoot, "run")
            val sourceManifest = File(sourceRun, "run_manifest.json")
            require(sourceRun.isDirectory && sourceManifest.isFile) { "书卷包缺少有效的 run/run_manifest.json。" }
            check(!target.exists()) { "新书卷目录已经存在，请重试。" }
            check(sourceRun.copyRecursively(target, overwrite = false)) { "复制书卷数据失败。" }
            File(target, "dialogue").mkdirs()
            val manifestFile = File(target, "run_manifest.json")
            val original = json.parseToJsonElement(manifestFile.readText()).jsonObject
            val rewritten = rewriteManifest(
                manifest = original,
                target = target,
                runId = newRunId,
                filename = request.filename,
                libraryPackage = request.libraryPackage,
            )
            storage.writeTextAtomically(manifestFile, json.encodeToString(JsonObject.serializer(), rewritten))
            rewriteSessionRunIds(target, newRunId)
            return rewritten
        } catch (error: Throwable) {
            target.deleteRecursively()
            throw error
        } finally {
            importRoot.deleteRecursively()
        }
    }

    private fun extractSafely(bytes: ByteArray, root: File) {
        root.mkdirs()
        val canonicalRoot = root.canonicalFile
        var count = 0
        var totalBytes = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                count++
                require(count <= MAX_ENTRIES) { "书卷包文件数量超过限制。" }
                val target = File(root, entry.name).canonicalFile
                require(target.path.startsWith(canonicalRoot.path + File.separator)) { "书卷包包含不安全路径。" }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var entryBytes = 0L
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            entryBytes += read
                            totalBytes += read
                            require(entryBytes <= MAX_ENTRY_BYTES && totalBytes <= MAX_TOTAL_BYTES) { "书卷包解压大小超过限制。" }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun validatePackageManifest(file: File) {
        require(file.isFile && file.length() <= 256 * 1024) { "书卷包缺少 package_manifest.json。" }
        val manifest = json.parseToJsonElement(file.readText()).jsonObject
        require(manifest["kind"]?.jsonPrimitive?.contentOrNull == "zaomeng_web_run_package") { "书卷包类型不受支持。" }
        val version = manifest["schema_version"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        require(version in 0..2) { "书卷包版本不受支持：$version" }
    }

    private fun rewriteManifest(
        manifest: JsonObject,
        target: File,
        runId: String,
        filename: String,
        libraryPackage: LibraryPackageImportDto?,
    ): JsonObject {
        val originalRunId = manifest["run_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val sourceRoot = findSourceRoot(manifest, originalRunId)
        val importedAt = Instant.now().toString()
        val packageFilename = filename.substringAfterLast('/').substringAfterLast('\\')
        return buildJsonObject {
            manifest.forEach { (key, value) ->
                put(key, if (key == "run_id") JsonPrimitive(runId) else rewritePaths(value, sourceRoot, target.absolutePath))
            }
            put("run_id", runId)
            put("created_at", importedAt)
            put("updated_at", importedAt)
            put("entrypoint", "import")
            put("imported_from", buildJsonObject {
                put("package_filename", packageFilename)
                put("builtin_source", false)
                put("imported_at", importedAt)
                libraryPackage?.let { source ->
                    put("online_library", buildJsonObject {
                        put("id", source.id)
                        put("title", source.title)
                        put("version", source.version)
                        put("download_url", source.downloadUrl)
                        put("sha256", source.sha256)
                    })
                }
            })
        }
    }

    private fun findSourceRoot(value: JsonElement, runId: String): String {
        if (runId.isBlank()) return ""
        return when (value) {
            is JsonObject -> value.values.firstNotNullOfOrNull { findSourceRoot(it, runId).ifBlank { null } }.orEmpty()
            is JsonArray -> value.firstNotNullOfOrNull { findSourceRoot(it, runId).ifBlank { null } }.orEmpty()
            is JsonPrimitive -> {
                val text = value.contentOrNull.orEmpty()
                val unixMarker = "/$runId/"
                val windowsMarker = "\\$runId\\"
                when {
                    value.isString && unixMarker in text -> text.substringBefore(unixMarker) + "/$runId"
                    value.isString && windowsMarker in text -> text.substringBefore(windowsMarker) + "\\$runId"
                    else -> ""
                }
            }
        }
    }

    private fun rewritePaths(value: JsonElement, sourceRoot: String, targetRoot: String): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { rewritePaths(it.value, sourceRoot, targetRoot) })
        is JsonArray -> JsonArray(value.map { rewritePaths(it, sourceRoot, targetRoot) })
        is JsonPrimitive -> if (value.isString && sourceRoot.isNotBlank() && value.contentOrNull.orEmpty().startsWith(sourceRoot)) {
            JsonPrimitive(targetRoot + value.contentOrNull.orEmpty().removePrefix(sourceRoot).replace('\\', File.separatorChar))
        } else value
    }

    private fun rewriteSessionRunIds(target: File, runId: String) {
        File(target, "dialogue/sessions").listFiles()?.filter(File::isDirectory)?.forEach { directory ->
            val manifest = File(directory, "session_manifest.json")
            if (!manifest.isFile) return@forEach
            val value = runCatching { json.parseToJsonElement(manifest.readText()).jsonObject }.getOrNull() ?: return@forEach
            val updated = buildJsonObject {
                value.forEach { (key, item) -> put(key, if (key == "run_id") JsonPrimitive(runId) else item) }
            }
            storage.writeTextAtomically(manifest, json.encodeToString(JsonObject.serializer(), updated))
        }
    }

    private companion object {
        const val MAX_ENTRIES = 10_000
        const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
    }
}
