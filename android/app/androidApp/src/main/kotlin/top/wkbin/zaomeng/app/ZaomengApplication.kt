package top.wkbin.zaomeng.app

import android.app.Application
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import top.wkbin.zaomeng.di.AndroidAppPlatform
import top.wkbin.zaomeng.di.sharedAppModule

/** Android 应用入口：初始化 Koin（前台服务等组件在进程内通过 Koin 解析依赖）。 */
class ZaomengApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            modules(sharedAppModule(AndroidAppPlatform(applicationContext)))
        }
    }
}
