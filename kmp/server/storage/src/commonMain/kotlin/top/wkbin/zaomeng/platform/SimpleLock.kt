package top.wkbin.zaomeng.platform

/**
 * KMP 可重入互斥锁：仅用于短临界区。
 *
 * 相对旧实现（`Mutex` + `runBlocking`）的三点变化：
 *
 * 1. **可重入**：同一线程重复进入同一把锁不再自己等自己。`StorageService` 的 64 路分片写锁
 *    允许不同路径碰撞到同一槽位，嵌套写入曾因此永久挂起（概率约 1/64）。
 * 2. **不依赖协程调度**：临界区不挂起，直接用平台阻塞锁。`runBlocking` 在单线程 dispatcher
 *    （iOS 主循环、Compose 主线程）上等待协程时会把线程本身锁死，进而让持锁方永远无法释放。
 * 3. **异常/取消必经 `finally` 释放**：不再出现"请求已取消但锁仍被占住"。
 *
 * 约束（临界区内）：
 * - 不得再获取另一把**不同**的 `SimpleLock`：可重入只解决同一把锁的嵌套，跨线程反向嵌套
 *   （A 持 L1 等 L2、B 持 L2 等 L1）仍是 ABBA 死锁；
 * - 不得把工作派发到**其他线程**再回头取同一把锁：持有者按线程记录，跨线程仍会互等，
 *   而持有方此时正在等那个线程，就直接死锁了；
 * - 允许调用 `runBlockingPlatform` 之类的阻塞桥接，它在调用线程的事件循环上完成，不会把
 *   临界区让给别的线程。
 */
class SimpleLock {
    private val lock = PlatformReentrantLock()

    fun <T> withLock(block: () -> T): T = lock.withLock(block)
}
