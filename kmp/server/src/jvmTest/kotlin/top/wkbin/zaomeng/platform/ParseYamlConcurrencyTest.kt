package top.wkbin.zaomeng.platform

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `parseYaml` 的跨线程回归：JVM 侧曾经共用一个 `org.yaml.snakeyaml.Yaml` 单例，
 * 而 `Yaml` 不是线程安全的。这里让多条线程同时解析各自不同的文档，每个线程必须
 * 只拿到自己那份内容。
 */
class ParseYamlConcurrencyTest {

    @Test
    fun `concurrent parsing keeps each document isolated`() {
        val threads = 8
        val iterations = 200
        val barrier = CyclicBarrier(threads)
        val failure = AtomicReference<Throwable?>(null)
        val workers = (0 until threads).map { worker ->
            thread(name = "yaml-worker-$worker") {
                try {
                    barrier.await()
                    repeat(iterations) { i ->
                        val text = buildString {
                            appendLine("name: worker-$worker")
                            appendLine("turn: t$i")
                            appendLine("tags:")
                            appendLine("  - a$worker")
                            appendLine("  - b$i")
                        }
                        val parsed = parseYaml(text) ?: error("worker-$worker 解析返回 null")
                        assertEquals("worker-$worker", parsed["name"], "字段串到了别的线程的文档")
                        assertEquals("t$i", parsed["turn"])
                        assertEquals(listOf("a$worker", "b$i"), parsed["tags"])
                    }
                } catch (e: Throwable) {
                    failure.compareAndSet(null, e)
                }
            }
        }
        workers.forEach { it.join(10_000) }

        assertTrue(workers.none { it.isAlive }, "并发解析出现阻塞")
        failure.get()?.let { throw AssertionError("并发解析失败", it) }
    }

    @Test
    fun `nested structures and malformed input behave`() {
        val parsed = parseYaml(
            """
            role: 林黛玉
            relations:
              - target: 贾宝玉
                kind: bond
              - target: 薛宝钗
                kind: rival
            flags:
              enabled: true
            """.trimIndent(),
        )

        assertEquals("林黛玉", parsed?.get("role"))
        val relations = parsed?.get("relations") as? List<*>
        assertEquals(2, relations?.size)
        assertEquals("贾宝玉", (relations?.get(0) as? Map<*, *>)?.get("target"))
        assertNull(parseYaml("role: [unclosed"))
    }
}
