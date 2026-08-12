package top.wkbin.zaomeng.feature.bookshelf

import kotlin.test.Test
import kotlin.test.assertEquals
import top.wkbin.zaomeng.data.api.NovelSourceDto
import top.wkbin.zaomeng.data.api.RunManifestDto

class BookshelfFilterTest {
    private val runs = listOf(
        run("ready", "红楼梦.txt", "林黛玉"),
        run("running", "西游记.txt", "孙悟空"),
        run("failed", "水浒传.txt", "林冲"),
    )

    @Test
    fun `search matches a character name`() {
        val result = filterBookshelfRuns(
            BookshelfUiState(runs = runs, searchQuery = "悟空"),
        )

        assertEquals(listOf("running"), result.map(RunManifestDto::runId))
    }

    @Test
    fun `needs attention includes failed and stopped runs`() {
        val result = filterBookshelfRuns(
            BookshelfUiState(runs = runs, filter = BookshelfFilter.NeedsAttention),
        )

        assertEquals(listOf("failed"), result.map(RunManifestDto::runId))
    }

    @Test
    fun `title sort is alphabetical by book title`() {
        val titledRuns = listOf(
            run("gamma", "Gamma.txt", "甲"),
            run("alpha", "Alpha.txt", "乙"),
            run("beta", "Beta.txt", "丙"),
        )
        val result = filterBookshelfRuns(
            BookshelfUiState(runs = titledRuns, sort = BookshelfSort.Title),
        )

        assertEquals(listOf("alpha", "beta", "gamma"), result.map(RunManifestDto::runId))
    }

    private fun run(id: String, sourceName: String, character: String): RunManifestDto = RunManifestDto(
        runId = id,
        status = id,
        novelSources = listOf(NovelSourceDto(sourceName = sourceName)),
        lockedCharacters = listOf(character),
    )
}
