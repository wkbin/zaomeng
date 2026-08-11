package top.wkbin.zaomeng.platform

/** Platform-secure key/value storage contract shared by client composition and server services. */
interface SecureKeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun entries(): Map<String, String>
}
