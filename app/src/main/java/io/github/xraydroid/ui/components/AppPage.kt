package io.github.xraydroid.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.xraydroid.R

/** 浮動工具列或 FAB 佔用的高度；清單底部需留白，最後一項才不會被遮住。 */
internal val FloatingControlClearance = 96.dp

/**
 * 所有頁面共用的骨架：可收合的大型頂端列，加上置中限寬的捲動清單。
 * 整頁只有這一個捲動容器，浮動控制項疊在清單之上。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AppPage(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backLabel: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    bottomClearance: Dp = 0.dp,
    bottomOverlay: (@Composable ColumnScope.() -> Unit)? = null,
    content: LazyListScope.() -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val container = MaterialTheme.colorScheme.surfaceContainer
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = container,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = backLabel)
                        }
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = container, scrolledContainerColor = container),
                scrollBehavior = scrollBehavior
            )
        },
        floatingActionButton = floatingActionButton
    ) { insets ->
        val direction = LocalLayoutDirection.current
        // 清單從頂端列下方開始，底部則延伸到浮動控制項與系統列之下。
        Box(Modifier.fillMaxSize().padding(top = insets.calculateTopPadding()), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 760.dp).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(
                    start = insets.calculateStartPadding(direction) + 16.dp,
                    top = 8.dp,
                    end = insets.calculateEndPadding(direction) + 16.dp,
                    bottom = insets.calculateBottomPadding() + 24.dp + bottomClearance
                ),
                content = content
            )
            if (bottomOverlay != null) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(
                            start = insets.calculateStartPadding(direction) + 16.dp,
                            end = insets.calculateEndPadding(direction) + 16.dp,
                            bottom = insets.calculateBottomPadding() + 16.dp
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = bottomOverlay
                )
            }
        }
    }
}
