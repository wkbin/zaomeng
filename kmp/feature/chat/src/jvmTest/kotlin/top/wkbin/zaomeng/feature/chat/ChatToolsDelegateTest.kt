package top.wkbin.zaomeng.feature.chat

import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import top.wkbin.zaomeng.data.CardRepository
import top.wkbin.zaomeng.data.DialogueRepository
import top.wkbin.zaomeng.data.PluginRepository
import top.wkbin.zaomeng.data.SessionRepository
import top.wkbin.zaomeng.data.api.DialogueSessionDto

class ChatToolsDelegateTest {
    @Test
    fun `opening a reply version requests navigation while preserving the source session`() = runBlocking {
        val original = DialogueSessionDto(runId = "run", sessionId = "original", status = "ready")
        val branch = original.copy(sessionId = "branch")
        var state = ChatUiState(runId = original.runId, sessionId = original.sessionId, session = original, loading = false)
        val tools = ChatToolsDelegate(
            dialogue = repository<DialogueRepository> { method ->
                check(method == "branchImportedSwipe")
                branch
            },
            sessions = repository<SessionRepository>(),
            plugins = repository<PluginRepository>(),
            cardsRepository = repository<CardRepository>(),
            scope = this,
            currentState = { state },
            updateState = { transform -> state = transform(state) },
        )

        tools.branchFromImportedSwipe("1", 1)
        coroutineContext.job.children.toList().joinAll()

        assertEquals(branch, state.navigationSession)
        assertEquals(original, state.session)
        assertEquals(original.sessionId, state.sessionId)
        assertTrue(state.toolBusy.isBlank())
        assertTrue(state.error.isBlank())
    }

    private inline fun <reified T> repository(noinline invoke: (String) -> Any = { error("Unexpected call to $it") }): T {
        val type = T::class.java
        return type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ -> invoke(method.name) })
    }
}
