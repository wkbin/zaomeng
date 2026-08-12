package top.wkbin.zaomeng.data.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** 在线书库模型反序列化与安全过滤回归测试。 */
class OnlineLibraryRepositoryTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `online library index books deserialize`() {
        val payload = json.parseToJsonElement(
            """
            {
              "version": 1,
              "books": [
                {
                  "id": "demo-book",
                  "title": "示例书卷",
                  "created_by": "wkbin",
                  "summary": "演示包",
                  "version": "1.0.0",
                  "download_url": "https://raw.githubusercontent.com/wkbin/zaomeng-library/main/demo.zaomeng-run.zip",
                  "sha256": "abc123",
                  "size_bytes": 1024
                }
              ]
            }
            """.trimIndent(),
        ).jsonObject

        val books = json.decodeFromJsonElement(
            ListSerializer(OnlineLibraryBook.serializer()),
            requireNotNull(payload["books"]),
        )

        assertEquals(1, books.size)
        assertEquals("demo-book", books.single().id)
        assertEquals("示例书卷", books.single().title)
    }

    @Test
    fun `book id and download url are validated before use`() {
        assertTrue(OnlineLibraryRepository.isSafeBookId("demo-book_2026"))
        assertFalse(OnlineLibraryRepository.isSafeBookId("../evil"))
        assertTrue(
            OnlineLibraryRepository.isAllowedDownloadUrl(
                "https://raw.githubusercontent.com/wkbin/zaomeng-library/main/demo.zaomeng-run.zip",
            ),
        )
        assertFalse(
            OnlineLibraryRepository.isAllowedDownloadUrl(
                "https://evil.example.com/payload.zaomeng-run.zip",
            ),
        )
        assertFalse(
            OnlineLibraryRepository.isAllowedDownloadUrl(
                "https://raw.githubusercontent.com/other/repo/main/demo.zip",
            ),
        )
    }
}
