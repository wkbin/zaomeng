package top.wkbin.zaomeng.ktor.services

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
import okio.Path
import top.wkbin.zaomeng.data.api.ImportRunPackageRequest
import top.wkbin.zaomeng.data.api.LibraryPackageImportDto
import top.wkbin.zaomeng.data.api.SillyTavernPreviewDto
import top.wkbin.zaomeng.data.api.SillyTavernPreviewRequest
import top.wkbin.zaomeng.platform.base64Decode
import top.wkbin.zaomeng.platform.nowIsoString
import top.wkbin.zaomeng.platform.randomUuid
import top.wkbin.zaomeng.platform.forEachZipEntry

class RunPackageService(private val storage: StorageService) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }
    internal val sillyTavern = SillyTavernImport(storage)

    fun previewSillyTavern(request: SillyTavernPreviewRequest): SillyTavernPreviewDto {
        val extension = request.filename.substringAfterLast('.', "").lowercase()
        val png = extension == "png"
        require(extension == "json" || (png && !request.worldBook)) { "请选择 JSON 世界书或 JSON / PNG 角色卡。" }
        return sillyTavern.preview(
            decodeSillyTavern(request.contentBase64),
            png = png,
            worldBook = request.worldBook,
            targetRunId = request.targetRunId,
            testText = request.testText,
        )
    }

    private fun decodeSillyTavern(encoded: String): ByteArray {
        require(encoded.length <= (SillyTavernImport.MAX_BYTES + 2) / 3 * 4) { "SillyTavern 文件不能超过 5 MB。" }
        return runCatching { base64Decode(encoded) }
            .getOrElse { throw IllegalArgumentException("SillyTavern 文件的 Base64 内容无效。") }
    }

    fun importPackage(request: ImportRunPackageRequest): JsonObject {
        val extension = request.filename.substringAfterLast('.', "").lowercase()
        val isSillyTavern = request.libraryPackage == null && (extension == "json" || extension == "png")
        require(request.targetRunId == null || (isSillyTavern && extension == "json")) {
            "只有 JSON 世界书可以导入已有书卷。"
        }
        require(request.expectedWorldBookRevision == null || request.targetRunId != null) {
            "只有独立世界书需要校验挂载版本。"
        }
        if (isSillyTavern) {
            val bytes = decodeSillyTavern(request.contentBase64)
            return request.targetRunId?.let { sillyTavern.importWorldBook(it, bytes, request.expectedWorldBookRevision) }
                ?: sillyTavern.importCard(bytes, png = extension == "png")
        }
        require(request.contentBase64.length <= ((MAX_PACKAGE_BYTES + 2) / 3) * 4) { "archive is too large" }
        val bytes = runCatching { base64Decode(request.contentBase64) }
            .getOrElse { throw IllegalArgumentException("书卷包 Base64 内容无效。", it) }
        require(bytes.isNotEmpty()) { "导入的书卷包内容为空。" }

        val importRoot = storage.getStorageRoot() / "tmp-imports/import-${randomUuid()}"
        val newRunId = randomUuid().replace("-", "")
        val target = storage.runsDir / newRunId
        var ownsTarget = false
        try {
            extractSafely(bytes, importRoot)
            validatePackageManifest(importRoot / "package_manifest.json")
            val sourceRun = importRoot / "run"
            val sourceManifest = sourceRun / "run_manifest.json"
            require(storage.isDirectory(sourceRun) && storage.isFile(sourceManifest)) { "书卷包缺少有效的 run/run_manifest.json。" }
            check(!storage.exists(target)) { "新书卷目录已经存在，请重试。" }
            ownsTarget = true
            check(runCatching { copyRecursively(sourceRun, target); true }.getOrDefault(false)) { "复制书卷数据失败。" }
            storage.mkdirs(target / "dialogue")
            val manifestFile = target / "run_manifest.json"
            val original = json.parseToJsonElement(storage.readText(manifestFile)).jsonObject
            val imported = rewriteManifest(
                manifest = original,
                target = target,
                runId = newRunId,
                filename = request.filename,
                libraryPackage = request.libraryPackage,
            )
            val rewritten = SillyTavernImport.restorePackagedCardOrigin(storage, imported)
            storage.writeTextAtomically(manifestFile, json.encodeToString(JsonObject.serializer(), rewritten))
            rewriteSessionRunIds(target, newRunId)
            return rewritten
        } catch (error: Throwable) {
            if (ownsTarget) storage.deleteRecursively(target)
            throw error
        } finally {
            storage.deleteRecursively(importRoot)
        }
    }

    private fun extractSafely(bytes: ByteArray, root: Path) {
        storage.mkdirs(root)
        require(bytes.size.toLong() <= MAX_PACKAGE_BYTES) { "archive is too large" }
        val files = HashSet<String>()
        val directories = HashSet<String>()
        forEachZipEntry(bytes) { entry ->
            val name = normalizeArchiveEntryName(entry.name)
            val key = name.lowercase()
            require(files.add(key)) { "duplicate archive path" }
            require(key !in directories) { "archive file/directory collision" }
            var prefix = ""
            name.split('/').dropLast(1).forEach { component ->
                prefix = if (prefix.isEmpty()) component else "$prefix/$component"
                val prefixKey = prefix.lowercase()
                require(prefixKey !in files) { "archive file/directory collision" }
                directories += prefixKey
            }
            val target = resolveSafe(root, name)
            storage.mkdirs(target.parent!!)
            storage.writeBytes(target, entry.content)
        }
    }

    private fun normalizeArchiveEntryName(rawName: String): String {
        val name = rawName.replace('\\', '/')
        require(name.isNotBlank() && name.length <= MAX_PATH_LENGTH) { "invalid archive file name" }
        require(!name.startsWith('/') && !WINDOWS_ABSOLUTE_PATH.matches(name)) { "absolute archive path" }
        val components = name.split('/')
        require(components.size <= MAX_PATH_DEPTH) { "archive path is too deep" }
        components.forEach { component ->
            require(component.isNotEmpty() && component != "." && component != "..") { "unsafe archive path" }
            require(component.length <= MAX_COMPONENT_LENGTH) { "archive file name is too long" }
            require(component == component.trimEnd(' ', '.') && component.none { it in ":*?\"<>|" || it.code < 0x20 }) {
                "invalid archive file name"
            }
            val stem = component.substringBefore('.').trimEnd().uppercase()
            require(stem !in WINDOWS_RESERVED_NAMES) { "reserved archive file name" }
        }
        return components.joinToString("/")
    }

    /** 路径穿越防护：归一化后必须仍在 root 内部。 */
    private fun resolveSafe(root: Path, name: String): Path {
        val normalizedRoot = root.normalized()
        val candidate = (root / name).normalized()
        require(candidate == normalizedRoot || !candidate.relativeTo(normalizedRoot).toString().startsWith("..")) { "书卷包包含不安全路径。" }
        return candidate
    }

    private fun copyRecursively(source: Path, target: Path) {
        storage.mkdirs(target)
        for (child in storage.listFiles(source)) {
            if (isDiscardableLegacyAsset(child)) continue
            val dest = target / child.name
            if (storage.isDirectory(child)) {
                copyRecursively(child, dest)
            } else {
                storage.writeBytes(dest, storage.readBytes(child))
            }
        }
    }

    /** Legacy web exports bundled a multi-megabyte Mermaid runtime inside every run. */
    private fun isDiscardableLegacyAsset(path: Path): Boolean =
        storage.isFile(path) && LEGACY_MERMAID_RUNTIME.matches(path.name.lowercase())

    private fun validatePackageManifest(file: Path) {
        require(storage.isFile(file) && storage.fileSize(file) <= 256 * 1024) { "书卷包缺少 package_manifest.json。" }
        val manifest = json.parseToJsonElement(storage.readText(file)).jsonObject
        require(manifest["kind"]?.jsonPrimitive?.contentOrNull == "zaomeng_web_run_package") { "书卷包类型不受支持。" }
        val version = manifest["schema_version"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        require(version in 0..2) { "书卷包版本不受支持：$version" }
    }

    private fun rewriteManifest(
        manifest: JsonObject,
        target: Path,
        runId: String,
        filename: String,
        libraryPackage: LibraryPackageImportDto?,
    ): JsonObject {
        val originalRunId = manifest["run_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val sourceRoot = findSourceRoot(manifest, originalRunId)
        val importedAt = nowIsoString()
        val packageFilename = filename.substringAfterLast('/').substringAfterLast('\\')
        return buildJsonObject {
            manifest.forEach { (key, value) ->
                put(key, if (key == "run_id") JsonPrimitive(runId) else rewritePaths(value, sourceRoot, target.toString(), key))
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

    private fun rewritePaths(
        value: JsonElement,
        sourceRoot: String,
        targetRoot: String,
        fieldName: String = "",
    ): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (key, item) -> rewritePaths(item, sourceRoot, targetRoot, key) })
        is JsonArray -> JsonArray(value.map { rewritePaths(it, sourceRoot, targetRoot, fieldName) })
        is JsonPrimitive -> if (value.isString && isManifestPathField(fieldName)) {
            JsonPrimitive(rewriteManifestPath(value.contentOrNull.orEmpty(), sourceRoot, targetRoot, fieldName))
        } else if (value.isString && sourceRoot.isNotBlank() && isPathWithin(value.contentOrNull.orEmpty(), sourceRoot)) {
            val path = value.contentOrNull.orEmpty().replace('\\', '/')
            val root = sourceRoot.replace('\\', '/').trimEnd('/')
            JsonPrimitive(joinTargetRoot(targetRoot, path.removeRange(0, root.length).trimStart('/')))
        } else value
    }

    private fun rewriteManifestPath(value: String, sourceRoot: String, targetRoot: String, fieldName: String): String {
        if (value.isBlank()) return value
        val normalized = value.replace('\\', '/')
        val source = sourceRoot.replace('\\', '/').trimEnd('/')
        return when {
            source.isNotBlank() && isPathWithin(normalized, source) ->
                joinTargetRoot(targetRoot, normalized.removeRange(0, source.length).trimStart('/'))
            isAbsolutePath(normalized) ->
                throw IllegalArgumentException("Manifest $fieldName points outside the imported package")
            else -> {
                val components = normalized.split('/')
                require(components.none { it.isEmpty() || it == "." || it == ".." }) {
                    "Manifest $fieldName contains an unsafe relative path"
                }
                joinTargetRoot(targetRoot, normalized)
            }
        }
    }

    private fun isManifestPathField(fieldName: String): Boolean =
        fieldName.endsWith("_path") || fieldName.endsWith("_file") || fieldName.endsWith("_dir")

    private fun isAbsolutePath(value: String): Boolean =
        value.startsWith('/') || WINDOWS_ABSOLUTE_PATH.matches(value)

    private fun isPathWithin(value: String, root: String): Boolean {
        val normalizedValue = value.replace('\\', '/').trimEnd('/').lowercase()
        val normalizedRoot = root.replace('\\', '/').trimEnd('/').lowercase()
        return normalizedValue == normalizedRoot || normalizedValue.startsWith("$normalizedRoot/")
    }

    private fun joinTargetRoot(targetRoot: String, relative: String): String =
        targetRoot.trimEnd('/', '\\') + if (relative.isBlank()) "" else "/${relative.trimStart('/')}"

    private fun rewriteSessionRunIds(target: Path, runId: String) {
        val sessionsDirectory = target / "dialogue/sessions"
        storage.listFiles(sessionsDirectory).filter { storage.isDirectory(it) }.forEach { directory ->
            val manifest = directory / "session_manifest.json"
            if (!storage.isFile(manifest)) return@forEach
            val value = runCatching { json.parseToJsonElement(storage.readText(manifest)).jsonObject }.getOrNull() ?: return@forEach
            val updated = buildJsonObject {
                value.forEach { (key, item) -> put(key, if (key == "run_id") JsonPrimitive(runId) else item) }
            }
            storage.writeTextAtomically(manifest, json.encodeToString(JsonObject.serializer(), updated))
            rewriteReceiptRunIds(directory, runId)
        }
    }

    /**
     * 回复操作与派生操作的回执内嵌 `run_id`，路径由新书卷决定。
     * 导入后必须把回执里的旧书卷标识改成新书卷，否则续跑/派生会读写到原书卷目录。
     */
    private fun rewriteReceiptRunIds(sessionDirectory: Path, runId: String) {
        listOf("operations", "derived-operations").forEach { storeName ->
            val storeDirectory = sessionDirectory / storeName
            if (!storage.isDirectory(storeDirectory)) return@forEach
            storage.listFiles(storeDirectory).filter { storage.isDirectory(it) }.forEach operationLoop@ { operationDirectory ->
                val receipt = operationDirectory / "receipt.json"
                if (!storage.isFile(receipt)) return@operationLoop
                val value = runCatching { json.parseToJsonElement(storage.readText(receipt)).jsonObject }
                    .getOrNull() ?: return@operationLoop
                if (value["run_id"]?.jsonPrimitive?.contentOrNull == runId) return@operationLoop
                val updated = buildJsonObject {
                    value.forEach { (key, item) -> put(key, if (key == "run_id") JsonPrimitive(runId) else item) }
                }
                storage.writeTextAtomically(receipt, json.encodeToString(JsonObject.serializer(), updated))
            }
        }
    }

    private companion object {
        const val MAX_PACKAGE_BYTES = 128L * 1024 * 1024
        const val MAX_PATH_LENGTH = 1024
        const val MAX_PATH_DEPTH = 32
        const val MAX_COMPONENT_LENGTH = 255
        val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:/")
        val LEGACY_MERMAID_RUNTIME = Regex("""mermaid(?:-[0-9.]+)?(?:\.min)?\.js""")
        val WINDOWS_RESERVED_NAMES = setOf(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
        )
    }
}
