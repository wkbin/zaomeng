package top.wkbin.zaomeng.ktor.services

import java.io.File

/**
 * 存储标识符验证异常
 *
 * 对应 Python 的 InvalidStorageIdentifier
 */
class InvalidStorageIdentifierException(message: String) : IllegalArgumentException(message)

/**
 * 路径安全检查工具
 *
 * 对应 Python 的 path_safety.py
 */
object PathSafety {
    val STORAGE_ID_PATTERN = Regex("^[A-Za-z0-9_-]+$")
    private const val MAX_STORAGE_ID_LENGTH = 128

    /**
     * 验证存储标识符
     *
     * @param value 待验证的标识符
     * @param fieldName 字段名（用于错误消息）
     * @return 规范化后的标识符
     * @throws InvalidStorageIdentifierException 如果标识符无效
     */
    fun validateStorageId(value: String, fieldName: String = "identifier"): String {
        val normalized = value.trim()

        if (normalized.isEmpty() ||
            normalized.length > MAX_STORAGE_ID_LENGTH ||
            !STORAGE_ID_PATTERN.matches(normalized)) {
            throw InvalidStorageIdentifierException(
                "Invalid $fieldName. Use only letters, numbers, underscores, and hyphens."
            )
        }

        return normalized
    }

    /**
     * 宽松的路径组件校验（允许中文等 Unicode，但禁止路径分隔/穿越与特殊字符）。
     * 用于角色名、小说名等会拼进文件路径的用户输入（蒸馏落盘、redistill 源文件等）。
     */
    fun sanitizePathComponent(value: String, fieldName: String = "name"): String {
        val normalized = value.trim()
        if (normalized.isEmpty() || normalized.length > MAX_STORAGE_ID_LENGTH) {
            throw InvalidStorageIdentifierException("Invalid $fieldName. Name is empty or too long.")
        }
        if (normalized == "." || normalized == "..") {
            throw InvalidStorageIdentifierException("Invalid $fieldName.")
        }
        val forbidden = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|', '\u0000')
        if (normalized.any { it in forbidden }) {
            throw InvalidStorageIdentifierException(
                "Invalid $fieldName. Path separators and special characters are not allowed."
            )
        }
        return normalized
    }

    /**
     * 解析存储子路径，确保不会逃逸根目录（纯 java.io.File 实现，兼容 minSdk 24）。
     *
     * @param root 根目录
     * @param value 子路径标识符
     * @param fieldName 字段名（用于错误消息）
     * @return 解析后的安全路径
     * @throws InvalidStorageIdentifierException 如果路径不安全
     */
    fun resolveStorageChild(root: File, value: String, fieldName: String = "path"): File {
        val safeValue = validateStorageId(value, fieldName)
        val resolvedRoot = root.canonicalFile
        val candidate = File(resolvedRoot, safeValue).canonicalFile
        // 确保 candidate 在 resolvedRoot 内部（同目录或子路径）
        if (candidate.path != resolvedRoot.path &&
            !candidate.path.startsWith(resolvedRoot.path + File.separator)
        ) {
            throw InvalidStorageIdentifierException(
                "Invalid $fieldName: path escapes storage root."
            )
        }
        return candidate
    }
}
