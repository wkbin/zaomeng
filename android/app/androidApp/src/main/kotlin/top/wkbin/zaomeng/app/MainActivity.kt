package top.wkbin.zaomeng.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.koin.core.context.startKoin
import top.wkbin.zaomeng.app.shared.App
import top.wkbin.zaomeng.di.AndroidAppPlatform
import top.wkbin.zaomeng.di.sharedAppModule

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        startKoin { modules(sharedAppModule(AndroidAppPlatform(applicationContext))) }
        super.onCreate(savedInstanceState)
        setContent {
            App()
        }
    }
}
