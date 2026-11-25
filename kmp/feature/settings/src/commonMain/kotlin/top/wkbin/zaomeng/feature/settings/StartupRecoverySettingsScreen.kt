package top.wkbin.zaomeng.feature.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardListGroup
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import top.wkbin.zaomeng.data.preferences.AppPreferences
import top.wkbin.zaomeng.data.preferences.AppPreferencesRepository
import top.wkbin.zaomeng.ui.coui.CouiPage
import top.wkbin.zaomeng.ui.coui.CouiTopBarStyle
import top.wkbin.zaomeng.ui.coui.isWideWindow

@Composable
fun StartupRecoverySettingsScreen(
    onBack: () -> Unit,
    preferencesRepository: AppPreferencesRepository = koinInject(),
) {
    val preferences by preferencesRepository.preferences.collectAsStateWithLifecycle(AppPreferences())
    val scope = rememberCoroutineScope()
    CouiPage(
        title = "启动与恢复",
        topBarStyle = CouiTopBarStyle.Small,
        onBack = onBack,
        wide = isWideWindow(),
    ) {
        item {
            Card(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                CardListGroup {
                    item {
                        SwitchPreference(
                            title = "恢复上次浏览位置",
                            summary = "启动后自动打开上次浏览的书卷或会话。",
                            checked = preferences.restoreLastLocation,
                            onCheckedChange = { enabled ->
                                scope.launch { preferencesRepository.setRestoreLastLocation(enabled) }
                            },
                        )
                    }
                }
            }
        }
    }
}
