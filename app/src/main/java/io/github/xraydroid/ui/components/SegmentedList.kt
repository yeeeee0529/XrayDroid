package io.github.xraydroid.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val SegmentOuterCorner = 20.dp
private val SegmentInnerCorner = 4.dp

/** 分段式清單的形狀：整組外緣大圓角，相鄰項目之間小圓角。 */
internal fun segmentShape(index: Int, count: Int): Shape {
    val top = if (index == 0) SegmentOuterCorner else SegmentInnerCorner
    val bottom = if (index == count - 1) SegmentOuterCorner else SegmentInnerCorner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

@Composable
internal fun SegmentedColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
}

/** 分段清單中的一列：前導、標題與說明、尾端控制項。 */
@Composable
internal fun SegmentedItem(
    index: Int,
    count: Int,
    headline: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val shape = segmentShape(index, count)
    val color = MaterialTheme.colorScheme.surfaceBright
    val body: @Composable () -> Unit = {
        SegmentRow(leading, trailing) {
            Text(headline, style = MaterialTheme.typography.titleMedium)
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), enabled = enabled, shape = shape, color = color, content = body)
    } else {
        Surface(modifier = modifier.fillMaxWidth(), shape = shape, color = color, content = body)
    }
}

@Composable
internal fun SegmentRow(
    leading: (@Composable () -> Unit)?,
    trailing: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
        trailing?.invoke()
    }
}

/** 圖示徽章：用色塊形狀承載圖示，作為清單與卡片的視覺錨點。 */
@Composable
internal fun IconBadge(
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    size: Dp = 40.dp
) {
    Box(modifier.size(size).background(container, shape), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(size * 0.5f), tint = content)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.titleMediumEmphasized,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 4.dp, top = 12.dp)
    )
}
