package top.wkbin.zaomeng.db

import androidx.room3.Room
import androidx.room3.RoomDatabase
import okio.Path

/** iOS：数据库文件放在 ApplicationSupport/zaomeng 下（与数据目录一致）。 */
fun getDatabaseBuilder(path: Path): RoomDatabase.Builder<ZaomengDatabase> =
    Room.databaseBuilder<ZaomengDatabase>(name = path.toString())
