package top.wkbin.zaomeng.data.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineLibraryRepositoryTest {
    @Test
    fun `accepts only the configured GitHub library download path`() {
        assertTrue(
            OnlineLibraryRepository.isAllowedDownloadUrl(
                "https://raw.githubusercontent.com/wkbin/zaomeng-library/main/books/demo.zaomeng-run.zip",
            ),
        )
        assertFalse(OnlineLibraryRepository.isAllowedDownloadUrl("http://raw.githubusercontent.com/wkbin/zaomeng-library/main/books/demo.zip"))
        assertFalse(OnlineLibraryRepository.isAllowedDownloadUrl("https://example.com/demo.zip"))
        assertFalse(OnlineLibraryRepository.isAllowedDownloadUrl("https://raw.githubusercontent.com/other/library/main/books/demo.zip"))
    }

    @Test
    fun `accepts only safe library book ids`() {
        assertTrue(OnlineLibraryRepository.isSafeBookId("demo-book-1"))
        assertTrue(OnlineLibraryRepository.isSafeBookId("demo_book_2"))
        assertFalse(OnlineLibraryRepository.isSafeBookId(""))
        assertFalse(OnlineLibraryRepository.isSafeBookId("../escape"))
        assertFalse(OnlineLibraryRepository.isSafeBookId("book/../escape"))
        assertFalse(OnlineLibraryRepository.isSafeBookId("absolute/id"))
        assertFalse(OnlineLibraryRepository.isSafeBookId("id with space"))
    }
}
