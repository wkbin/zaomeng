package top.wkbin.zaomeng.feature.chat.insights

internal data class BranchNodeInsight(
    val sessionId: String,
    val label: String,
    val detail: String,
    val isCurrent: Boolean,
    val isMainline: Boolean,
)

internal data class ConsistencyInsight(
    val statusLabel: String,
    val checkedTurns: Int,
    val issueCount: Int,
    val averageScore: Int?,
    val passRate: Int?,
    val passStreak: Int,
    val summary: String,
    val latestIssues: List<String>,
)

internal data class CharacterArcInsight(
    val name: String,
    val stateSummary: String,
    val growthSummary: String,
    val latestReason: String,
    val changeCount: Int,
)

internal data class SpeakerActivityInsight(
    val name: String,
    val statusLabel: String,
    val detail: String,
    val needsAttention: Boolean,
)

internal data class SpeakerInsight(
    val recommendedSpeakers: List<String>,
    val reasons: Map<String, String>,
    val activity: List<SpeakerActivityInsight>,
)

internal data class RelationTimelineInsight(
    val label: String,
    val locked: Boolean,
    val currentSummary: String,
    val changeSummary: String,
    val reason: String,
    val evidence: String,
)

internal data class EventSignalInsight(
    val kindLabel: String,
    val cue: String,
    val context: String,
)

internal data class GenerationInsight(
    val provider: String,
    val model: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val elapsedSeconds: Double,
    val attemptCount: Int,
    val cacheObserved: Boolean,
    val cacheStatus: String,
    val cacheHitRate: Double?,
    val sessionTurns: Int,
    val sessionTokens: Int,
    val sessionElapsedSeconds: Double,
    val sessionRetryCount: Int,
)

internal data class ContextUsageInsight(
    val speaker: String,
    val sources: List<ContextSourceInsight>,
)

internal data class ContextSourceInsight(
    val label: String,
    val count: Int,
    val items: List<String>,
)
