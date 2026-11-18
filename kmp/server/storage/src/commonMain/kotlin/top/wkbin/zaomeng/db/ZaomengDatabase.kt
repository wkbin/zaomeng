package top.wkbin.zaomeng.db

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import top.wkbin.zaomeng.platform.platformIoDispatcher

/**
 * 统一持久化数据库：
 * - documents：通用文档存储（二进制/未建模产物，业务数据全文）。
 * - runs / sessions / messages / cards / personas：领域实体表（v2 起），
 *   由 StorageService 写入时同步维护，列表/检索读取走实体。
 */
@Database(
    entities = [
        DocumentEntity::class,
        RunEntity::class,
        SessionEntity::class,
        MessageEntity::class,
        CardEntity::class,
        PersonaEntity::class,
    ],
    version = 2,
)
@ConstructedBy(ZaomengDatabaseConstructor::class)
abstract class ZaomengDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao

    abstract fun domainDao(): DomainDao
}

/** Room 编译器会按平台生成 actual 实现。 */
@Suppress("KotlinNoActualForExpect")
expect object ZaomengDatabaseConstructor : RoomDatabaseConstructor<ZaomengDatabase> {
    override fun initialize(): ZaomengDatabase
}

/** 通用构建：捆绑 SQLite 驱动 + IO 调度器（平台差异只剩 Builder 的路径/Context）。 */
fun buildZaomengDatabase(builder: RoomDatabase.Builder<ZaomengDatabase>): ZaomengDatabase =
    builder
        // v1（documents-only）已确认可以丢弃，升级到 v2 时允许直接重建。
        // v2 起必须显式提供 Migration：漏写会在打开数据库时抛异常暴露问题，而不是静默清空
        // 所有用户数据。不要改回全局 fallbackToDestructiveMigration(dropAllTables = true)。
        .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(platformIoDispatcher)
        .build()
