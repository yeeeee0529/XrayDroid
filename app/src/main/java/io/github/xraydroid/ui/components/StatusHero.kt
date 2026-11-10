package io.github.xraydroid.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

internal enum class StatusTone { Idle, Active, Error }

/**
 * 服務狀態主視覺：容器色與徽章形狀隨狀態改變，轉換中以載入指示器取代徽章。
 * 首頁的 3x-ui 與各 frp 實例共用，兩處的狀態語彙才會一致。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun StatusHero(
    tone: StatusTone,
    busy: Boolean,
    @DrawableRes icon: Int,
    label: String,
    title: String,
    modifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = when (tone) {
            StatusTone.Error -> scheme.errorContainer
            StatusTone.Active -> scheme.primaryContainer
            StatusTone.Idle -> scheme.secondaryContainer
        },
        label = "statusContainer"
    )
    val contentColor = when (tone) {
        StatusTone.Error -> scheme.onErrorContainer
        StatusTone.Active -> scheme.onPrimaryContainer
        StatusTone.Idle -> scheme.onSecondaryContainer
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLargeIncreased,
        color = container,
        contentColor = contentColor
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (busy) {
                    ContainedLoadingIndicator(
                        modifier = Modifier.size(64.dp),
                        containerColor = contentColor,
                        indicatorColor = container
                    )
                } else {
                    val shape = when (tone) {
                        StatusTone.Error -> MaterialShapes.SoftBurst
                        StatusTone.Active -> MaterialShapes.Cookie9Sided
                        StatusTone.Idle -> MaterialShapes.Cookie4Sided
                    }.toShape()
                    Box(Modifier.size(64.dp).background(contentColor, shape), contentAlignment = Alignment.Center) {
                        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(28.dp), tint = container)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    Text(title, style = MaterialTheme.typography.headlineMediumEmphasized, modifier = titleModifier)
                }
            }
            content()
        }
    }
}
