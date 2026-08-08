package top.wkbin.zaomeng

import top.wkbin.zaomeng.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class ZaomengApplication : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@ZaomengApplication)
            modules(appModule)
        }
    }
}
