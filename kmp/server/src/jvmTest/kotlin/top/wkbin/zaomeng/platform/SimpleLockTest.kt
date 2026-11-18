package top.wkbin.zaomeng.platform

import okio.Path
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.db.DocumentStore
import top.wkbin.zaomeng.ktor.services.StorageService
import java.io.File
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `SimpleLock` 的临界区语义：可重入、异常释放、跨线程互斥。
 *
 * 最后一条测试直接复现旧实现的死锁场景：`StorageService` 按路径把写锁分 64 片，
 * 两个不同路径碰撞到同一片时，嵌套写入会再次请求同一把锁。
 */
class SimpleLockTest {

    @Test
    fun `nested acquisition on the same thread does not deadlock`() {
        val lock = SimpleLock()

        val value = lock.withLock { lock.withLock { 42 } }

        assertEquals(42, value)
    }

    @Test
    fun `lock is released when the block throws`() {
        val lock = SimpleLock()

        assertFailsWith<IllegalStateException> { lock.withLock { error("boom") } }

        // 抛异常后仍能再次进入，说明释放走了 finally
        assertEquals(1, lock.withLock { 1 })
    }

    @Test
    fun `concurrent writers stay mutually exclusive`() {
        val lock = SimpleLock()
        var counter = 0
        val workerCount = 4
        val iterations = 2_000

        val workers = List(workerCount) {
            thread { repeat(iterations) { lock.withLock { counter += 1 } } }
        }
        workers.forEach { it.join() }

        assertEquals(workerCount * iterations, counter)
    }

    @Test
    fun `nested write into a colliding shard slot does not deadlock`() {
        val root: Path = File.createTempFile("zaomeng-lock-", ".dir").let {
            it.delete()
            it.absolutePath.toPath()
        }
        val slotOf = { path: Path -> (path.toString().hashCode() and Int.MAX_VALUE) % 64 }
        val first = root / "runs" / "run-1" / "first.json"
        // 找一个与 first 落在同一分片槽位的另一个路径
        val second = generateSequence(1) { it + 1 }
            .map { root / "runs" / "run-1" / "second-$it.json" }
            .first { slotOf(it) == slotOf(first) }

        lateinit var storage: StorageService
        val writes = mutableListOf<String>()
        val store = object : DocumentStore {
            override fun readBytes(path: Path): ByteArray? = null

            override fun writeBytes(path: Path, bytes: ByteArray, updatedAtMillis: Long) {
                writes += path.toString()
                // 持锁期间再写同一槽位上的另一个路径
                if (path == first) storage.writeTextAtomically(second, "nested")
            }

            override fun updatedAtMillis(path: Path): Long? = null
            override fun exists(path: Path): Boolean = false
            override fun isFile(path: Path): Boolean = false
            override fun isDirectory(path: Path): Boolean = false
            override fun listFiles(path: Path): List<Path> = emptyList()
            override fun mkdirs(path: Path) = Unit
            override fun deleteFile(path: Path) = Unit
            override fun deleteRecursively(path: Path) = Unit
            override fun rename(source: Path, target: Path): Boolean = false
            override fun fileSize(path: Path): Long = 0L
        }
        storage = StorageService(root, store)

        // 用带超时的线程跑：不可重入时表现为超时失败，而不是卡死整个测试进程
        val worker = thread { storage.writeTextAtomically(first, "outer") }
        worker.join(5_000)

        assertFalse(worker.isAlive, "嵌套写入仍在等锁：SimpleLock 退化为不可重入")
        assertTrue(writes.contains(first.toString()), "外层写入未执行")
        assertTrue(writes.contains(second.toString()), "嵌套写入未执行")
    }
}
