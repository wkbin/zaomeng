package top.wkbin.zaomeng.data

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import okio.FileSystem
import okio.Path
import okio.buffer
import top.wkbin.zaomeng.client.platform.clientMonotonicNanos

internal const val STREAM_BUFFER_SIZE = 64 * 1024

/** 把 Ktor 响应体通道流式写入缓存目录的临时文件（跨平台，避免整响应缓冲）。 */
internal suspend fun streamChannelToTempFile(
    channel: ByteReadChannel,
    directory: Path,
    prefix: String = "zaomeng-export-",
    suffix: String = ".zip",
): StreamedTempFile {
    val fs = FileSystem.SYSTEM
    fs.createDirectories(directory)
    val file = directory / "$prefix${clientMonotonicNanos()}$suffix"
    val sink = fs.sink(file).buffer()
    var byteCount = 0L
    val buffer = ByteArray(STREAM_BUFFER_SIZE)
    try {
        while (true) {
            val read = channel.readAvailable(buffer, 0, buffer.size)
            if (read < 0) break
            if (read > 0) {
                sink.write(buffer, 0, read)
                byteCount += read
            }
        }
        sink.flush()
    } catch (error: Throwable) {
        runCatching { sink.close() }
        runCatching { fs.delete(file) }
        throw error
    }
    sink.close()
    return StreamedTempFile(file, byteCount)
}

internal data class StreamedTempFile(
    val file: Path,
    val byteCount: Long,
)
