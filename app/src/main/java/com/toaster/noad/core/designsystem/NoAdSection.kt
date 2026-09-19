package com.toaster.noad.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 通用「区块」容器：圆角卡片 + 标题 + 可选尾部动作。
 *
 * 用于替代各页面重复实现的 Surface 卡片，统一圆角与抬高。
 */
@Composable
fun NoAdSection(
    title: String? = null,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (title != null) {
                SectionHeader(title = title, subtitle = subtitle)
                Spacer(Modifier.height(12.dp))
            }
            content()
        }
    }
}

/**
 * 区块标题行。
 */
@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 圆形图标徽标。用于卡片左侧的视觉标识。
 */
@Composable
fun IconBadge(
    icon: ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    containerColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 44.dp,
) {
    Surface(
        shape = CircleShape,
        color = containerColor,
        modifier = modifier.size(size),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.padding(size / 3.7f),
        )
    }
}

/**
 * 一行「标签 + 值」，用于状态详情展示。
 */
@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = valueColor,
        )
    }
}
