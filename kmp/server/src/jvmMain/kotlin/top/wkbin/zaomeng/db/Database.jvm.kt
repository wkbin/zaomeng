package top.wkbin.zaomeng.db

import androidx.room3.Room
import androidx.room3.RoomDatabase
import okio.Path

/** JVM 桌面：数据库文件放在数据根目录下（zaomeng.db），随数据目录迁移。 */
fun getDatabaseBuilder(path: Path): RoomDatabase.Builder<ZaomengDatabase> =
    Room.databaseBuilder<ZaomengDatabase>(name = path.toString())
