package top.wkbin.zaomeng.platform

import it.krzeminski.snakeyaml.engine.kmp.api.Load
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

@Suppress("UNCHECKED_CAST")
actual fun parseYaml(text: String): Map<String, Any?>? =
    runCatching { Load().loadOne(text) }.getOrNull() as? Map<String, Any?>

actual fun <T> runBlockingPlatform(block: suspend CoroutineScope.() -> T): T =
    runBlocking(block = block)
