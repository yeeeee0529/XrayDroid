package io.github.xraydroid.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
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

// 進入子頁是返回的鏡像：上一頁 100% → 110% 淡出，子頁 90% → 100% 淡入，同樣以 35% 為界。
private const val FORWARD_MILLIS = 300

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
    var animation by remember { mutableStateOf<Job?>(null) }
    // 單一進度同時驅動手勢預覽、完成與取消：0 為目前頁面，1 為上一頁。
    val progress = remember { Animatable(0f) }

    // 以下三者只在事件與組合階段比對，不驅動畫面，因此不使用 State。
    // generation：每次手勢或外部切頁遞增，使較舊的收尾工作失效。
    // lastPage：上一次組合看到的頁面。
    // ownDestination：本元件剛完成返回、等待上層套用的目標頁。
    // committing：收尾動畫播放中的目標頁。
    val generation = remember { intArrayOf(0) }
    val lastPage = remember { arrayOf(page) }
    val ownDestination = remember { arrayOfNulls<String>(1) }
    val externalChange = remember { intArrayOf(-1) }
    val committing = remember { arrayOfNulls<String>(1) }

    // 進場剩餘量：1 為仍顯示上一頁，0 為子頁就位。只有從父頁進入子頁才播放，
    // 通知跳轉等非相鄰切換維持即時。在組合階段就設為 1，子頁第一格不會先以完整畫面閃現。
    var entering by remember { mutableFloatStateOf(0f) }
    if (lastPage[0] != page) {
        val own = ownDestination[0] == page
        ownDestination[0] = null
        entering = if (!own && parent(page) == lastPage[0]) 1f else 0f
        lastPage[0] = page
        if (!own) {
            // 頁面由通知或其他導覽切換時，使舊手勢及收尾失效。
            // 自己完成的返回不可走這條路：緊接而來的下一次返回可能已經開始，重設會把它吃掉。
            externalChange[0] = ++generation[0]
            target = null
            committing[0] = null
        }
    }
    // 進場期間子頁先不提供語意與觸控：無障礙服務若在透明階段讀到節點，會把它快取為不可見，
    // 而圖層透明度變化不會發出更新事件。就位後才掛上語意，節點以可見狀態首次出現。
    val settling by remember { derivedStateOf { entering > 0f } }

    LaunchedEffect(page) {
        // 外部切頁之後若已有新手勢開始，該手勢已自行清理，這裡不再介入。
        if (externalChange[0] != generation[0]) return@LaunchedEffect
        animation?.cancel()
        progress.snapTo(0f)
        if (entering > 0f) {
            animation = scope.launch {
                animate(1f, 0f, animationSpec = tween(FORWARD_MILLIS, easing = LinearEasing)) { value, _ -> entering = value }
            }
        }
    }

    fun commit(destination: String) {
        committing[0] = null
        if (destination != lastPage[0]) ownDestination[0] = destination
        navigate(destination)
        target = null
    }

    suspend fun begin(): Int {
        val token = ++generation[0]
        animation?.cancel()
        // 收尾動畫還沒播完就再次返回：先讓上一次返回立即生效，連按兩次才會退兩層，而不是重播同一層。
        committing[0]?.let(::commit)
        // 進場途中開始返回時直接就位，改由返回進度接手。
        entering = 0f
        progress.snapTo(0f)
        // 上一次返回的切頁可能尚未重新組合，以它的目標頁為起點。
        target = parent(ownDestination[0] ?: page)
        return token
    }

    fun complete(token: Int) {
        val destination = target ?: return
        committing[0] = destination
        animation = scope.launch {
            // 從手勢停下的位置把轉場播完再切頁；縮放的插值在繪製時套用，這裡保持線性。
            progress.animateTo(1f, tween(COMMIT_MILLIS, easing = LinearEasing))
            if (token == generation[0]) commit(destination)
        }
    }

    PredictiveBackHandler(enabled = parent(page) != null) { events ->
        val token = begin()
        try {
            events.collect { event ->
                if (token == generation[0]) progress.snapTo(event.progress.coerceIn(0f, 1f))
            }
            if (token == generation[0]) complete(token)
        } catch (cancelled: CancellationException) {
            // 系統會取消事件工作的 Job；回復動畫必須由仍有效的 Compose scope 執行。
            if (token == generation[0]) {
                animation = scope.launch {
                    progress.animateTo(0f, tween(CANCEL_MILLIS, easing = FastOutSlowInEasing))
                    if (token == generation[0]) target = null
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
                            if (foreground && !settling) {
                                Modifier
                            } else {
                                // 預覽頁與進場中的頁面只供繪製，不讓控制項接收觸控或無障礙操作。
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
                            if (target == null && entering > 0f) {
                                val amount = 1f - entering
                                val eased = BackEasing.transform(amount)
                                if (foreground) {
                                    scaleX = lerp(EXIT_SCALE, 1f, eased)
                                    alpha = ((amount - FADE_THROUGH_THRESHOLD) / (1f - FADE_THROUGH_THRESHOLD)).coerceIn(0f, 1f)
                                } else {
                                    scaleX = lerp(1f, ENTER_SCALE, eased)
                                    alpha = 1f - (amount / FADE_THROUGH_THRESHOLD).coerceIn(0f, 1f)
                                }
                                scaleY = scaleX
                                return@graphicsLayer
                            }
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
