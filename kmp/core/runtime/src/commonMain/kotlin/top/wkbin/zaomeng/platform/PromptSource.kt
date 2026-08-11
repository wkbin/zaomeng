package top.wkbin.zaomeng.platform

/** Provides prompt resources without coupling runtime consumers to an app or server implementation. */
interface PromptSource {
    /** Returns prompt content and its modification time, or null when the resource is missing. */
    fun read(relativePath: String): Pair<String, Long>?

    /** Returns only the modification time so callers can validate caches without reading content. */
    fun lastModified(relativePath: String): Long?
}
