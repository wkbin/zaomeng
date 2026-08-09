package top.wkbin.zaomeng.feature.sessions

import androidx.paging.PagingSource
import androidx.paging.PagingSource.LoadParams
import androidx.paging.PagingSource.LoadResult
import kotlinx.coroutines.test.runTest
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.SessionsResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 会话分页数据源测试：验证 offset/limit 切片、末页 nextKey 置空、
 * 首屏 total 回调，以及分页窗口下的全选/删除键处理。
 */
class SessionsPagingSourceTest {
    private val sessions = (1..45).map { index ->
        DialogueSessionDto(
            sessionId = "session-$index",
            runId = "run-alpha",
            updatedAt = "2026-08-0${index % 9 + 1}T00:00:00Z",
        )
    }

    @Test
    fun `first page has no prevKey and advances to next page`() = runTest {
        val source = SessionsPagingSource(fetcher = fakeFetcher(total = 45))

        val result = source.load(
            LoadParams.Refresh(key = null, loadSize = 30, placeholdersEnabled = false),
        ) as LoadResult.Page

        assertEquals(30, result.data.size)
        assertEquals("session-1", result.data.first().sessionId)
        assertNull(result.prevKey)
        assertEquals(30, result.nextKey)
    }

    @Test
    fun `last page returns null nextKey`() = runTest {
        val source = SessionsPagingSource(fetcher = fakeFetcher(total = 45))

        val result = source.load(
            LoadParams.Append(key = 30, loadSize = 30, placeholdersEnabled = false),
        ) as LoadResult.Page

        assertEquals(15, result.data.size)
        assertEquals(0, result.prevKey)
        assertNull(result.nextKey)
    }

    @Test
    fun `empty dataset ends immediately`() = runTest {
        val source = SessionsPagingSource(fetcher = fakeFetcher(total = 0))

        val result = source.load(
            LoadParams.Refresh(key = null, loadSize = 30, placeholdersEnabled = false),
        ) as LoadResult.Page

        assertEquals(0, result.data.size)
        assertNull(result.prevKey)
        assertNull(result.nextKey)
    }

    @Test
    fun `first page reports total for list counter`() = runTest {
        var reportedTotal = -1
        val source = SessionsPagingSource(
            fetcher = fakeFetcher(total = 45),
            onFirstPage = { reportedTotal = it },
        )

        source.load(LoadParams.Refresh(key = null, loadSize = 30, placeholdersEnabled = false))

        assertEquals(45, reportedTotal)
    }

    @Test
    fun `fetcher failure becomes load error`() = runTest {
        val source = SessionsPagingSource(
            fetcher = { _, _ -> error("boom") },
        )

        val result = source.load(
            LoadParams.Refresh(key = null, loadSize = 30, placeholdersEnabled = false),
        )

        assert(result is LoadResult.Error)
    }

    @Test
    fun `select all adds every visible session`() {
        val result = toggleVisibleSelection(
            selectedKeys = setOf("run-hidden::hidden"),
            visibleKeys = setOf("run-alpha::session-1", "run-beta::older"),
        )

        assertEquals(
            setOf("run-hidden::hidden", "run-alpha::session-1", "run-beta::older"),
            result,
        )
    }

    @Test
    fun `select all again clears only visible sessions`() {
        val result = toggleVisibleSelection(
            selectedKeys = setOf("run-hidden::hidden", "run-alpha::session-1", "run-beta::older"),
            visibleKeys = setOf("run-alpha::session-1", "run-beta::older"),
        )

        assertEquals(setOf("run-hidden::hidden"), result)
    }

    @Test
    fun `delete response only handles sessions from current selection`() {
        val result = handledSessionKeys(
            selectedKeys = setOf("run-alpha::session-1", "run-beta::older"),
            refs = listOf(
                top.wkbin.zaomeng.data.api.SessionRefDto(runId = "run-alpha", sessionId = "session-1"),
                top.wkbin.zaomeng.data.api.SessionRefDto(runId = "other-run", sessionId = "unexpected"),
            ),
        )

        assertEquals(setOf("run-alpha::session-1"), result)
    }

    private fun fakeFetcher(total: Int): suspend (Int, Int) -> SessionsResponse =
        { offset, limit ->
            val items = sessions.take(total).drop(offset).take(limit)
            SessionsResponse(
                items = items,
                total = total,
                hasMore = offset + items.size < total,
            )
        }
}
