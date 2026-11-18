package top.wkbin.zaomeng.platform

/**
 * 平台级**可重入**阻塞锁，作为 [SimpleLock] 的底座。
 *
 * 只用于同步临界区：块内不得挂起、不得调用 `runBlocking` 之外的长任务。
 * JVM/Android 用 `ReentrantLock`，iOS 用 `NSRecursiveLock`，两者都按线程记录持有者，
 * 同一线程重复进入同一把锁不会自死锁。
 */
internal expect class PlatformReentrantLock() {
    fun <T> withLock(block: () -> T): T
}
