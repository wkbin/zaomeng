package top.wkbin.zaomeng.platform

import io.github.yuroyami.kitearchive.KiteArchive
import io.github.yuroyami.kitearchive.archive.ByteArrayRandomAccessSource

actual fun readZipFileEntries(bytes: ByteArray): List<ZipFileEntryData> {
    val reader = KiteArchive.open(ByteArrayRandomAccessSource(bytes))
    return reader.entries()
        .filterNot { it.isDirectory || it.name.endsWith("/") }
        .map { ZipFileEntryData(it.name, reader.read(it)) }
}
