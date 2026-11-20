package top.wkbin.zaomeng.feature.rundetail

import kotlin.test.Test
import kotlin.test.assertEquals
import top.wkbin.zaomeng.data.api.ArtifactIndexDto
import top.wkbin.zaomeng.data.api.PersonaIndexDto
import top.wkbin.zaomeng.data.api.RunManifestDto
import top.wkbin.zaomeng.data.api.RunProgressDto
import top.wkbin.zaomeng.data.api.RunSummaryDto

class RunNextActionTest {
    @Test
    fun `imported character card can start chatting without a distillation record`() {
        val action = nextActionFor(
            RunManifestDto(
                status = "ready",
                entrypoint = "sillytavern",
                lockedCharacters = listOf("黛玉"),
                artifactIndex = ArtifactIndexDto(characters = listOf(PersonaIndexDto(name = "黛玉"))),
            ),
        )

        assertEquals(RunNextActionTarget.OpenSessions, action.target)
        assertEquals("开始聊天", action.label)
    }

    @Test
    fun `unfinished characters take priority over all other actions`() {
        val action = nextActionFor(
            RunManifestDto(
                status = "stopped",
                lockedCharacters = listOf("黛玉", "宝玉"),
                progress = RunProgressDto(completedCharacters = listOf("黛玉")),
                artifactIndex = ArtifactIndexDto(characters = listOf(PersonaIndexDto(name = "黛玉"))),
            ),
        )

        assertEquals(RunNextActionTarget.ResumeDistillation, action.target)
        assertEquals("继续蒸馏", action.label)
    }

    @Test
    fun `completed book without graph sends user to persona review first`() {
        val action = nextActionFor(
            RunManifestDto(
                status = "ready",
                artifactIndex = ArtifactIndexDto(characters = listOf(PersonaIndexDto(name = "黛玉"))),
                summary = RunSummaryDto(graphStatus = "pending"),
            ),
        )

        assertEquals(RunNextActionTarget.OpenPersona, action.target)
        assertEquals("黛玉", action.character)
    }

    @Test
    fun `completed graph sends user to relation review`() {
        val action = nextActionFor(
            RunManifestDto(
                status = "ready",
                artifactIndex = ArtifactIndexDto(characters = listOf(PersonaIndexDto(name = "黛玉"))),
                summary = RunSummaryDto(graphStatus = "complete"),
            ),
        )

        assertEquals(RunNextActionTarget.OpenRelations, action.target)
    }
}
