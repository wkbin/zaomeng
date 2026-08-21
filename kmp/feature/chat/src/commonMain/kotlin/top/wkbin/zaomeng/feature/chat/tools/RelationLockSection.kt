package top.wkbin.zaomeng.feature.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.feature.chat.insights.firstString

@Composable
internal fun RelationLockSection(
    session: DialogueSessionDto,
    enabled: Boolean,
    onShowHelp: () -> Unit,
    onRequestChange: (String, Boolean) -> Unit,
) {
    ToolSection(
        title = "会话关系锁",
        action = {
            IconButton(onClick = onShowHelp) {
                Icon(Icons.Outlined.Info, contentDescription = "关系锁说明")
            }
        },
    ) {
        session.relationMatrix.forEach { (matrixKey, element) ->
            val relation = element as? JsonObject ?: JsonObject(emptyMap())
            val pairKey = relation.stringValue("pair_key").ifBlank { matrixKey }
            if (pairKey.isNotBlank()) {
                val locked = session.relationLocks[pairKey]
                    ?.jsonPrimitive?.booleanOrNull == true
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            relation.firstString("label", "relationship_type").ifBlank { pairKey },
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            pairKey,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = { onRequestChange(pairKey, !locked) },
                        enabled = enabled,
                    ) {
                        Icon(
                            if (locked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                            contentDescription = if (locked) "解除锁定" else "锁定关系",
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(if (locked) "已锁定" else "锁定")
                    }
                }
            }
        }
    }
}
