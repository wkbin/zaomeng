package top.wkbin.zaomeng.platform

import platform.Foundation.NSRecursiveLock

internal actual class PlatformReentrantLock actual constructor() {
    private val lock = NSRecursiveLock()

    actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
