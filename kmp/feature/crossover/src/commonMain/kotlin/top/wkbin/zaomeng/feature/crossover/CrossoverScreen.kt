package top.wkbin.zaomeng.feature.crossover

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardDefaults
import io.github.suqi8.coui.kmp.basic.CircularProgressIndicator
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.LinearProgressIndicator
import io.github.suqi8.coui.kmp.basic.ProgressIndicatorDefaults
import io.github.suqi8.coui.kmp.basic.Surface
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.basic.TextFieldMode
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Ok
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.theme.COUIRoundCorner
import io.github.suqi8.coui.kmp.theme.COUITheme
import top.wkbin.zaomeng.ui.coui.CouiPage
import top.wkbin.zaomeng.ui.coui.CouiTopBarStyle
import top.wkbin.zaomeng.ui.coui.isWideWindow

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CrossoverScreen(
    viewModel: CrossoverViewModel,
    showBackButton: Boolean = true,
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.createdRunId) {
        if (state.createdRunId.isNotBlank()) {
            val runId = state.createdRunId
            viewModel.consumeCreatedRun()
            onCreated(runId)
        }
    }
    CouiPage(
        title = "跨书卷共演 Beta",
        topBarStyle = CouiTopBarStyle.Small,
        onBack = if (showBackButton) onBack else null,
        wide = isWideWindow(),
    ) {
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 7.dp),
                colors = CardDefaults.defaultColors(
                    color = COUITheme.colorScheme.containerThemeHalftone,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "测试功能 · 结果可能不稳定",
                        style = COUITheme.textStyles.headlineXS,
                        color = COUITheme.colorScheme.labelPrimary,
                    )
                    Text(
                        "人物表现、世界设定和关系演化仍可能偏离预期。创建时只复制人物资料快照，共演产生的聊天、记忆、关系和修正不会写回任何原书卷。",
                        style = COUITheme.textStyles.bodyXS,
                        color = COUITheme.colorScheme.labelSecondary,
                    )
                }
            }
        }
        item {
            TextField(
                value = state.title,
                onValueChange = viewModel::updateTitle,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 7.dp),
                label = "共演空间名称",
                backgroundMode = TextFieldMode.Rectangle,
                singleLine = true,
            )
        }
        item {
            TextField(
                value = state.worldSetting,
                onValueChange = viewModel::updateWorldSetting,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 7.dp),
                label = "独立世界设定（可选）",
                backgroundMode = TextFieldMode.Rectangle,
                minLines = 3,
                maxLines = 6,
            )
        }
        item {
            Text(
                "选择人物 ${state.selected.size}/$CROSSOVER_MAX_PARTICIPANTS",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 7.dp),
                style = COUITheme.textStyles.headlineXS,
                color = COUITheme.colorScheme.labelPrimary,
            )
        }
        if (state.loading) {
            item {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 7.dp),
                )
            }
        }
        items(state.runs, key = { it.runId }) { run ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 7.dp),
                colors = CardDefaults.defaultColors(
                    color = COUITheme.colorScheme.backgroundWithCard,
                ),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        run.title,
                        style = COUITheme.textStyles.headlineXS,
                        fontWeight = FontWeight.SemiBold,
                        color = COUITheme.colorScheme.labelPrimary,
                    )
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val itemWidth = (maxWidth - 16.dp) / 3
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            maxItemsInEachRow = 3,
                        ) {
                            run.availableCharacters.forEach { character ->
                                val choice = CrossoverChoice(run.runId, run.title, character)
                                val checked = state.selected.any {
                                    it.runId == run.runId && it.character == character
                                }
                                val enabled = !state.creating &&
                                    (checked || state.selected.size < CROSSOVER_MAX_PARTICIPANTS)
                                Surface(
                                    onClick = { viewModel.toggle(choice) },
                                    enabled = enabled,
                                    modifier = Modifier.width(itemWidth).height(48.dp),
                                    shape = SquircleShape(COUIRoundCorner.Normal.S, COUIRoundCorner.Normal.Weight),
                                    color = if (checked) {
                                        COUITheme.colorScheme.containerThemeHalftone
                                    } else {
                                        COUITheme.colorScheme.cardBackground
                                    },
                                    contentColor = if (checked) {
                                        COUITheme.colorScheme.labelPrimary
                                    } else {
                                        COUITheme.colorScheme.labelSecondary
                                    },
                                    border = BorderStroke(
                                        1.dp,
                                        if (checked) {
                                            COUITheme.colorScheme.primary
                                        } else {
                                            COUITheme.colorScheme.divider
                                        },
                                    ),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        if (checked) {
                                            Icon(
                                                COUIIcons.Ok,
                                                contentDescription = null,
                                                Modifier.size(16.dp),
                                            )
                                        }
                                        Text(
                                            character,
                                            style = COUITheme.textStyles.bodyXS,
                                            fontWeight = if (checked) {
                                                FontWeight.SemiBold
                                            } else {
                                                FontWeight.Normal
                                            },
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (state.error.isNotBlank()) {
            item {
                Text(
                    state.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 7.dp),
                    color = COUITheme.colorScheme.error,
                    style = COUITheme.textStyles.bodyM,
                )
            }
        }
        item {
            Button(
                onClick = viewModel::create,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 7.dp),
                enabled = !state.creating &&
                    state.title.isNotBlank() &&
                    state.selected.size in 2..CROSSOVER_MAX_PARTICIPANTS &&
                    state.selected.map { it.runId }.distinct().size >= 2,
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                if (state.creating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        colors = ProgressIndicatorDefaults.loadingViewColors(
                            foregroundColor = COUITheme.colorScheme.onPrimary,
                        ),
                    )
                }
                Text(
                    if (state.creating) "正在创建独立空间..." else "创建共演空间",
                    Modifier.padding(start = if (state.creating) 8.dp else 0.dp),
                )
            }
        }
    }
}
