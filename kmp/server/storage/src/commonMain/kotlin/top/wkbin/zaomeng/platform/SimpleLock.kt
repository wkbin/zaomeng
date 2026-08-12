package top.wkbin.zaomeng.platform

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** KMP 互斥锁（替代 JVM synchronized）：仅用于短临界区。 */
class SimpleLock {
    private val mutex = Mutex()

    fun <T> withLock(block: () -> T): T = runBlocking { mutex.withLock { block() } }
}
