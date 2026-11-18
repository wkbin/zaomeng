package top.wkbin.zaomeng.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.LoaderOptions

/**
 * SnakeYAML 的 `Yaml` 持有构造器/解析器状态，官方明确它不是线程安全的：多个协程在不同线程上
 * 并发 `load` 会互相污染（节点、锚点状态串台，轻则解析出别人的字段，重则抛异常）。
 *
 * 按线程各持一份：frontmatter 解析在人物/会话列表路径上是高频调用，而 `SafeConstructor`
 * 的构造不算便宜，比每次 `Yaml(...)` 新建更划算；线程数由线程池封顶，不会无界增长。
 * Android/iOS 侧的 `snakeyaml-engine-kmp` 本来就没有可复用实例，每次新建，行为一致。
 */
private val jvmYamlParser = ThreadLocal.withInitial { Yaml(SafeConstructor(LoaderOptions())) }

@Suppress("UNCHECKED_CAST")
actual fun parseYaml(text: String): Map<String, Any?>? =
    runCatching { jvmYamlParser.get().load<Any?>(text) }.getOrNull() as? Map<String, Any?>

actual fun <T> runBlockingPlatform(block: suspend CoroutineScope.() -> T): T =
    runBlocking(block = block)
