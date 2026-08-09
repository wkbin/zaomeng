package top.wkbin.zaomeng.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

/**
 * 文档表 DAO：按前缀模拟目录列举/递归删除。
 *
 * 所有查询都用 `LIKE ... ESCAPE '!'`，调用方需先把前缀转成转义后的 pattern。
 */
@Dao
interface DocumentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(document: DocumentEntity)

    @Query("SELECT bytes FROM documents WHERE path = :path")
    suspend fun bytesOf(path: String): ByteArray?

    @Query("SELECT updated_at FROM documents WHERE path = :path")
    suspend fun updatedAtOf(path: String): Long?

    @Query("SELECT EXISTS(SELECT 1 FROM documents WHERE path = :path)")
    suspend fun exists(path: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM documents WHERE path LIKE :pattern ESCAPE '!')")
    suspend fun existsUnder(pattern: String): Boolean

    @Query("SELECT path FROM documents WHERE path LIKE :pattern ESCAPE '!'")
    suspend fun pathsUnder(pattern: String): List<String>

    @Query("DELETE FROM documents WHERE path = :path")
    suspend fun delete(path: String)

    @Query("DELETE FROM documents WHERE path LIKE :pattern ESCAPE '!'")
    suspend fun deleteUnder(pattern: String)
}
