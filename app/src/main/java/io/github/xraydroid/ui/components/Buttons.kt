package io.github.xraydroid.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/** 按鈕內容：前導圖示加文字；需放在按鈕的 RowScope 內。 */
@Composable
internal fun ButtonLabel(@DrawableRes icon: Int, text: String, large: Boolean = false) {
    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(if (large) 24.dp else 18.dp))
    Spacer(Modifier.width(8.dp))
    Text(text, style = if (large) MaterialTheme.typography.titleMedium else LocalTextStyle.current, maxLines = 1)
}
