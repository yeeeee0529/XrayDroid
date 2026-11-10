package io.github.xraydroid.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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

// Android 預測返回設計規範「全螢幕介面」的動態規格：
// 離開頁 100% → 90%、進入頁 110% → 100%；以 35% 進度為界做 fade through，
// 離開頁在此之前淡出完畢，進入頁在此之後才開始淡入，交界處兩者皆不可見。
private const val EXIT_SCALE = 0.9f
private const val ENTER_SCALE = 1.1f
private const val FADE_THROUGH_THRESHOLD = 0.35f
private const val COMMIT_MILLIS = 250
private const val CANCEL_MILLIS = 200

// 與 SystemUI 返回動畫相同的插值器，讓畫面在手勢初期就明顯反應並快速離開。
private val BackEasing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)

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
    // 單一進度同時驅動手勢預覽、完成與取消：0 為目前頁面，1 為上一頁。
    val progress = remember { Animatable(0f) }

    // 頁面由通知或其他導覽切換時，使舊手勢及收尾失效。
    LaunchedEffect(page) {
        generation++
        animation?.cancel()
        target = null
        progress.snapTo(0f)
    }

    suspend fun begin(): Int {
        generation++
        animation?.cancel()
        progress.snapTo(0f)
        target = parent(page)
        return generation
    }

    fun complete(token: Int) {
        val destination = target ?: return
        animation = scope.launch {
            // 從手勢停下的位置把轉場播完再切頁；縮放的插值在繪製時套用，這裡保持線性。
            progress.animateTo(1f, tween(COMMIT_MILLIS, easing = LinearEasing))
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
                    progress.animateTo(0f, tween(CANCEL_MILLIS, easing = FastOutSlowInEasing))
                    if (token == generation) target = null
                }
            }
            throw cancelled
        }
    }

    val requestBack: () -> Unit = {
        if (target == null) scope.launch { complete(begin()) }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
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
                            val amount = if (target == null) 0f else progress.value
                            val eased = BackEasing.transform(amount)
                            if (foreground) {
                                scaleX = lerp(1f, EXIT_SCALE, eased)
                                scaleY = scaleX
                                alpha = 1f - (amount / FADE_THROUGH_THRESHOLD).coerceIn(0f, 1f)
                                // 縮小後的頁面邊緣以圓角收邊，呈現為一塊可被拉離的介面。
                                shape = RoundedCornerShape((28f * eased).dp)
                                clip = true
                            } else {
                                scaleX = lerp(ENTER_SCALE, 1f, eased)
                                scaleY = scaleX
                                alpha = ((amount - FADE_THROUGH_THRESHOLD) / (1f - FADE_THROUGH_THRESHOLD)).coerceIn(0f, 1f)
                            }
                        }
                ) { content(current, requestBack) }
            }
        }
    }
}
