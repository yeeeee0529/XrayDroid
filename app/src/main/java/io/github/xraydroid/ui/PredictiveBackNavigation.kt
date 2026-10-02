package io.github.xraydroid.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun PredictiveBackNavigation(
    page: String,
    parent: (String) -> String?,
    onNavigate: (String) -> Unit,
    content: @Composable (String, () -> Unit) -> Unit
) {
    val scope = rememberCoroutineScope()
    val navigate by rememberUpdatedState(onNavigate)
    var target by remember { mutableStateOf<String?>(null) }
    var generation by remember { mutableStateOf(0) }
    var animation by remember { mutableStateOf<Job?>(null) }
    val progress = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }

    // 頁面由通知或其他導覽切換時，使舊手勢及收尾失效。
    LaunchedEffect(page) {
        generation++
        animation?.cancel()
        target = null
        progress.snapTo(0f)
        exit.snapTo(0f)
    }

    suspend fun begin(): Int {
        generation++
        animation?.cancel()
        progress.snapTo(0f)
        exit.snapTo(0f)
        target = parent(page)
        return generation
    }

    fun complete(token: Int) {
        val destination = target ?: return
        animation = scope.launch {
            // 獨立收尾進度讓前景逐漸消失；僅縮小到 90% 仍會在切頁時突跳。
            exit.animateTo(1f, tween(200, easing = FastOutSlowInEasing))
            if (token == generation) {
                navigate(destination)
                target = null
            }
        }
    }

    PredictiveBackHandler(enabled = parent(page) != null) { events ->
        val token = begin()
        try {
            events.collect { event ->
                if (token == generation) progress.snapTo(event.progress.coerceIn(0f, 1f))
            }
            if (token == generation) complete(token)
        } catch (cancelled: CancellationException) {
            // 系統會取消事件工作的 Job；回復動畫必須由仍有效的 Compose scope 執行。
            if (token == generation) {
                animation = scope.launch {
                    progress.animateTo(0f, tween(180, easing = FastOutSlowInEasing))
                    if (token == generation) target = null
                }
            }
            throw cancelled
        }
    }

    val requestBack: () -> Unit = {
        if (target == null) scope.launch { complete(begin()) }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 所有頁面使用同一個 key 呼叫位置，手勢前後不重建 remember 草稿與捲動位置。
        // 預先建立上一頁，避免首次組合與測量吃掉收尾動畫的大部分時間。
        val pages = listOfNotNull(target ?: parent(page), page).distinct()
        pages.forEach { current ->
            key(current) {
                val foreground = current == page
                Box(
                    Modifier
                        .fillMaxSize()
                        .zIndex(if (foreground) 1f else 0f)
                        .then(
                            if (foreground) {
                                Modifier
                            } else {
                                // 預覽頁只供繪製，不讓隱藏控制項接收觸控或無障礙操作。
                                Modifier.clearAndSetSemantics { }.pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                        }
                                    }
                                }
                            }
                        )
                        .graphicsLayer {
                            val amount = if (target == null) 0f else lerp(progress.value, 1f, exit.value)
                            if (foreground) {
                                scaleX = lerp(1f, 0.9f, amount)
                                scaleY = scaleX
                                alpha = if (target == null) 1f else 1f - exit.value
                                shape = RoundedCornerShape((24f * amount).dp)
                                clip = true
                                shadowElevation = 16.dp.toPx() * amount
                            } else {
                                alpha = if (target == null) 0f else 1f
                                scaleX = lerp(0.95f, 1f, amount)
                                scaleY = scaleX
                            }
                        }
                ) { content(current, requestBack) }
            }
        }
    }
}
