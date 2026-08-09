package top.wkbin.zaomeng.platform

/**
 * GB18030 严格解码（Android/JVM 走 java.nio Charset；iOS 走 NSString + CFStringEncoding）。
 * 仅用于导入文档的编码识别；无法严格解码时返回 null。
 */
expect fun decodeGb18030Strict(bytes: ByteArray): String?
