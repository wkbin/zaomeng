package top.wkbin.zaomeng.platform

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64

/**
 * 桌面密钥保护：Windows 用 DPAPI（当前用户，随系统账户绑定），其他系统退化为权限受限的明文文件。
 *
 * 存储值约定：
 * - `dpapi:<base64>`：DPAPI 加密后的密文；
 * - 其他：历史遗留明文（读取时原样返回，下次写入时自动加密）。
 *
 * 迁移到另一台机器或另一个系统账户时密文无法解出，此时 [reveal] 返回 null，
 * 调用方按“没有密钥”处理，让用户重新录入，而不是带着错误密钥发起请求。
 */
internal class SecretCodec(private val dpapi: DpapiSecret?) {
    val canEncrypt: Boolean get() = dpapi != null

    fun isEncoded(stored: String): Boolean = stored.startsWith(PREFIX)

    fun encode(plain: String): String {
        val protector = dpapi ?: return plain
        val encrypted = protector.protect(plain.encodeToByteArray()) ?: return plain
        return PREFIX + Base64.getEncoder().encodeToString(encrypted)
    }

    /** 无法解密（跨机器/跨平台/密文损坏）返回 null。 */
    fun reveal(stored: String): String? {
        if (!isEncoded(stored)) return stored
        val protector = dpapi ?: return null
        val encrypted = runCatching {
            Base64.getDecoder().decode(stored.removePrefix(PREFIX))
        }.getOrNull() ?: return null
        return protector.unprotect(encrypted)?.decodeToString()
    }

    companion object {
        private const val PREFIX = "dpapi:"

        fun platformDefault(): SecretCodec = SecretCodec(DpapiSecret.load())
    }
}

/** DPAPI 绑定；非 Windows 或 JNA 不可用时整体为 null。 */
internal class DpapiSecret private constructor(
    private val crypt32: Crypt32,
    private val kernel32: Kernel32,
) {
    fun protect(plain: ByteArray): ByteArray? = transform(plain) { input, output ->
        crypt32.CryptProtectData(input, null, null, null, null, UI_FORBIDDEN, output)
    }

    fun unprotect(encrypted: ByteArray): ByteArray? = transform(encrypted) { input, output ->
        crypt32.CryptUnprotectData(input, null, null, null, null, UI_FORBIDDEN, output)
    }

    private inline fun transform(
        input: ByteArray,
        call: (DataBlob, DataBlob) -> Boolean,
    ): ByteArray? = runCatching {
        val buffer = Memory(input.size.toLong().coerceAtLeast(1L))
        buffer.write(0, input, 0, input.size)
        val blobIn = DataBlob().apply {
            cbData = input.size
            pbData = buffer
        }
        val blobOut = DataBlob()
        if (!call(blobIn, blobOut)) return null
        val pointer = blobOut.pbData ?: return null
        try {
            pointer.getByteArray(0, blobOut.cbData)
        } finally {
            kernel32.LocalFree(pointer)
        }
    }.getOrNull()

    companion object {
        private const val UI_FORBIDDEN = 0x1

        fun load(): DpapiSecret? {
            if (!System.getProperty("os.name").orEmpty().lowercase().contains("windows")) return null
            return runCatching {
                val crypt32 = Native.load("crypt32", Crypt32::class.java)
                val kernel32 = Native.load("kernel32", Kernel32::class.java)
                DpapiSecret(crypt32, kernel32)
            }.getOrNull()
        }
    }
}

@Structure.FieldOrder("cbData", "pbData")
internal class DataBlob : Structure() {
    @JvmField
    var cbData: Int = 0

    @JvmField
    var pbData: Pointer? = null
}

private interface Crypt32 : Library {
    fun CryptProtectData(
        dataIn: DataBlob,
        description: Pointer?,
        entropy: DataBlob?,
        reserved: Pointer?,
        prompt: Pointer?,
        flags: Int,
        dataOut: DataBlob,
    ): Boolean

    fun CryptUnprotectData(
        dataIn: DataBlob,
        description: PointerByReference?,
        entropy: DataBlob?,
        reserved: Pointer?,
        prompt: Pointer?,
        flags: Int,
        dataOut: DataBlob,
    ): Boolean
}

private interface Kernel32 : Library {
    fun LocalFree(memory: Pointer?): Pointer?
}

/** 收紧文件权限到仅当前用户可读写；Windows 无 POSIX 权限，忽略即可。 */
internal fun restrictSecretFilePermissions(path: java.nio.file.Path) {
    runCatching {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
    }
}

internal fun secretFilePath(path: String): java.nio.file.Path = Paths.get(path)
