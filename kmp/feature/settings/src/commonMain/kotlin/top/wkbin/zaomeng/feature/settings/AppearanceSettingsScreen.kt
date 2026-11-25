package top.wkbin.zaomeng.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardListGroup
import io.github.suqi8.coui.kmp.preference.ListPreference
import io.github.suqi8.coui.kmp.preference.ListPreferenceEntry
import io.github.suqi8.coui.kmp.preference.MarkPreference
import io.github.suqi8.coui.kmp.preference.PreferenceCategory
import io.github.suqi8.coui.kmp.preference.SliderPreference
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import top.wkbin.zaomeng.data.preferences.AppPreferencesRepository
import top.wkbin.zaomeng.data.preferences.ThemeMode
import top.wkbin.zaomeng.data.preferences.UI_SCALE_DEFAULT
import top.wkbin.zaomeng.data.preferences.UI_SCALE_MAX
import top.wkbin.zaomeng.data.preferences.UI_SCALE_MIN
import top.wkbin.zaomeng.platform.backHandlingToggleSupported
import top.wkbin.zaomeng.ui.coui.CouiPage
import top.wkbin.zaomeng.ui.coui.CouiTopBarStyle
import top.wkbin.zaomeng.ui.coui.isWideWindow
import top.wkbin.zaomeng.ui.theme.ThemeSeedColor

private val themeColorEntries = ThemeSeedColor.entries.map { ListPreferenceEntry(text = it.displayName) }

@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    /** 内置导航返回处理开关切换回调（仅 Android 显示开关），由平台入口持久化。 */
    onBuiltInBackHandlingEnabledChange: (Boolean) -> Unit = {},
    preferencesRepository: AppPreferencesRepository = koinInject(),
) {
    val themeMode by preferencesRepository.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
    val themeSeedColorArgb by preferencesRepository.themeSeedColorArgb.collectAsStateWithLifecycle(0L)
    val dynamicColorEnabled by preferencesRepository.dynamicColorEnabled.collectAsStateWithLifecycle(false)
    val uiScale by preferencesRepository.uiScale.collectAsStateWithLifecycle(UI_SCALE_DEFAULT)
    val builtInBackHandlingEnabled by preferencesRepository.builtInBackHandlingEnabled.collectAsStateWithLifecycle(true)
    var uiScaleDraft by remember { mutableFloatStateOf(uiScale) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(uiScale) { uiScaleDraft = uiScale }

    CouiPage(
        title = "外观",
        topBarStyle = CouiTopBarStyle.Small,
        onBack = onBack,
        wide = isWideWindow(),
    ) {
        item(key = "theme_mode") {
            PreferenceCategory(title = "主题模式")
            Card(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                CardListGroup {
                    ThemeMode.entries.forEach { mode ->
                        item(key = mode.name) {
                            MarkPreference(
                                title = mode.displayName,
                                summary = when (mode) {
                                    ThemeMode.SYSTEM -> "根据设备当前的系统外观自动切换。"
                                    ThemeMode.LIGHT -> "始终使用浅色界面。"
                                    ThemeMode.DARK -> "始终使用深色界面。"
                                },
                                checked = themeMode == mode,
                                onClick = { scope.launch { preferencesRepository.setThemeMode(mode) } },
                            )
                        }
                    }
                    item(key = "dynamic_color") {
                        SwitchPreference(
                            title = "动态取色",
                            summary = "使用主题色生成整套色板。选择默认色时，支持的设备会使用系统色板。",
                            checked = dynamicColorEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch { preferencesRepository.setDynamicColorEnabled(enabled) }
                            },
                        )
                    }
                }
            }
        }
        item(key = "seed_color") {
            PreferenceCategory(title = "配色")
            Card(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                ListPreference(
                    title = "主题色",
                    entries = themeColorEntries,
                    selectedIndex = ThemeSeedColor.entries.indexOfFirst { it.argb == themeSeedColorArgb },
                    cancelButtonText = "取消",
                    startAction = {
                        Box(Modifier.size(20.dp).background(COUITheme.colorScheme.primary, CircleShape))
                    },
                    onSelectedIndexChange = { index ->
                        scope.launch { preferencesRepository.setThemeSeedColor(ThemeSeedColor.entries[index].argb) }
                    },
                )
            }
        }
        item(key = "ui_scale") {
            PreferenceCategory(title = "界面缩放")
            Card(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                SliderPreference(
                    title = "界面大小",
                    summary = "拖动滑杆调整整个应用的界面大小，不影响系统设置。",
                    value = uiScaleDraft,
                    onValueChange = { uiScaleDraft = it },
                    onValueChangeFinished = {
                        scope.launch { preferencesRepository.setUiScale(uiScaleDraft) }
                    },
                    valueText = "${(uiScaleDraft * 100).toInt()}%",
                    valueRange = UI_SCALE_MIN..UI_SCALE_MAX,
                )
            }
        }
        if (backHandlingToggleSupported()) {
            item(key = "system") {
                PreferenceCategory(title = "系统")
                Card(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                    SwitchPreference(
                        title = "内置返回处理",
                        summary = "开启后由 Navigation3 处理应用内返回并使用内置返回动画。",
                        checked = builtInBackHandlingEnabled,
                        onCheckedChange = onBuiltInBackHandlingEnabledChange,
                    )
                }
            }
        }
    }
}
