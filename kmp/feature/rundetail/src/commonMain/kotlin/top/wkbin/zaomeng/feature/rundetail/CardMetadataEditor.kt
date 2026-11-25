package top.wkbin.zaomeng.feature.rundetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.Chip
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.basic.TextFieldMode
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.wkbin.zaomeng.data.api.CARD_CREATOR_NOTES_LIMIT
import top.wkbin.zaomeng.data.api.CARD_CHARACTER_NOTE_LIMIT
import top.wkbin.zaomeng.data.api.CARD_CHARACTER_NOTE_MAX_DEPTH
import top.wkbin.zaomeng.data.api.CARD_METADATA_LABEL_LIMIT
import top.wkbin.zaomeng.data.api.CARD_TAG_CHAR_LIMIT
import top.wkbin.zaomeng.data.api.CARD_TAG_LIMIT
import top.wkbin.zaomeng.data.api.SillyTavernCardMetadataDto
import top.wkbin.zaomeng.data.api.SillyTavernCharacterNoteDto
import top.wkbin.zaomeng.data.api.UpdateSillyTavernCardMetadataRequest
import top.wkbin.zaomeng.data.api.WorldBookInjectionRole
import top.wkbin.zaomeng.ui.coui.CouiAlertDialog

@Composable
internal fun CardMetadataEditor(
    metadata: SillyTavernCardMetadataDto,
    save: suspend (UpdateSillyTavernCardMetadataRequest) -> SillyTavernCardMetadataDto,
    onClose: (reload: Boolean) -> Unit,
) {
    var creator by rememberMetadataField(metadata.revision, metadata.creator)
    var version by rememberMetadataField(metadata.revision, metadata.characterVersion)
    var notes by rememberMetadataField(metadata.revision, metadata.creatorNotes)
    var tags by rememberMetadataField(metadata.revision, metadata.tags.joinToString("\n"))
    val parsedTags = tags.text.lineSequence().map(String::trim).filter(String::isNotBlank).distinct().toList()
    val update = UpdateSillyTavernCardMetadataRequest(
        revision = metadata.revision,
        creator = creator.text.changedFrom(metadata.creator),
        characterVersion = version.text.changedFrom(metadata.characterVersion),
        creatorNotes = notes.text.changedFrom(metadata.creatorNotes).takeUnless { metadata.creatorNotesTruncated },
        tags = parsedTags.takeIf { it != metadata.tags && !metadata.tagsTruncated },
    )
    val creatorTooLong = creator.text.length > CARD_METADATA_LABEL_LIMIT
    val versionTooLong = version.text.length > CARD_METADATA_LABEL_LIMIT
    val notesTooLong = notes.text.length > CARD_CREATOR_NOTES_LIMIT
    val tagsTooLong = !metadata.tagsTruncated &&
        (parsedTags.size > CARD_TAG_LIMIT || parsedTags.any { it.length > CARD_TAG_CHAR_LIMIT })
    val hasChanges = update.creator != null || update.characterVersion != null || update.creatorNotes != null || update.tags != null
    CardMetadataSaveDialog(
        title = "编辑作者资料",
        saveLabel = "保存作者资料",
        canSave = hasChanges && !creatorTooLong && !versionTooLong && !notesTooLong && !tagsTooLong,
        onSave = { save(update) },
        onClose = onClose,
    ) { enabled ->
        MetadataField("作者", creator, { creator = it }, enabled, isError = creatorTooLong)
        MetadataField("角色版本", version, { version = it }, enabled, isError = versionTooLong)
        if (metadata.tagsTruncated) {
            Text("原卡标签超过编辑范围，保存会保留原有标签。")
        } else {
            MetadataField("标签（一行一个）", tags, { tags = it }, enabled, singleLine = false, isError = tagsTooLong)
            if (tagsTooLong) Text("最多 32 个标签，每个最多 64 字符。", color = COUITheme.colorScheme.error)
        }
        if (metadata.creatorNotesTruncated) {
            Text("原卡作者说明超过 16000 字符，保存会保留全文。")
        } else {
            MetadataField("作者说明", notes, { notes = it }, enabled, singleLine = false, isError = notesTooLong)
            if (notesTooLong) Text("作者说明最多 16000 字符。", color = COUITheme.colorScheme.error)
        }
        if (creatorTooLong || versionTooLong) Text("作者和版本各最多 120 字符。", color = COUITheme.colorScheme.error)
    }
}

@Composable
internal fun CardCharacterNoteEditor(
    metadata: SillyTavernCardMetadataDto,
    save: suspend (UpdateSillyTavernCardMetadataRequest) -> SillyTavernCardMetadataDto,
    onClose: (reload: Boolean) -> Unit,
) {
    val original = metadata.characterNote
    var text by rememberMetadataField(metadata.revision, original.text)
    var depth by rememberMetadataField(metadata.revision, original.depth.toString())
    var role by rememberSaveable(metadata.revision) { mutableStateOf(original.role) }
    val depthNumber = depth.text.toIntOrNull()?.takeIf { it in 0..CARD_CHARACTER_NOTE_MAX_DEPTH }
    val tooLong = text.text.length > CARD_CHARACTER_NOTE_LIMIT
    val draft = SillyTavernCharacterNoteDto(text.text.trim(), depthNumber ?: original.depth, role)
    CardMetadataSaveDialog(
        title = "角色注记",
        saveLabel = "保存角色注记",
        canSave = depthNumber != null && !tooLong && draft != original,
        onSave = { save(UpdateSillyTavernCardMetadataRequest(metadata.revision, characterNote = draft)) },
        onClose = onClose,
    ) { enabled ->
        MetadataField("注记内容", text, { text = it }, enabled, singleLine = false, isError = tooLong)
        if (tooLong) {
            Text("最多 $CARD_CHARACTER_NOTE_LIMIT 字符。", color = COUITheme.colorScheme.error)
        } else {
            Text("清空内容可停止注入。", style = COUITheme.textStyles.bodyXS)
        }
        MetadataField(
            "注入深度", depth, { depth = it }, enabled, isError = depthNumber == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Text(
            if (depthNumber == null) "请输入 0 至 $CARD_CHARACTER_NOTE_MAX_DEPTH 的整数。" else "0 表示放在本轮输入之后。",
            color = if (depthNumber == null) COUITheme.colorScheme.error else COUITheme.colorScheme.labelSecondary,
            style = COUITheme.textStyles.bodyXS,
        )
        Text("消息角色", style = COUITheme.textStyles.bodyXS)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WorldBookInjectionRole.entries.forEach { option ->
                Chip(selected = role == option, onClick = { role = option }, label = option.characterNoteLabel, enabled = enabled)
            }
        }
    }
}

internal val WorldBookInjectionRole.characterNoteLabel: String
    get() = when (this) {
        WorldBookInjectionRole.System -> "系统"
        WorldBookInjectionRole.User -> "用户"
        WorldBookInjectionRole.Assistant -> "助手"
    }

@Composable
private fun CardMetadataSaveDialog(
    title: String,
    saveLabel: String,
    canSave: Boolean,
    onSave: suspend () -> Unit,
    onClose: (reload: Boolean) -> Unit,
    fields: @Composable (enabled: Boolean) -> Unit,
) {
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val dismiss = { if (!saving) onClose(error.isNotBlank()) }
    CouiAlertDialog(
        onDismissRequest = dismiss,
        title = title,
        dismissText = "取消",
        onDismiss = dismiss,
        confirmText = if (saving) "正在保存…" else saveLabel,
        confirmEnabled = canSave && !saving,
        onConfirm = {
            saving = true
            error = ""
            scope.launch {
                try {
                    onSave()
                    onClose(true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error = failure.message ?: "保存失败。"
                } finally {
                    saving = false
                }
            }
        },
        body = {
            Column(
                Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                fields(!saving)
                if (error.isNotBlank()) Text(error, color = COUITheme.colorScheme.error)
            }
        },
    )
}

@Composable
private fun rememberMetadataField(revision: String, text: String) =
    rememberSaveable(revision, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(text, selection = TextRange(text.length)))
    }

private fun String.changedFrom(previous: String): String? = trim().takeIf { it != previous }

@Composable
private fun MetadataField(
    label: String,
    value: TextFieldValue,
    onChange: (TextFieldValue) -> Unit,
    enabled: Boolean,
    singleLine: Boolean = true,
    isError: Boolean,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    Text(label, style = COUITheme.textStyles.bodyXS)
    TextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        backgroundMode = TextFieldMode.Line,
        label = "",
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else 5,
        enabled = enabled,
        isError = isError,
        keyboardOptions = keyboardOptions,
    )
}
