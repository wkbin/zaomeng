package top.wkbin.zaomeng.data.update

/**
 * 发布制品摘要的规范化与比较。
 *
 * GitHub release asset 的 `digest` 字段形如 `sha256:<64 位十六进制>`；手工清单可能只写十六进制。
 * 这里统一成小写十六进制，非法或非 sha256 的值返回 null，调用方据此把摘要当作“未知”而不是“匹配”。
 */
object AppUpdateDigest {
    private val HEX_PATTERN = Regex("^[0-9a-fA-F]{64}$")

    /** 接受 `sha256:HEX`、`HEX`（大小写不敏感）；其他算法、长度或字符集返回 null。 */
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val hex = when {
            trimmed.startsWith("sha256:", ignoreCase = true) -> trimmed.substringAfter(':')
            trimmed.contains(':') -> return null
            else -> trimmed
        }.trim()
        if (!HEX_PATTERN.matches(hex)) return null
        return hex.lowercase()
    }

    /** 实际下载/落盘文件的十六进制摘要是否与期望值一致；期望值为空表示无摘要可校验。 */
    fun matches(expectedHex: String, actualHex: String): Boolean {
        val expected = normalize(expectedHex) ?: return false
        return expected == actualHex.trim().lowercase()
    }
}
