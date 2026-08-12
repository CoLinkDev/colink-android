package com.colink.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * CoLink 统一警告卡片：图标 + 标题 + 正文 + 可选动作按钮。
 * 用于"版本过低/功能禁用/加载失败"等页面级提示场景。
 * 组件无状态，dismissed 状态由调用方管理。
 *
 * 无动作按钮时正文下方保留更大的底部空白；有动作按钮时底部空白紧凑。
 *
 * @param title 卡片标题。
 * @param modifier 外部修饰符。
 * @param body 正文文本；为 null 时不渲染。
 * @param icon 左侧图标；默认警告图标。
 * @param actionLabel 底部动作按钮文案；与 [onAction] 均非空时显示。
 * @param onAction 底部动作按钮回调；与 [actionLabel] 均非空时显示。
 */
@Composable
fun WarningCard(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    icon: ImageVector = Icons.Default.Warning,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        val hasAction = actionLabel != null && onAction != null
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 16.dp,
                    top = 16.dp,
                    end = 16.dp,
                    bottom = if (hasAction) 6.dp else 16.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(24.dp),
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                if (body != null) {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f),
                    )
                }
            }
            if (hasAction) {
                TextButton(
                    onClick = onAction,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(
                        text = actionLabel,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}
