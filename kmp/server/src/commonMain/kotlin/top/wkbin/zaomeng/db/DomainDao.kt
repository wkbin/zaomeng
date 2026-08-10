package top.wkbin.zaomeng.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

/** 领域实体表 DAO：runs / sessions / messages / cards / personas。 */
@Dao
interface DomainDao {
    // ------------------------------------------------------------------ runs

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRun(run: RunEntity)

    @Query("SELECT * FROM runs ORDER BY updatedAtMillis DESC")
    suspend fun allRuns(): List<RunEntity>

    @Query("SELECT * FROM runs WHERE runId = :runId")
    suspend fun runById(runId: String): RunEntity?

    @Query("SELECT COUNT(*) FROM runs")
    suspend fun runCount(): Int

    @Query("DELETE FROM runs WHERE runId = :runId")
    suspend fun deleteRun(runId: String)

    // -------------------------------------------------------------- sessions

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE runId = :runId ORDER BY updatedAtMillis DESC")
    suspend fun sessionsOf(runId: String): List<SessionEntity>

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
}
