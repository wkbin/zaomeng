package top.wkbin.zaomeng.db

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

/** 统一持久化数据库：目前承载文档存储（业务数据），后续可继续加领域实体表。 */
@Database(entities = [DocumentEntity::class], version = 1)
@ConstructedBy(ZaomengDatabaseConstructor::class)
abstract class ZaomengDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
}

/** Room 编译器会按平台生成 actual 实现。 */
@Suppress("KotlinNoActualForExpect")
expect object ZaomengDatabaseConstructor : RoomDatabaseConstructor<ZaomengDatabase> {
    override fun initialize(): ZaomengDatabase
}

/** 通用构建：捆绑 SQLite 驱动 + IO 调度器（平台差异只剩 Builder 的路径/Context）。 */
fun buildZaomengDatabase(builder: RoomDatabase.Builder<ZaomengDatabase>): ZaomengDatabase =
    builder
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
