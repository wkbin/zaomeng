package top.wkbin.zaomeng.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import top.wkbin.zaomeng.data.preferences.AppPreferencesRepository
import top.wkbin.zaomeng.data.preferences.ThemeMode
import top.wkbin.zaomeng.data.preferences.ThemeSeedColor
import top.wkbin.zaomeng.data.preferences.UI_SCALE_DEFAULT
import top.wkbin.zaomeng.data.preferences.UI_SCALE_MAX
import top.wkbin.zaomeng.data.preferences.UI_SCALE_MIN

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    preferencesRepository: AppPreferencesRepository = koinInject(),
) {
    val themeMode by preferencesRepository.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
    val themeSeedColorArgb by preferencesRepository.themeSeedColorArgb.collectAsStateWithLifecycle(0L)
    val uiScale by preferencesRepository.uiScale.collectAsStateWithLifecycle(UI_SCALE_DEFAULT)
    var uiScaleDraft by remember { mutableFloatStateOf(uiScale) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(uiScale) { uiScaleDraft = uiScale }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("外观") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            item {
                Text(
                    "主题模式",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 32.dp, top = 16.dp, bottom = 8.dp, end = 32.dp),
                )
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column {
                        ThemeMode.values().forEachIndexed { index, mode ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            ThemeModeRow(
                                mode = mode,
                                selected = themeMode == mode,
                                onClick = { scope.launch { preferencesRepository.setThemeMode(mode) } },
                            )
                        }
                    }
                }
            }
            item {
                AnimatedVisibility(visible = themeMode.isMonet) {
                    Column {
                        Text(
                            "主题色",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 32.dp, top = 16.dp, bottom = 8.dp, end = 32.dp),
                        )
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        ) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                ThemeSeedColor.entries.forEach { seed ->
                                    SeedColorDot(
                                        seed = seed,
                                        selected = themeSeedColorArgb == seed.argb,
                                        onClick = { scope.launch { preferencesRepository.setThemeSeedColor(seed.argb) } },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item {
                Column {
                    Text(
                        "界面缩放",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 32.dp, top = 16.dp, bottom = 8.dp, end = 32.dp),
                    )
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                "拖动滑杆调整整个应用的界面大小（参考 KernelSU 页面缩放），不影响系统设置。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "${(uiScaleDraft * 100).toInt()}%",
                                modifier = Modifier.align(Alignment.End),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Slider(
                                value = uiScaleDraft,
                                onValueChange = { uiScaleDraft = it },
                                onValueChangeFinished = {
                                    scope.launch { preferencesRepository.setUiScale(uiScaleDraft) }
                                },
                                valueRange = UI_SCALE_MIN..UI_SCALE_MAX,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeModeRow(mode: ThemeMode, selected: Boolean, onClick: () -> Unit) {
    val subtitle = when (mode) {
        ThemeMode.SYSTEM -> "根据设备当前的系统外观自动切换。"
        ThemeMode.LIGHT -> "始终使用浅色界面。"
        ThemeMode.DARK -> "始终使用深色界面。"
        ThemeMode.MONET_SYSTEM -> "跟随系统，并使用动态取色。"
        ThemeMode.MONET_LIGHT -> "始终浅色，并使用动态取色。"
        ThemeMode.MONET_DARK -> "始终深色，并使用动态取色。"
    }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(mode.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) Icon(Icons.Default.CheckCircle, contentDescription = "已选中", tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SeedColorDot(
    seed: ThemeSeedColor,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    if (seed == ThemeSeedColor.DEFAULT) {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    } else {
                        seed.color
                    }
                )
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                    shape = CircleShape,
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = if (seed == ThemeSeedColor.DEFAULT) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.White
                    },
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Text(
            seed.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
