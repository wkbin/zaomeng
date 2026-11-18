package top.wkbin.zaomeng.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFStringConvertEncodingToNSStringEncoding
import platform.CoreFoundation.kCFStringEncodingGB_18030_2000
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.create

@OptIn(ExperimentalForeignApi::class)
actual fun decodeGb18030Strict(bytes: ByteArray): String? {
    if (bytes.isEmpty()) return ""
    val data = bytes.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
    }
    val encoding = CFStringConvertEncodingToNSStringEncoding(kCFStringEncodingGB_18030_2000.toUInt())
    return NSString.create(data = data, encoding = encoding)?.toString()
}
