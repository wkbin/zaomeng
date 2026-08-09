package top.wkbin.zaomeng.platform

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

actual fun readZipFileEntries(bytes: ByteArray): List<ZipFileEntryData> {
    val entries = mutableListOf<ZipFileEntryData>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (!entry.isDirectory) {
                entries += ZipFileEntryData(entry.name, zip.readBytes())
            }
            zip.closeEntry()
        }
    }
    return entries
}
