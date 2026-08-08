package top.wkbin.zaomeng.app.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.wkbin.zaomeng.ui.theme.MyApplicationTheme
import top.wkbin.zaomeng.ui.theme.AppDimens

/** 跨平台共享 UI 入口：androidApp 与 desktopApp 都渲染这个组合。 */
@Composable
fun App() {
    MyApplicationTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(AppDimens.screenPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "Zaomeng",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.primary),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "共享 UI · Compose Multiplatform · ${platformName()}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "主题：${MaterialTheme.colorScheme.primary}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
