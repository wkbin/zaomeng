package top.wkbin.zaomeng.platform

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64

/**
 * 桌面密钥保护：
 * - Windows：DPAPI（当前用户，随系统账户绑定），密文写回文件 `dpapi:<base64>`；
 * - macOS：系统 Keychain，值不再落文件，文件只保留 `keychain:` 标记；
 * - 其他平台：退化为权限受限的明文文件。
 *
 * 历史明文条目读取时原样返回，下次写入时自动迁移。
 *
 * 迁移到另一台机器/另一个系统账户/另一种保护方式时值无法解出，此时 [reveal] 返回 null，
 * 调用方按“没有密钥”处理，让用户重新录入，而不是带着错误密钥发起请求。
 */
internal interface SecretCodec {
    /** 保护方式名称；null 表示无系统级保护（明文文件）。 */
    val protection: String?

    /** 落盘值的标记前缀；明文回退时没有标记。 */
    val marker: String?

    val canEncrypt: Boolean get() = protection != null

    fun isEncoded(stored: String): Boolean = marker?.let { stored.startsWith(it) } == true

    /** 无法保护时返回明文，调用方据此决定是否跳过迁移。 */
    fun encode(key: String, plain: String): String

    /** 无法解密（跨机器/跨平台/密文损坏）返回 null。 */
    fun reveal(key: String, stored: String): String?

    /** 删除 key 对应的系统侧记录；文件型保护无需处理。 */
    fun forget(key: String) {}
}

/** 所有已知保护的标记：没有对应后端时一律按“无法解出”处理，不能当明文使用。 */
internal object SecretMarkers {
    const val DPAPI = "dpapi:"
    const val KEYCHAIN = "keychain:"

    fun isForeign(stored: String): Boolean = stored.startsWith(DPAPI) || stored.startsWith(KEYCHAIN)
}

internal object PlaintextSecretCodec : SecretCodec {
    override val protection: String? = null
    override val marker: String? = null

    override fun encode(key: String, plain: String): String = plain

    override fun reveal(key: String, stored: String): String? =
        if (SecretMarkers.isForeign(stored)) null else stored
}

internal class DpapiSecretCodec(private val dpapi: DpapiSecret) : SecretCodec {
    override val protection: String = "DPAPI"
    override val marker: String = SecretMarkers.DPAPI

    override fun encode(key: String, plain: String): String {
        val encrypted = dpapi.protect(plain.encodeToByteArray()) ?: return plain
        return marker + Base64.getEncoder().encodeToString(encrypted)
    }

    override fun reveal(key: String, stored: String): String? {
        if (!isEncoded(stored)) return PlaintextSecretCodec.reveal(key, stored)
        val encrypted = runCatching {
            Base64.getDecoder().decode(stored.removePrefix(marker))
        }.getOrNull() ?: return null
        return dpapi.unprotect(encrypted)?.decodeToString()
    }

    companion object {
        fun platformDefault(): DpapiSecretCodec? = DpapiSecret.load()?.let(::DpapiSecretCodec)
    }
}

/**
 * macOS Keychain：每个 key 作为 generic password 条目（service 固定，account = key）。
 *
 * 文件里只写 `keychain:` 标记，值本身不落盘。写入失败时退回明文，由迁移逻辑整体跳过。
 * 使用旧版 SecKeychain* C 接口：签名简单、无需手工构造 CFDictionary，系统仍完整支持。
 */
internal class MacKeychainSecretCodec private constructor(
    private val security: MacSecurity,
    private val coreFoundation: MacCoreFoundation,
) : SecretCodec {
    override val protection: String = "macOS Keychain"
    override val marker: String = SecretMarkers.KEYCHAIN

    override fun encode(key: String, plain: String): String {
        val value = plain.encodeToByteArray()
        if (!store(account = key, value = value)) return plain
        return marker
    }

    override fun reveal(key: String, stored: String): String? {
        if (!isEncoded(stored)) return PlaintextSecretCodec.reveal(key, stored)
        return lookup(account = key)?.decodeToString()
    }

    override fun forget(key: String) {
        val item = findItem(account = key) ?: return
        try {
            security.SecKeychainItemDelete(item)
        } finally {
            coreFoundation.CFRelease(item)
        }
    }

    private fun findItem(account: String): Pointer? {
        val accountBytes = account.toByteArray()
        val item = PointerByReference()
        val status = security.SecKeychainFindGenericPassword(
            null, SERVICE.size, SERVICE, accountBytes.size, accountBytes, null, null, item,
        )
        return if (status == NO_ERR) item.value else null
    }

    private fun lookup(account: String): ByteArray? {
        val accountBytes = account.toByteArray()
        val length = IntByReference()
        val data = PointerByReference()
        val status = security.SecKeychainFindGenericPassword(
            null, SERVICE.size, SERVICE, accountBytes.size, accountBytes, length, data, null,
        )
        if (status != NO_ERR) return null
        val pointer = data.value ?: return null
        return try {
            pointer.getByteArray(0, length.value)
        } finally {
            security.SecKeychainItemFreeContent(null, pointer)
        }
    }

    private fun store(account: String, value: ByteArray): Boolean {
        val accountBytes = account.toByteArray()
        findItem(account)?.let { item ->
            return try {
                security.SecKeychainItemModifyAttributesAndData(item, null, value.size, value) == NO_ERR
            } finally {
                coreFoundation.CFRelease(item)
            }
        }
        return security.SecKeychainAddGenericPassword(
            null, SERVICE.size, SERVICE, accountBytes.size, accountBytes, value.size, value, null,
        ) == NO_ERR
    }

    companion object {
        /** noErr */
        private const val NO_ERR = 0

        private val SERVICE = "top.wkbin.zaomeng.server".toByteArray()

        fun load(): MacKeychainSecretCodec? {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            if (!os.contains("mac")) return null
            return runCatching {
                val security = Native.load("/System/Library/Frameworks/Security.framework/Security", MacSecurity::class.java)
                val coreFoundation = Native.load(
                    "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation",
                    MacCoreFoundation::class.java,
                )
                MacKeychainSecretCodec(security, coreFoundation)
            }.getOrNull()
        }
    }
}

private interface MacSecurity : Library {
    fun SecKeychainFindGenericPassword(
        keychainOrArray: Pointer?,
        serviceNameLength: Int,
        serviceName: ByteArray,
        accountNameLength: Int,
        accountName: ByteArray,
        passwordLength: IntByReference?,
        passwordData: PointerByReference?,
        itemRef: PointerByReference?,
    ): Int

    fun SecKeychainAddGenericPassword(
        keychain: Pointer?,
        serviceNameLength: Int,
        serviceName: ByteArray,
        accountNameLength: Int,
        accountName: ByteArray,
        passwordLength: Int,
        passwordData: ByteArray,
        itemRef: PointerByReference?,
    ): Int

    fun SecKeychainItemModifyAttributesAndData(
        itemRef: Pointer?,
        attrList: Pointer?,
        length: Int,
        data: ByteArray,
    ): Int

    fun SecKeychainItemFreeContent(attrList: Pointer?, data: Pointer?): Int

    fun SecKeychainItemDelete(itemRef: Pointer?): Int
}

private interface MacCoreFoundation : Library {
    fun CFRelease(cf: Pointer?)
}

internal object DesktopSecretCodec {
    fun platformDefault(): SecretCodec {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("mac") -> MacKeychainSecretCodec.load() ?: PlaintextSecretCodec
            else -> DpapiSecretCodec.platformDefault() ?: PlaintextSecretCodec
        }
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
