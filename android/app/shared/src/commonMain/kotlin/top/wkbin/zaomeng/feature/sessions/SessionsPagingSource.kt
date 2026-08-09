package top.wkbin.zaomeng.feature.sessions

import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.CancellationException
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.SessionsResponse

/**
 * 会话列表分页数据源：按 offset/limit 走本地 Ktor 接口。
 *
 * 搜索词与排序在构造时固定——查询条件变化由 [SessionsViewModel] 重建 Pager。
 */
class SessionsPagingSource(
    private val fetcher: suspend (offset: Int, limit: Int) -> SessionsResponse,
    /** 首屏加载后回调过滤条件下的会话总数（用于列表计数展示）。 */
    private val onFirstPage: (Int) -> Unit = {},
) : PagingSource<Int, DialogueSessionDto>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, DialogueSessionDto> {
        val offset = params.key ?: 0
        val limit = params.loadSize.coerceIn(1, 200)
        return try {
            val page = fetcher(offset, limit)
            if (offset == 0) onFirstPage(page.total)
            val nextOffset = offset + page.items.size
            LoadResult.Page(
                data = page.items,
                prevKey = if (offset == 0) null else (offset - limit).coerceAtLeast(0),
                nextKey = if (page.hasMore) nextOffset else null,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            LoadResult.Error(error)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, DialogueSessionDto>): Int? {
        val anchor = state.anchorPosition ?: return null
        val page = state.closestPageToPosition(anchor) ?: return null
        return page.prevKey?.plus(1) ?: page.nextKey?.minus(1) ?: 0
    }
}
