package ptq.mpga.ptqbookpageview.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import androidx.annotation.IntRange

private const val TAG = "PTQBookPageBitmapController"

private val BITMAP_COLOR_CONFIG = Bitmap.Config.ARGB_8888

/**
 * 处于当前页时，需要已经绘制好当前页、前一页和后一页的Bitmap，如果在第一页，则不画前一页，在最后同理
 *
 * 使用SideEffect监听重组，如果重组发生，则重画当前三页
 */
internal class PTQBookPageBitmapController(@IntRange(from = 0L) var totalPage: Int) {

    //记录前一页，当前页，后一页，如果current==0，则bitmapBuffer[0]为null。current==total同理
    private val bitmapBuffer = arrayOfNulls<Bitmap?>(3)

//    val bitmapSynthesizer = BitmapSynthesizer()

    //需要获取的页面，以及对应的BufferIndex
    private var needBitmapPages = mutableListOf<Pair<Int, Int>>()

    //每个 Buffer 里当前存的是哪一页，-1 表示还没有内容
    private val bufferPage = intArrayOf(-1, -1, -1)
    //bufferPage 记录的是哪一次排版（totalPage）下的页码，重新分页后映射失效
    private var bufferTotalPage = -1
    private var bufferWidth = 0
    private var bufferHeight = 0

    private val canvas = Canvas()

    //当前页面
    var currentPage = 0
        private set

    var exeRecompositionBlock: (() -> Unit)? = null
        set(value) {
            if (field != null) return
            field = value
        }

    private fun calculateNeedBitmapPages(page: Int) {
        if (page !in 0 until totalPage) return

        currentPage = page

        //需要缓存的页数范围
        val needs = mutableListOf<Pair<Int, Int>>()
        if (page > 0) {
            needs.add(Pair(page - 1, 0))
        }
        needs.add(Pair(page, 1))
        if (page < totalPage - 1) {
            needs.add(Pair(page + 1, 2))
        }

        if (!reuseBuffers(page, needs)) {
            bufferPage.fill(-1)
        }
        needBitmapPages = needs
    }

    /**
     * 翻页时复用上一页已经画好的 Bitmap：向后翻只需重画新的后一页，向前翻只需重画新的前一页。
     * 原来每翻一页都要把前后三页全部重画一遍，长文本页面上这一项就是主要卡顿来源。
     */
    private fun reuseBuffers(page: Int, needs: MutableList<Pair<Int, Int>>): Boolean {
        if (needs.size != 3) return false
        if (bufferTotalPage != totalPage) return false
        if (bufferWidth <= 1 || bufferHeight <= 1) return false
        if (!canReuse(0) || !canReuse(1) || !canReuse(2)) return false

        if (bufferPage[1] == page - 1 && bufferPage[2] == page) {
            // 向后翻一页：新前一页←原当前页，新当前页←原后一页，只需重画新的后一页
            bitmapBuffer[0] = bitmapBuffer[1]
            bitmapBuffer[1] = bitmapBuffer[2]
            bitmapBuffer[2] = null
            bufferPage[0] = page - 1
            bufferPage[1] = page
            bufferPage[2] = -1
            needs.clear()
            needs.add(Pair(page + 1, 2))
            return true
        }
        if (bufferPage[0] == page && bufferPage[1] == page + 1) {
            // 向前翻一页：新后一页←原当前页，新当前页←原前一页，只需重画新的前一页
            bitmapBuffer[2] = bitmapBuffer[1]
            bitmapBuffer[1] = bitmapBuffer[0]
            bitmapBuffer[0] = null
            bufferPage[2] = page + 1
            bufferPage[1] = page
            bufferPage[0] = -1
            needs.clear()
            needs.add(Pair(page - 1, 0))
            return true
        }
        return false
    }

    private fun canReuse(which: Int): Boolean {
        val bitmap = bitmapBuffer[which] ?: return false
        return !bitmap.isRecycled && bitmap.width == bufferWidth && bitmap.height == bufferHeight
    }

    fun needBitmapAt(page: Int) {
        //这个机制在高速刷新状态时（例如LazyColumn）有可能会出错，needBitmapPages也许还没清空，导致needBitmap需求被拒绝，不能正确刷新状态
        //但是为了性能，似乎只能这么做了
        if (needBitmapPages.isNotEmpty()) return
        // 前后三页都已就绪时直接返回：否则内容层每次刷新都会把整页重绘一遍，
        // 拖动过程中会反复整页重绘，表现为明显卡顿。
        if (isCacheComplete(page)) return
        calculateNeedBitmapPages(page)
        exeRecompositionBlock?.let { it() }
//        bitmapSynthesizer.clearSynthesizedCache()
    }

    /** 判断 [page] 及其前后页是否都已经画好，且尺寸 / 排版都没变 */
    private fun isCacheComplete(page: Int): Boolean {
        if (bufferTotalPage != totalPage) return false
        if (bufferPage[1] != page) return false
        if (page > 0 && (bufferPage[0] != page - 1 || !canReuse(0))) return false
        if (page < totalPage - 1 && (bufferPage[2] != page + 1 || !canReuse(2))) return false
        return canReuse(1)
    }

    /**
     * 强制切换到 [page]：丢弃正在排队的旧页面截图重新计算。
     * 阅读器切换章节 / 重新分页时总页数会变化，排队中的旧页码可能已经越界，
     * 沿用 [needBitmapAt] 的“排队时直接放弃”会导致旧页一直不被替换。
     */
    fun forceNeedBitmapAt(page: Int) {
        if (totalPage <= 0) return
        if (needBitmapPages.isEmpty() && isCacheComplete(page)) return
        needBitmapPages.clear()
        calculateNeedBitmapPages(page.coerceIn(0, totalPage - 1))
        exeRecompositionBlock?.let { it() }
    }

    fun refresh() {
        needBitmapAt(currentPage)
    }

    fun getNeedPage(): Int {
        if (totalPage <= 0) return 0
        //总页数可能在截图排队期间变小，先把当前页夹回有效范围，避免空列表取 first 崩溃
        if (currentPage !in 0 until totalPage) {
            currentPage = currentPage.coerceIn(0, totalPage - 1)
        }
        // 队列为空说明前后三页都已画好，这里不能再重建队列：
        // 动画每帧都会取一次当前页，重建队列会让三页在拖动过程中被反复整页重绘。
        if (needBitmapPages.isEmpty()) return currentPage
        return needBitmapPages.first().first
    }

    /**
     * 只分配一次内存，除非新的size有变化
     * @since v1.1.0
     */
    fun renderThenSave(width: Int, height: Int, render: (drawable: Canvas) -> Unit) {
        //如果不再需要bitmap，则不再绘制了
        if (needBitmapPages.isEmpty() || width <= 0 || height <= 0) {
            return
        }

        val first = needBitmapPages.first()

        var needNew = false
        if (bitmapBuffer[first.second] == null) {
            needNew = true
        } else {
            //新的大小发生变化（因为config不变，所以bitmap的大小可以认为只受width, height影响，而不再去计算allocationByteCount）
            bitmapBuffer[first.second]!!.let {
                if (width != it.width || height != it.height || it.isRecycled) {
                    // 这里不能 recycle：上一帧的绘制可能仍持有该 bitmap 的引用，
                    // 立刻回收会在下一帧抛「Canvas: trying to use a recycled bitmap」直接崩溃。
                    // 只丢引用交给 GC 回收即可。
                    bitmapBuffer[first.second] = null
                    needNew = true
                }
            }
        }

        if (needNew) {
            bitmapBuffer[first.second] = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)

//            with(bitmapSynthesizer) {
//                if (synthesizedBitmap.width != width || synthesizedBitmap.height != height) {
//                    synthesizedBitmap.recycle()
//                    synthesizedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
//                    resize(width, height)
//                }
//            }
        }

        canvas.let {
            it.setBitmap(bitmapBuffer[first.second]!!)
            render(it)
            it.setBitmap(null)
        }

        //记录这个 Buffer 里现在是哪一页，供下次翻页时复用
        bufferPage[first.second] = first.first
        bufferTotalPage = totalPage
        bufferWidth = width
        bufferHeight = height

        // List.removeFirst() 是 Java 21 的 SequencedCollection API，在 Android 低版本上不可用
        needBitmapPages.removeAt(0)
        if (needBitmapPages.isEmpty()) return
        exeRecompositionBlock?.let { it() }
    }

    fun destroy() {
        bitmapBuffer.forEach {
            if (it != null && !it.isRecycled) it.recycle()
        }
//        bitmapSynthesizer.destroy()
    }

    /**
     * 获取当前Bitmap
     * @param which 0=前一张 1=当前 2=下一张
     */
    fun getBitmapCurrent(which: Int): Bitmap {
        val cur = bitmapBuffer[which]
        if (cur == null || cur.isRecycled) {
            bitmapBuffer[which] = Bitmap.createBitmap(1, 1, BITMAP_COLOR_CONFIG)
        }
        return bitmapBuffer[which]!!
    }

    fun isRenderOk() = needBitmapPages.isEmpty()
}

///**
// * Bitmap合成器，在native层合成lowerBitmap和distortedBitmap
// * 把BitmapSynthesizer放进BitmapController里是为了生命周期方便一并管理
// * @since v1.1.0
// */
//internal class BitmapSynthesizer {
//    var synthesizedBitmap: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8)
//
//    private external fun nSynthesize(
//        lower: Bitmap,
//        synthesized: Bitmap,
//        WZS: FloatArray,
//        upsideDown: Boolean,
//    )
//
//    private external fun nResize(width: Int, height: Int, synthesized: Bitmap)
//    private external fun nDestroy()
//    private external fun nClearSynthesizedCache(synthesized: Bitmap)
//
//    companion object {
//        init {
//            System.loadLibrary("bitmap-mesh")
//        }
//    }
//
//    fun destroy() {
//        synthesizedBitmap.recycle()
//        nDestroy()
//    }
//
//    fun resize(width: Int, height: Int) = nResize(width, height, synthesizedBitmap)
//
//    /**
//     * 合成distortion和background
//     * @since v1.1.0
//     */
//    fun synthesize(
//        lower: Bitmap,
//        W: Point,
//        Z: Point,
//        S: Point,
//        upsideDown: Boolean,
//    ) {
//        nSynthesize(
//            lower,
//            synthesizedBitmap,
//            floatArrayOf(W.x, W.y, Z.x, Z.y, S.x, S.y),
//            upsideDown
//        )
//    }
//
//    fun clearSynthesizedCache() {
//        nClearSynthesizedCache(synthesizedBitmap)
//    }
//}