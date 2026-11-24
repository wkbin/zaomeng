package top.wkbin.zaomeng.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction

/** 领域实体表 DAO：runs / sessions / messages / cards / personas。 */
@Dao
interface DomainDao {
    // ------------------------------------------------------------------ runs

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRun(run: RunEntity)

    @Query("SELECT * FROM runs ORDER BY updatedAtMillis DESC")
    suspend fun allRuns(): List<RunEntity>

    @Query("SELECT runId FROM runs ORDER BY updatedAtMillis DESC")
    suspend fun runIds(): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM runs WHERE runId = :runId)")
    suspend fun runExists(runId: String): Boolean

    @Query("SELECT COUNT(*) FROM runs")
    suspend fun runCount(): Int

    @Query("DELETE FROM runs WHERE runId = :runId")
    suspend fun deleteRun(runId: String)

    // -------------------------------------------------------------- sessions

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE runId = :runId ORDER BY updatedAtMillis DESC")
    suspend fun sessionsOf(runId: String): List<SessionEntity>

    @Query("SELECT sessionId FROM sessions WHERE runId = :runId ORDER BY updatedAtMillis DESC")
    suspend fun sessionIdsOf(runId: String): List<String>

    @Query("SELECT DISTINCT runId FROM sessions ORDER BY runId")
    suspend fun sessionRunIds(): List<String>

    @Query("SELECT COUNT(*) FROM sessions WHERE runId = :runId")
    suspend fun sessionCountOf(runId: String): Int

    @Query("DELETE FROM sessions WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("DELETE FROM sessions WHERE runId = :runId")
    suspend fun deleteSessionsOf(runId: String)

    // ------------------------------------------------------------- messages

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE runId = :runId AND sessionId = :sessionId")
    suspend fun deleteMessagesOf(runId: String, sessionId: String)

    @Query("DELETE FROM messages WHERE runId = :runId AND sessionId = :sessionId AND seq >= :startSeq")
    suspend fun deleteMessagesFrom(runId: String, sessionId: String, startSeq: Int)

    @Query("DELETE FROM messages WHERE runId = :runId")
    suspend fun deleteMessagesOfRun(runId: String)

    @Query("SELECT * FROM messages WHERE runId = :runId AND sessionId = :sessionId AND seq >= :startSeq ORDER BY seq")
    suspend fun messagesFrom(runId: String, sessionId: String, startSeq: Int): List<MessageEntity>

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteMessagesById(ids: List<Long>)

    @Transaction
    suspend fun replaceSessionWindow(session: SessionEntity, startSeq: Int, messages: List<MessageEntity>) {
        upsertSession(session)
        if (messages.isEmpty()) {
            deleteMessagesFrom(session.runId, session.sessionId, startSeq)
            return
        }
        val previous = messagesFrom(session.runId, session.sessionId, startSeq)
            .associateBy { it.seq }.toMutableMap()
        val changes = messages.mapNotNull { message ->
            val existing = previous.remove(message.seq)
            val updated = message.copy(id = existing?.id ?: 0)
            updated.takeUnless { it == existing }
        }
        for (batch in previous.values.map { it.id }.chunked(500)) {
            deleteMessagesById(batch)
        }
        if (changes.isNotEmpty()) upsertMessages(changes)
    }

    @Transaction
    suspend fun deleteRunCascade(runId: String) {
        deleteRun(runId)
        deleteSessionsOf(runId)
        deleteMessagesOfRun(runId)
        deletePersonasOf(runId)
    }

    @Transaction
    suspend fun deleteSessionCascade(runId: String, sessionId: String) {
        deleteSession(sessionId)
        deleteMessagesOf(runId, sessionId)
    }

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun messageCount(): Long

    // ---------------------------------------------------------------- cards

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCard(card: CardEntity)

    @Query("SELECT * FROM cards WHERE kind = :kind ORDER BY updatedAtMillis DESC")
    suspend fun cardsOf(kind: String): List<CardEntity>

    @Query("SELECT COUNT(*) FROM cards")
    suspend fun cardCount(): Int

    @Query("DELETE FROM cards WHERE cardId = :cardId")
    suspend fun deleteCard(cardId: String)

    // ------------------------------------------------------------- personas

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPersona(persona: PersonaEntity)

    @Query("SELECT COUNT(*) FROM personas")
    suspend fun personaCount(): Int

    @Query("DELETE FROM personas WHERE runId = :runId")
    suspend fun deletePersonasOf(runId: String)

    @Query("DELETE FROM personas WHERE runId = :runId AND novelId = :novelId AND name IN (:names)")
    suspend fun deletePersonas(runId: String, novelId: String, names: List<String>)
}
