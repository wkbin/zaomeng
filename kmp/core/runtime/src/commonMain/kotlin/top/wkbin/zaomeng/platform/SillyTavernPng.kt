package top.wkbin.zaomeng.platform

import okio.Buffer
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/** SillyTavern stores UTF-8 character JSON as Base64 in a PNG text chunk. */
object SillyTavernPng {
    const val MAX_FILE_BYTES = 5 * 1024 * 1024
    const val MAX_JSON_BYTES = 1024 * 1024

    private val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    fun hasSignature(bytes: ByteArray): Boolean =
        bytes.size >= signature.size && signature.indices.all { bytes[it] == signature[it] }

    fun fitsDimensions(width: Int, height: Int): Boolean =
        width in 1..4096 && height in 1..4096 && width.toLong() * height <= 8_000_000

    fun readCard(bytes: ByteArray): ByteArray {
        val cards = mutableMapOf<String, String>()
        forEachChunk(bytes) { chunk ->
            if (chunk.type != "tEXt") return@forEachChunk
            val key = chunk.cardKey(bytes) ?: return@forEachChunk
            if (key in cards) return@forEachChunk
            val textStart = chunk.dataStart + key.length + 1
            require(chunk.dataEnd - textStart <= 2 * MAX_JSON_BYTES) { "PNG 角色卡的元数据过大。" }
            require(crc32(bytes, chunk.offset + 4, chunk.dataEnd) == uint32(bytes, chunk.dataEnd)) {
                "PNG 角色卡的元数据校验失败。"
            }
            cards[key] = bytes.decodeToString(textStart, chunk.dataEnd)
        }
        val encoded = cards["ccv3"] ?: cards["chara"]
            ?: throw IllegalArgumentException("PNG 中没有 SillyTavern 角色卡数据。")
        return encoded.decodeBase64()?.toByteArray()
            ?: throw IllegalArgumentException("PNG 角色卡中的 Base64 数据无效。")
    }

    fun writeCard(image: ByteArray, cardJson: ByteArray, isV3: Boolean = false): ByteArray {
        require(cardJson.size in 1..MAX_JSON_BYTES) { "角色卡数据过大，无法生成可重新导入的 PNG。" }
        require(image.size <= MAX_FILE_BYTES) { "带头像的 PNG 角色卡超过 5 MB。" }
        val keyword = if (isV3) "ccv3" else "chara"
        val text = ("tEXt$keyword\u0000" + cardJson.toByteString().base64()).encodeToByteArray()
        val output = Buffer().write(signature)
        forEachChunk(image) { chunk ->
            if (chunk.cardKey(image) != null) return@forEachChunk
            if (chunk.type == "IEND") {
                output.writeInt(text.size - 4).write(text).writeInt(crc32(text, 0, text.size).toInt())
            }
            output.write(image, chunk.offset, chunk.end - chunk.offset)
        }
        require(output.size <= MAX_FILE_BYTES) { "带头像的 PNG 角色卡超过 5 MB。" }
        return output.readByteArray()
    }

    private data class Chunk(val type: String, val offset: Int, val dataEnd: Int) {
        val dataStart: Int get() = offset + 8
        val end: Int get() = dataEnd + 4

        fun cardKey(bytes: ByteArray): String? {
            if (type != "tEXt" && type != "zTXt" && type != "iTXt") return null
            val separator = (dataStart until minOf(dataEnd, dataStart + 80)).firstOrNull { bytes[it] == 0.toByte() }
                ?: return null
            return bytes.decodeToString(dataStart, separator).lowercase().takeIf { it == "chara" || it == "ccv3" }
        }
    }

    private inline fun forEachChunk(bytes: ByteArray, visit: (Chunk) -> Unit) {
        require(bytes.size in 20..MAX_FILE_BYTES && hasSignature(bytes)) { "这不是有效的 PNG 角色卡。" }
        var offset = signature.size
        var hasImageData = false
        while (offset <= bytes.size - 12) {
            val length = uint32(bytes, offset)
            require(length <= bytes.size - offset - 12) { "PNG 角色卡的数据块已截断。" }
            val type = bytes.decodeToString(offset + 4, offset + 8)
            val chunk = Chunk(type, offset, offset + 8 + length.toInt())
            if (offset == signature.size) {
                require(type == "IHDR" && length == 13L) { "PNG 角色卡缺少有效的图像头。" }
                val width = uint32(bytes, chunk.dataStart)
                val height = uint32(bytes, chunk.dataStart + 4)
                require(width <= 4096 && height <= 4096 && fitsDimensions(width.toInt(), height.toInt())) {
                    "PNG 角色卡的图片尺寸过大。"
                }
            }
            if (type == "IDAT" && length > 0) hasImageData = true
            if (type == "IEND") {
                require(length == 0L && chunk.end == bytes.size) { "PNG 角色卡的结束块无效。" }
                require(hasImageData) { "PNG 角色卡缺少图像数据或结束块。" }
                visit(chunk)
                return
            }
            visit(chunk)
            offset = chunk.end
        }
        throw IllegalArgumentException("PNG 角色卡缺少图像数据或结束块。")
    }

    private fun uint32(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        repeat(4) { value = (value shl 8) or (bytes[offset + it].toLong() and 255) }
        return value
    }

    private fun crc32(bytes: ByteArray, start: Int, end: Int): Long {
        var crc = UInt.MAX_VALUE
        for (index in start until end) {
            crc = crc xor bytes[index].toUByte().toUInt()
            repeat(8) {
                crc = (crc shr 1) xor if (crc and 1u != 0u) 0xedb88320u else 0u
            }
        }
        return crc.inv().toLong()
    }
}
