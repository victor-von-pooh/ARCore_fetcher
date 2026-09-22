package com.example.arcorefetcher.coverage

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.arcorefetcher.R

/**
 * 撮った視点方向を極座標のリングで示す。
 *
 * 中心が真上から、外周が水平から見た方向。扇形 1 つが [ViewCoverage] の 1 セルで、
 * 埋まったセルを塗る。**埋まっていない扇形が「まだ撮っていない画角」**。
 * いま自分がいるセルは縁取りで示すので、どちらへ動けば埋まるかが分かる。
 *
 * 表示専用。撮影にも出力にも影響しない。
 */
class CoverageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var snapshot: CoverageSnapshot? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(11f)
        isFakeBoldText = true
    }

    private val outer = RectF()
    private val inner = RectF()
    private val path = Path()

    private val colorCovered = color(R.color.coverage_covered)
    private val colorEmpty = color(R.color.coverage_empty)
    private val colorCurrent = color(R.color.coverage_current)
    private val colorGrid = color(R.color.coverage_grid)
    private val colorInk = color(R.color.figure_ink)
    private val colorDim = color(R.color.figure_dim)

    /** GL スレッドが作った不変コピーを UI スレッドから流し込む。 */
    fun update(value: CoverageSnapshot?) {
        snapshot = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) / 2f - dp(4f)
        if (radius <= 0f) return

        // 背景。カメラ映像の上に置くので、暗く敷かないと扇形が読めない。
        fill.color = color(R.color.coverage_backdrop)
        canvas.drawCircle(cx, cy, radius, fill)

        val snap = snapshot
        val bands = ViewCoverage.BAND_COUNT
        val bins = ViewCoverage.AZIMUTH_BINS
        val hole = radius * HOLE_RATIO
        val bandWidth = (radius - hole) / bands
        val sweep = 360f / bins

        for (band in 0 until bands) {
            // band 0（低い仰角）を外側に置く。周回撮影で一番よく使う帯を、
            // 面積が大きく押しやすい外周に対応させる。
            val rOut = radius - band * bandWidth
            val rIn = rOut - bandWidth
            outer.set(cx - rOut, cy - rOut, cx + rOut, cy + rOut)
            inner.set(cx - rIn, cy - rIn, cx + rIn, cy + rIn)

            for (az in 0 until bins) {
                val cell = band * bins + az
                // 方位角 0 を画面の上に置き、時計回りに増やす。Canvas の角度は
                // 3 時方向が 0 度なので 90 度戻す。
                val start = -90f + az * sweep
                buildSector(start, sweep)

                fill.color = when {
                    snap == null || !snap.hasCenter -> colorEmpty
                    snap.covered[cell] -> colorCovered
                    else -> colorEmpty
                }
                canvas.drawPath(path, fill)

                stroke.color = colorGrid
                stroke.strokeWidth = dp(1f)
                canvas.drawPath(path, stroke)

                if (snap != null && snap.current == cell) {
                    stroke.color = colorCurrent
                    stroke.strokeWidth = dp(2.5f)
                    canvas.drawPath(path, stroke)
                }
            }
        }

        drawCenterLabel(canvas, cx, cy, snap)
    }

    /** 扇形（ドーナツの一片）を [path] に組む。 */
    private fun buildSector(start: Float, sweep: Float) {
        path.reset()
        val gap = SECTOR_GAP_DEG
        path.arcTo(outer, start + gap / 2f, sweep - gap, true)
        path.arcTo(inner, start + sweep - gap / 2f, -(sweep - gap), false)
        path.close()
    }

    /**
     * 中心の表示。
     *
     * 中心が決まる前は「何をすれば始まるか」を出す。無言の空リングだと、
     * 壊れているのか使い方が違うのか分からない。
     */
    private fun drawCenterLabel(canvas: Canvas, cx: Float, cy: Float, snap: CoverageSnapshot?) {
        if (snap == null || !snap.hasCenter) {
            label.color = colorDim
            canvas.drawText(context.getString(R.string.coverage_aim), cx, cy + dp(4f), label)
            return
        }
        label.color = colorInk
        canvas.drawText("${snap.coveredCount} / ${snap.cellCount}", cx, cy + dp(1f), label)
        if (!snap.locked) {
            label.color = colorDim
            canvas.drawText(context.getString(R.string.coverage_unlocked), cx, cy + dp(14f), label)
        }
    }

    private fun color(id: Int): Int = ContextCompat.getColor(context, id)
    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private companion object {
        /**
         * 中央の空き（半径に対する比）。
         *
         * 扇形を中心まで伸ばすと、内側ほど細くなって色が読めない。
         * 空けたぶんは「何 / 何」の表示に使う。
         */
        const val HOLE_RATIO = 0.34f

        /** 隣り合う扇形のあいだに空ける角度 [度]。境界を見せるためだけのもの。 */
        const val SECTOR_GAP_DEG = 0.8f
    }
}
