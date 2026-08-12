package top.wkbin.zaomeng.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.LoaderOptions

private val jvmYamlParser = Yaml(SafeConstructor(LoaderOptions()))

@Suppress("UNCHECKED_CAST")
actual fun parseYaml(text: String): Map<String, Any?>? =
    runCatching { jvmYamlParser.load<Any?>(text) }.getOrNull() as? Map<String, Any?>

actual fun <T> runBlockingPlatform(block: suspend CoroutineScope.() -> T): T =
    runBlocking(block = block)
