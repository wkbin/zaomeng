package top.wkbin.zaomeng.feature.sessions

import kotlin.test.Test
import kotlin.test.assertEquals
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.NovelSourceDto
import top.wkbin.zaomeng.data.api.RunManifestDto
import top.wkbin.zaomeng.data.api.SessionRefDto

class SessionsFilterTest {
    private val runs = listOf(
        run("run-alpha", "Alpha.txt"),
        run("run-beta", "Beta.txt"),
    )
    private val sessions = listOf(
        session(
            id = "older",
            runId = "run-beta",
            updatedAt = "2026-07-20T09:00:00Z",
            participants = listOf("贾宝玉", "林黛玉"),
        ),
        session(
            id = "newer",
            runId = "run-alpha",
            updatedAt = "2026-07-28T09:00:00Z",
            participants = listOf("孙悟空"),
        ),
    )

    @Test
    fun `search matches participants`() {
        val result = filterSessions(
            SessionsUiState(runs = runs, sessions = sessions, searchQuery = "黛玉"),
        )

        assertEquals(listOf("older"), result.map(DialogueSessionDto::sessionId))
    }

    @Test
    fun `recent sort shows latest activity first`() {
        val result = filterSessions(
            SessionsUiState(runs = runs, sessions = sessions, sort = SessionsSort.Recent),
        )

        assertEquals(listOf("newer", "older"), result.map(DialogueSessionDto::sessionId))
    }

    @Test
    fun `title sort uses book title`() {
        val result = filterSessions(
            SessionsUiState(runs = runs, sessions = sessions, sort = SessionsSort.Title),
        )

        assertEquals(listOf("newer", "older"), result.map(DialogueSessionDto::sessionId))
    }

    @Test
    fun `select all adds every visible session`() {
        val result = toggleVisibleSelection(
            selectedKeys = setOf("run-hidden::hidden"),
            visibleKeys = setOf("run-alpha::newer", "run-beta::older"),
        )

        assertEquals(
            setOf("run-hidden::hidden", "run-alpha::newer", "run-beta::older"),
            result,
        )
    }

    @Test
    fun `select all again clears only visible sessions`() {
        val result = toggleVisibleSelection(
            selectedKeys = setOf("run-hidden::hidden", "run-alpha::newer", "run-beta::older"),
            visibleKeys = setOf("run-alpha::newer", "run-beta::older"),
        )

        assertEquals(setOf("run-hidden::hidden"), result)
    }

    @Test
    fun `selection keeps only sessions visible in current search`() {
        val state = SessionsUiState(
            runs = runs,
            sessions = sessions,
            searchQuery = "黛玉",
        )

        val result = visibleSelectionKeys(
            state = state,
            candidateKeys = sessions.mapTo(mutableSetOf(), DialogueSessionDto::key),
        )

        assertEquals(setOf("run-beta::older"), result)
    }

    @Test
    fun `delete response only handles sessions from current selection`() {
        val result = handledSessionKeys(
            selectedKeys = setOf("run-alpha::newer", "run-beta::older"),
            refs = listOf(
                SessionRefDto(runId = "run-alpha", sessionId = "newer"),
                SessionRefDto(runId = "other-run", sessionId = "unexpected"),
            ),
        )

        assertEquals(setOf("run-alpha::newer"), result)
    }

    private fun run(id: String, sourceName: String) = RunManifestDto(
        runId = id,
        novelSources = listOf(NovelSourceDto(sourceName = sourceName)),
    )

    private fun session(
        id: String,
        runId: String,
        updatedAt: String,
        participants: List<String>,
    ) = DialogueSessionDto(
        sessionId = id,
        runId = runId,
        updatedAt = updatedAt,
        participants = participants,
    )
}
