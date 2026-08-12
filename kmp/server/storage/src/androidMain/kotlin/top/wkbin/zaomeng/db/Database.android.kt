package top.wkbin.zaomeng.db

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase

/** Android：数据库文件放在应用私有数据库目录（getDatabasePath）。 */
fun getDatabaseBuilder(context: Context): RoomDatabase.Builder<ZaomengDatabase> {
    val appContext = context.applicationContext
    return Room.databaseBuilder<ZaomengDatabase>(
        context = appContext,
        name = appContext.getDatabasePath("zaomeng.db").absolutePath,
    )
}
