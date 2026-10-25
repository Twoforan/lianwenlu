package ptq.mpga.ptqbookpageview.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.EmptyCoroutineContext

private const val TAG = "PTQBookPageView"

internal class PTQBookPageViewScopeImpl : PTQBookPageViewScope {
    var contents: @Composable BoxScope.(currentPage: Int, refresh: () -> Unit) -> Unit = { _, _ -> }
    var onTurnPageRequest: (Int, Boolean, Boolean) -> Unit = { _, _, _ -> }
    var tapBehavior: ((leftUp: Point, rightDown: Point, touchPoint: Point) -> Boolean?)? = null
    var dragBehavior: ((rightDown: Point, initialTouchPoint: Point, lastTouchPoint: Point, isRightToLeftWhenStart: Boolean) -> Pair<Boolean, Boolean?>)? = null
    var responseDragWhen: ((rightDown: Point, startTouchPoint: Point, currentTouchPoint: Point) -> Boolean?)? = null
    //由 PTQBookPageViewInner 绑定的程序化翻页入口（音量键等）
    var requestTurn: ((nextOrPrevious: Boolean) -> Unit)? = null

    override fun onTurnPageRequest(block: (currentPage: Int, nextOrPrevious: Boolean, success: Boolean) -> Unit) {
        onTurnPageRequest = block
    }

    override fun contents(block: @Composable BoxScope.(currentPage: Int, refresh: () -> Unit) -> Unit) {
        contents = block
    }

    override fun tapBehavior(block: (leftUp: Point, rightDown: Point, touchPoint: Point) -> Boolean?) {
        tapBehavior = block
    }

    override fun responseDragWhen(block: (rightDown: Point, startTouchPoint: Point, currentTouchPoint: Point) -> Boolean?) {
        responseDragWhen = block
    }

    override fun dragBehavior(block: (rightDown: Point, initialTouchPoint: Point, lastTouchPoint: Point, isRightToLeftWhenStart: Boolean) -> Pair<Boolean, Boolean?>) {
        dragBehavior = block
    }
}

/**
 * @param modifier 修饰符[支持以padding方式设置组件位置和大小，或以height和width方式设置大小，不支持offset和size修饰符]
 * @param config 组件配置，请使用[rememberPTQBookPageViewConfig]
 * @param state 组件状态，请使用[rememberPTQBookPageViewState]
 * @param request 程序化翻页入口（音量键等），可由外部持有并调用，动画与点击翻页一致
 * @param ptqBookPageViewScope 翻页器提供的各类回调 [PTQBookPageViewScope]
 */
@Composable
fun PTQBookPageView(
    modifier: Modifier = Modifier, config: PTQBookPageViewConfig = PTQBookPageViewConfig(), state: PTQBookPageViewState, request: PTQBookPageViewRequest? = null, ptqBookPageViewScope: PTQBookPageViewScope.() -> Unit
) {
    Box(
        modifier.fillMaxSize()
    ) {
        val controller by remember {
            mutableStateOf(PTQBookPageBitmapController(state.pageCount))
        }

        DisposableEffect(Unit) {
            onDispose {
                controller.destroy()
            }
        }

        remember(state) {
            val pageCountChanged = controller.totalPage != state.pageCount
            controller.totalPage = state.pageCount
            state.currentPage?.let {
                //总页数变化（换章 / 重新分页）或外部页码跳转时强制重算，
                //避免旧页码的截图排队把新页码挤掉，甚至在新页数下越界
                if (pageCountChanged || controller.currentPage != it) {
                    controller.forceNeedBitmapAt(it)
                } else {
                    controller.needBitmapAt(it)
                }
            }
            mutableStateOf(state)
        }

        val callbacks = rememberUpdatedState(PTQBookPageViewScopeImpl().apply(ptqBookPageViewScope))

        //程序化翻页 / 重新截屏入口，始终读取最新的回调实现
        SideEffect {
            val scope = callbacks.value
            request?.action = { next -> scope.requestTurn?.invoke(next) }
            request?.refreshAction = { controller.forceNeedBitmapAt(controller.currentPage) }
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                object : AbstractComposeView(context) {

                    @Composable
                    override fun Content() {
                        //重组触发器
                        var recomposeTrigger by remember { mutableStateOf(0L) }

                        controller.exeRecompositionBlock = {
                            recomposeTrigger = System.currentTimeMillis()
                        }

                        Box(
                            Modifier
                                .fillMaxSize()
                                .align(Alignment.Center)) {
                            callbacks.value.contents(this, controller.getNeedPage()) { controller.refresh() }
                            recomposeTrigger
                            invalidate()
                        }
                    }

                    override fun dispatchDraw(canvas: Canvas) {
                        /**
                         * 复用canvas、bitmap，且把流程控制在BitmapController内部，dispatchDraw只负责dispatchDraw
                         * @since v1.1.0
                         */
                        controller.renderThenSave(width, height) {
                            super.dispatchDraw(it)
                        }
                    }
                }
            }
        )

        //貌似必须包裹在CompositionLocalProvider，否则就会不断重组，没想通为什么
        CompositionLocalProvider(
            LocalPTQBookPageViewConfig provides config
        ) {
            var position by remember { mutableStateOf(Rect.Zero) }

            Box(
                Modifier
                    .fillMaxSize()
                    .align(Alignment.Center)
                    .clipToBounds()
                    .onGloballyPositioned {
                        position = it.boundsInRoot()
                    }
            ) {
                PTQBookPageViewInner(
                    bounds = position,
                    controller = controller,
                    callbacks = callbacks.value,
                    onNext = {
                        with(controller) {
                            val pageNow = currentPage
                            val canTurn = pageNow < totalPage - 1
                            // 翻页动画结束时先把控制器的当前页同步过去，
                            // 否则 Idle 状态会用旧页码重绘一帧，表现为每次翻页都闪一下
                            if (canTurn) forceNeedBitmapAt(pageNow + 1)
                            callbacks.value.onTurnPageRequest(pageNow, true, canTurn)
                        }
                    }, onPrevious = {
                        with(controller) {
                            val pageNow = currentPage
                            val canTurn = pageNow > 0
                            if (canTurn) forceNeedBitmapAt(pageNow - 1)
                            callbacks.value.onTurnPageRequest(pageNow, false, canTurn)
                        }
                    }
                )
            }
        }

    }
}


