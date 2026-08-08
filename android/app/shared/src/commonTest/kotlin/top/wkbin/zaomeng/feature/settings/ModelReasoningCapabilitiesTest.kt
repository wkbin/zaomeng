package top.wkbin.zaomeng.feature.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class ModelReasoningCapabilitiesTest {
    @Test
    fun capabilitiesAreModelSpecific() {
        assertEquals(
            listOf("auto", "off", "low", "medium", "high", "xhigh"),
            modelReasoningEfforts("openai-compatible", "https://api.deepseek.com", "deepseek-v4-pro"),
        )
        assertEquals(
            listOf("auto", "low", "medium", "high"),
            modelReasoningEfforts("openai", "", "o3-mini"),
        )
        assertEquals(
            listOf("auto", "off"),
            modelReasoningEfforts(
                "openai-compatible",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "qwen-plus",
            ),
        )
        assertEquals(
            listOf("auto", "low", "medium", "high"),
            modelReasoningEfforts(
                "openai-compatible",
                "https://api.stepfun.com/v1",
                "step-3.5-flash",
            ),
        )
        assertEquals(
            listOf("auto", "low", "high"),
            modelReasoningEfforts(
                "openai-compatible",
                "https://api.stepfun.com/v1",
                "step-3.5-flash-2603",
            ),
        )
        assertEquals(
            listOf("auto"),
            modelReasoningEfforts("openai", "", "gpt-4.1"),
        )
    }

    @Test
    fun stepFunIsAvailableAsBuiltInCatalog() {
        val catalog = modelCatalogs.first { it.id == "stepfun" }

        assertEquals("阶跃星辰", catalog.title)
        assertEquals("openai-compatible", catalog.provider)
        assertEquals("https://api.stepfun.com/v1", catalog.baseUrl)
        assertEquals(
            listOf("step-3.7-flash", "step-3.5-flash"),
            catalog.models.map { it.id },
        )
    }

    @Test
    fun unsupportedReasoningValueFallsBackToModelDefault() {
        assertEquals(
            "auto",
            normalizedReasoningEffort(
                "openai-compatible",
                "https://api.stepfun.com/v1",
                "step-3.7-flash",
                "off",
            ),
        )
        assertEquals(
            "off",
            normalizedReasoningEffort(
                "openai-compatible",
                "https://api.deepseek.com",
                "deepseek-v4-pro",
                "unsupported",
            ),
        )
    }
}
