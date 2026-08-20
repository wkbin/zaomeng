package top.wkbin.zaomeng.ktor.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryRetrievalStrategyTest {
    private val strategy = LexicalRetrievalStrategy()

    @Test
    fun `lexical strategy scores token and exact phrase matches`() {
        assertEquals(0f, strategy.score("月光", "午后的花园"))
        assertTrue(strategy.score("月光", "月光照在窗边") >= 6f)
        assertTrue(strategy.score("旧 城", "他回到旧城门前") > 0f)
    }

    @Test
    fun `blank query never matches`() {
        assertEquals(0f, strategy.score("   ", "任意记忆"))
    }
}
