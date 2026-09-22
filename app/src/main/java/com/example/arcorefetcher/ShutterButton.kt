package com.example.arcorefetcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * カメラアプリと同じ見た目のシャッター。外周のリングと中の白丸。
 *
 * 押している間だけ中の丸を縮めて、押せたことを指の下で返す。撮れたかどうかは
 * これとは別で、実際にフレームを保存できたときに画面全体が光る
 * （[MainActivity] の flashCaptured）。**押した合図と撮れた合図を分けてある**のは、
 * ウォームアップ前や画像が来ていないときに押しても撮れないことがあるため。
 * 押した瞬間に撮れた演出を出すと、撮れていないのに撮れたと誤解させる。
 *
 * 無効のときは全体を暗くする。撮れない理由は画面上部の表示が持つ。
 */
class ShutterButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringColor = ContextCompat.getColor(context, R.color.shutter_ring)
    private val discColor = ContextCompat.getColor(context, R.color.shutter_disc)

    /** 中の丸の拡大率。押下で縮む。 */
    private var discScale = 1f
    private var animator: ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
    }

    override fun setPressed(pressed: Boolean) {
        val changed = isPressed != pressed
        super.setPressed(pressed)
        if (changed) animateDisc(if (pressed) PRESSED_SCALE else 1f)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) / 2f
        if (radius <= 0f) return

        val dim = if (isEnabled) 1f else DISABLED_ALPHA

        val ringWidth = radius * RING_WIDTH_RATIO
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ringWidth
        paint.color = ringColor
        paint.alpha = (Color.alpha(ringColor) * dim).toInt()
        canvas.drawCircle(cx, cy, radius - ringWidth / 2f, paint)

        paint.style = Paint.Style.FILL
        paint.color = discColor
        paint.alpha = (Color.alpha(discColor) * dim).toInt()
        canvas.drawCircle(cx, cy, radius * DISC_RATIO * discScale, paint)
    }

    private fun animateDisc(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(discScale, target).apply {
            duration = PRESS_ANIM_MS
            addUpdateListener {
                discScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        /** 外周リングの太さ（半径に対する比）。 */
        const val RING_WIDTH_RATIO = 0.09f

        /** 中の白丸の大きさ（半径に対する比）。リングとの間に隙間を残す。 */
        const val DISC_RATIO = 0.76f

        const val PRESSED_SCALE = 0.86f
        const val PRESS_ANIM_MS = 90L
        const val DISABLED_ALPHA = 0.35f
    }
}
