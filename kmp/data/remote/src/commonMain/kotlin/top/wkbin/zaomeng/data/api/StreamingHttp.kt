package top.wkbin.zaomeng.data.api

/**
 * 打开 SSE 流式响应（逐块流式，避免整响应缓冲）。
 *
 * Ktor client 的 bodyAsChannel 在 Android + OkHttp 引擎会把响应体缓冲到连接关闭才返回，
 * 因此流式读取使用平台 HTTP 客户端直接返回 okio.BufferedSource（OkHttp ResponseBody.source()）。
 * 调用方负责关闭返回的 source。
 */
expect fun openStreamingResponse(url: String, jsonBody: String, token: String): okio.BufferedSource
