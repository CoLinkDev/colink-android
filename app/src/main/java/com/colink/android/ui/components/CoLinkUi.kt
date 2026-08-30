package com.colink.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape

val LocalAccountAction = staticCompositionLocalOf<(@Composable () -> Unit)?> { null }
val ScreenHeaderHeight = 52.dp

/** 内容组之间的统一间距：设备列表页的组后间隔条目、设置页与设备详情页的父容器排列间距均使用此值。 */
val ContentGroupSpacing = 20.dp

@Composable
fun ScreenColumn(
    title: String,
    icon: ImageVector? = null,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    showLandscapeAccountAction: Boolean = true,
    headerOverride: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = if (isLandscape) 8.dp else 12.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(if (isLandscape) 6.dp else 8.dp),
    ) {
        if (headerOverride != null) {
            headerOverride()
        } else {
            ScreenHeader(
                title = title,
                icon = icon,
                subtitle = subtitle,
                action = action,
                showLandscapeAccountAction = showLandscapeAccountAction,
            )
        }
        content()
    }
}

@Composable
fun ScreenHeader(
    title: String,
    icon: ImageVector? = null,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
    showLandscapeAccountAction: Boolean = true,
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val localAccountAction = LocalAccountAction.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ScreenHeaderHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 20.sp,
                        lineHeight = 24.sp,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (isLandscape) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.offset(x = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            action?.invoke()
            if (isLandscape && showLandscapeAccountAction) {
                localAccountAction?.invoke()
            }
        }
    }
}

@Composable
fun StateMessage(
    text: String?,
    modifier: Modifier = Modifier,
    isError: Boolean = true,
) {
    AnimatedVisibility(
        visible = !text.isNullOrBlank(),
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isError) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                contentColor = if (isError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
            ),
            shape = MaterialTheme.shapes.medium,
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (isError) Icons.Default.ErrorOutline else Icons.Default.CheckCircle,
                    contentDescription = null,
                )
                Text(
                    text = text.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String = "",
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        if (body.isNotBlank()) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (action != null) {
            action()
        }
    }
}

@Composable
fun BadgeChip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    androidx.compose.material3.Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/**
 * 内容组风格共享组件：与设备列表、设置页一致的"分组标题 + 圆角容器"外观。
 * - 组标题：labelLarge、onSurfaceVariant，水平 16dp 内边距，距容器 8dp。
 * - 容器：surfaceContainer 背景、16dp 圆角。
 */

fun contentGroupShape(isFirst: Boolean, isLast: Boolean): Shape =
    when {
        isFirst && isLast -> RoundedCornerShape(16.dp)
        isFirst -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        isLast -> RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
        else -> RoundedCornerShape(0.dp)
    }

/** 内容组上方的分组标题。组与组之间的间距由上一个组的底部提供，不在此处添加顶距。 */
@Composable
fun ContentGroupHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(
            start = 16.dp,
            end = 16.dp,
            bottom = 8.dp,
        ),
    )
}

/** 内容组中按位置圆角的分段条目；相邻条目之间保留 4dp 的页面底色间隔。 */
@Composable
fun ContentGroupItem(
    isFirst: Boolean,
    isLast: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = contentGroupShape(isFirst, isLast),
        ) {
            content()
        }
        if (!isLast) {
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(MaterialTheme.colorScheme.surface),
            )
        }
    }
}

/** 带分组标题的独立内容组容器。 */
@Composable
fun ContentGroup(
    title: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        ContentGroupHeader(title = title)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = containerColor,
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(content = content)
        }
    }
}

