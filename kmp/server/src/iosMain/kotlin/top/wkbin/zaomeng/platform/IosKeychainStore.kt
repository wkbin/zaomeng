@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package top.wkbin.zaomeng.platform

import kotlinx.cinterop.alloc
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import okio.FileSystem
import okio.Path
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSArray
import platform.Foundation.NSData
import platform.Foundation.NSDictionary
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitAll
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnAttributes
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * iOS 安全存储：Keychain（`kSecClassGenericPassword`，`AfterFirstUnlockThisDeviceOnly`）。
 *
 * 与桌面不同，Keychain 条目随应用身份隔离，且不进入 iCloud/iTunes 备份。
 * [legacyFile] 指向旧的明文 `secrets.properties`：仅当 Keychain 中尚无条目时导入一次，
 * 随后把原文件改名为 `<name>.<millis>.legacy` 保留（不删除用户数据）。
 */
class IosKeychainSecureStore(
    private val service: String,
    private val legacyFile: Path? = null,
) : SecureKeyValueStore {
    init {
        migrateLegacyFile()
    }

    override fun get(key: String): String? = read(key)

    override fun put(key: String, value: String) {
        // 先删后加：写入只发生在用户保存设置时，避免 SecItemUpdate 与属性迁移的额外分支。
        remove(key)
        val status = autoreleasepool {
            withQuery(
                account = key,
                configure = { dict, scope ->
                    val data = NSString.create(string = value).dataUsingEncoding(NSUTF8StringEncoding)
                    if (data != null) {
                        CFDictionarySetValue(dict, kSecValueData, scope.retain(data))
                        CFDictionarySetValue(
                            dict,
                            kSecAttrAccessible,
                            kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                        )
                    }
                },
            ) { SecItemAdd(it, null) }
        }
        if (status != errSecSuccess) {
            PlatformLog.e(TAG, "Keychain write failed (status=$status)")
        }
    }

    override fun remove(key: String) {
        val status = autoreleasepool { withQuery(account = key) { SecItemDelete(it) } }
        if (status != errSecSuccess && status != errSecItemNotFound) {
            PlatformLog.w(TAG, "Keychain delete failed (status=$status)")
        }
    }

    override fun entries(): Map<String, String> = autoreleasepool {
        memScoped {
            withQuery(
                account = null,
                configure = { dict, _ ->
                    CFDictionarySetValue(dict, kSecReturnAttributes, kCFBooleanTrue)
                    CFDictionarySetValue(dict, kSecMatchLimit, kSecMatchLimitAll)
                },
            ) { query ->
                val result = alloc<CFTypeRefVar>()
                if (SecItemCopyMatching(query, result.ptr) != errSecSuccess) {
                    return@withQuery emptyMap()
                }
                val items = CFBridgingRelease(result.value) as? NSArray ?: return@withQuery emptyMap()
                buildMap {
                    for (index in 0 until items.count.toInt()) {
                        val attributes = items.objectAtIndex(index.toULong()) as? NSDictionary ?: continue
                        val account = attributes.objectForKey(kSecAttrAccount as Any) as? String ?: continue
                        read(account)?.let { value -> this[account] = value }
                    }
                }
            }
        }
    }

    private fun read(key: String): String? = autoreleasepool {
        memScoped {
            withQuery(
                account = key,
                configure = { dict, _ ->
                    CFDictionarySetValue(dict, kSecReturnData, kCFBooleanTrue)
                    CFDictionarySetValue(dict, kSecMatchLimit, kSecMatchLimitOne)
                },
            ) { query ->
                val result = alloc<CFTypeRefVar>()
                when (val status = SecItemCopyMatching(query, result.ptr)) {
                    errSecSuccess -> {
                        val data = CFBridgingRelease(result.value) as? NSData
                        data?.toByteArray()?.decodeToString()
                    }
                    errSecItemNotFound -> null
                    else -> {
                        PlatformLog.w(TAG, "Keychain read failed (status=$status)")
                        null
                    }
                }
            }
        }
    }

    /**
     * 构造 `kSecClassGenericPassword` 查询字典。
     *
     * 字典使用 null callbacks（不 retain），因此桥接得到的 +1 引用必须由 [CfScope] 持有到
     * 查询结束再统一释放，否则要么提前释放要么逐次泄漏。
     */
    private fun <R> withQuery(
        account: String?,
        configure: (CFMutableDictionaryRef?, CfScope) -> Unit = { _, _ -> },
        block: (CFMutableDictionaryRef?) -> R,
    ): R {
        val scope = CfScope()
        val dict = CFDictionaryCreateMutable(kCFAllocatorDefault, 0, null, null)
        try {
            CFDictionarySetValue(dict, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(dict, kSecAttrService, scope.retain(service))
            if (account != null) {
                CFDictionarySetValue(dict, kSecAttrAccount, scope.retain(account))
            }
            configure(dict, scope)
            return block(dict)
        } finally {
            dict?.let { CFRelease(it as CFTypeRef) }
            scope.releaseAll()
        }
    }

    /** 一次性导入旧明文文件；Keychain 已有条目时只做备份，避免覆盖新数据。 */
    private fun migrateLegacyFile() {
        val file = legacyFile ?: return
        val fs = FileSystem.SYSTEM
        if (!fs.exists(file)) return
        val legacy = runCatching { IosFileSecureStore(file).entries() }.getOrDefault(emptyMap())
        if (legacy.isEmpty()) return
        if (entries().isEmpty()) {
            legacy.forEach { (key, value) -> put(key, value) }
        }
        val backup = file.parent!! / "${file.name}.${nowEpochMillis()}.legacy"
        runCatching { fs.atomicMove(file, backup) }
        PlatformLog.i(TAG, "Migrated ${legacy.size} secret(s) from legacy file; backup=${backup.name}")
    }

    private companion object {
        const val TAG = "IosKeychainSecureStore"
    }
}

/** 持有桥接得到的 +1 CF 引用，配合 null callbacks 的字典在查询结束后统一释放。 */
private class CfScope {
    private val retained = mutableListOf<CFTypeRef?>()

    fun retain(value: Any?): CFTypeRef? {
        val reference = CFBridgingRetain(value)
        retained += reference
        return reference
    }

    fun releaseAll() {
        retained.forEach { reference -> reference?.let { CFRelease(it) } }
        retained.clear()
    }
}

/** NSData → ByteArray：Keychain 返回的是 UTF-8 字节，避免 NSString 与 String 的桥接歧义。 */
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size <= 0) return ByteArray(0)
    return ByteArray(size).also { target ->
        target.usePinned { pinned ->
            platform.posix.memcpy(pinned.addressOf(0), bytes, length)
        }
    }
}
