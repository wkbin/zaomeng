package top.wkbin.zaomeng.db

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * 领域实体表（数据库 v2 起）。
 *
 * 实体表是文档表的结构化投影：StorageService 每次写入对应文档时同步更新，
 * 列表/检索类读取直接查实体（带索引），全文文档仍以 documents 表为准。
 * 这样既保留原路径语义，又获得按 run/会话/消息/卡片/人物查询的能力。
 */
@Entity(tableName = "runs")
data class RunEntity(
    @PrimaryKey val runId: String,
    val title: String,
    val novelId: String,
    val status: String,
    @ColumnInfo(name = "updatedAtMillis") val updatedAtMillis: Long,
    /** run_manifest.json 完整内容（JSON 文本）。 */
    val manifest: String,
)

@Entity(
    tableName = "sessions",
    indices = [
        Index(value = ["runId"]),
        Index(value = ["updatedAtMillis"]),
    ],
)
data class SessionEntity(
    @PrimaryKey val sessionId: String,
    val runId: String,
    val title: String,
    val mode: String,
    val status: String,
    @ColumnInfo(name = "updatedAtMillis") val updatedAtMillis: Long,
    /** session_manifest.json 完整内容（含 transcript）。 */
    val manifest: String,
)

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["runId"]),
        Index(value = ["sessionId"]),
        Index(value = ["runId", "sessionId", "seq"], unique = true),
    ],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: String,
    val sessionId: String,
    @ColumnInfo(name = "turnId") val turnId: String,
    /** 在会话 transcript 中的顺序（从 0 开始）。 */
    val seq: Int,
    val speaker: String,
    val role: String,
    val message: String,
    val timestamp: String,
)

@Entity(
    tableName = "cards",
    indices = [
        Index(value = ["kind"]),
        Index(value = ["updatedAtMillis"]),
    ],
)
data class CardEntity(
    @PrimaryKey val cardId: String,
    /** scene / self / opening。 */
    val kind: String,
    val title: String,
    @ColumnInfo(name = "updatedAtMillis") val updatedAtMillis: Long,
    /** 卡片字段 JSON 文本（不含 meta）。 */
    val fieldsJson: String,
)

@Entity(
    tableName = "personas",
    indices = [
        Index(value = ["runId"]),
        Index(value = ["novelId"]),
    ],
)
data class PersonaEntity(
    @PrimaryKey val personaId: String,
    val runId: String,
    val novelId: String,
    val name: String,
    @ColumnInfo(name = "updatedAtMillis") val updatedAtMillis: Long,
    /** PROFILE.md 原文（YAML frontmatter + 正文）。 */
    val profile: String,
)
