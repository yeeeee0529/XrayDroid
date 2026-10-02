package io.github.xraydroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.xraydroid.runtime.TextResource

/** 在 Compose 中解析顯示文字。 */
@Composable
fun TextResource.resolve(): String = if (args.isEmpty()) stringResource(id) else stringResource(id, *args.toTypedArray())
