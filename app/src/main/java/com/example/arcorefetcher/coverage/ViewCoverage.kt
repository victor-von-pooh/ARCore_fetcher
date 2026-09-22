package com.example.arcorefetcher.coverage

import kotlin.math.asin
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

/** どのセルにも割り当てられない（被写体中心が未確定、または近すぎる）。 */
const val NO_CELL = -1

/**
 * 被写体をどの方向から撮ったかを溜めて、まだ撮っていない方向を示す。
 *
 * **出力には一切影響しない。** transforms.json にも撮影データにも入らず、
 * フレームを弾くこともしない。これは「物体を中心に周回して撮る」用途を前提にした
 * 撮り方の助言であって、アプリの仕様ではない（README「用途について」）。
 * 用途が違う人には無意味なので、画面から消せるようにしてある。
 *
 * 見ているのは**視点の方向だけ**で、被写体の形は持たない。したがって
 * 「その方向から撮った」ことは分かるが「その面が見えた」ことは分からない。
 * 凹んだ部分や取っ手の裏は、セルが埋まっていても写っていないことがある。
 * 面まで見るには粗くても形状を持つ必要があり、それは実質的に点群の生成になる。
 *
 * GL スレッドからのみ触る。UI スレッドへは [snapshot] の不変コピーを渡す。
 */
class ViewCoverage {

    /**
     * 被写体の中心（world 座標）。未確定なら null。
     *
     * 撮影が始まるまでは画面中央の深度で追従し、最初の 1 枚で固定する。
     * 撮っている最中に中心が動くと、埋めたセルの意味が変わってしまうため。
     */
    private var center: FloatArray? = null
    private var locked = false

    /** 埋まったセル。添字は `band * AZIMUTH_BINS + azimuth`。 */
    private val covered = BooleanArray(AZIMUTH_BINS * BAND_COUNT)

    /**
     * 撮影したフレームのカメラ位置。
     *
     * 中心を取り直したときにセルを割り当て直すために持つ。位置さえ残っていれば
     * 新しい中心で全部引き直せるので、取り直しても撮影済みの情報を失わない。
     */
    private val capturedFrom = ArrayList<FloatArray>()

    /** いま向いているセル。[NO_CELL] なら判定できていない。 */
    private var current = NO_CELL

    val hasCenter: Boolean get() = center != null
    val isLocked: Boolean get() = locked

    /**
     * 画面中央の深度から得た点で中心を追従させる。固定後は何もしない。
     *
     * 生の値をそのまま入れると手ぶれでセルが跳ねるので指数移動平均で均す。
     */
    fun observeCenter(point: FloatArray) {
        if (locked) return
        val c = center
        if (c == null) {
            center = point.copyOf()
            return
        }
        for (i in 0..2) c[i] += (point[i] - c[i]) * CENTER_SMOOTHING
    }

    /** 中心を固定する。最初の 1 枚を撮った時点で呼ぶ。 */
    fun lock() {
        if (center != null) locked = true
    }

    /**
     * 中心を取り直して固定し、撮影済みのセルを新しい中心で割り当て直す。
     *
     * 被写体ではなく背景の深度を拾っていたときの逃げ道。撮り直しではないので、
     * それまでに撮ったフレームは失わない。
     */
    fun retake(point: FloatArray) {
        center = point.copyOf()
        locked = true
        covered.fill(false)
        for (p in capturedFrom) cellOf(p).let { if (it != NO_CELL) covered[it] = true }
        current = NO_CELL
    }

    /** 中心の推定をやり直せる状態に戻す。深度が取れない端末向けの初期化にも使う。 */
    fun reset() {
        center = null
        locked = false
        current = NO_CELL
        covered.fill(false)
        capturedFrom.clear()
    }

    /** 1 枚撮った。カメラ位置からセルを埋める。 */
    fun addCapture(cameraPosition: FloatArray) {
        capturedFrom += cameraPosition.copyOf()
        lock()
        val cell = cellOf(cameraPosition)
        if (cell != NO_CELL) covered[cell] = true
    }

    /**
     * いま向いているセルを更新する。
     *
     * @return セルが変わったら true。変わらない限り UI へ渡さないので、
     *   毎フレーム呼んでも UI スレッドには何も流れない。
     */
    fun updateCurrent(cameraPosition: FloatArray): Boolean {
        val cell = cellOf(cameraPosition)
        if (cell == current) return false
        current = cell
        return true
    }

    /** UI スレッドへ渡す不変コピー。 */
    fun snapshot(): CoverageSnapshot = CoverageSnapshot(
        covered = covered.copyOf(),
        current = current,
        hasCenter = center != null,
        locked = locked,
        capturedCount = capturedFrom.size,
    )

    /**
     * カメラ位置がどのセルに入るか。
     *
     * 方位角は world の X-Z 平面、仰角は world_up (+Y) からの角度。
     * どちらも transforms.json が宣言している規約（`world_up`, `handedness`）に
     * そのまま乗っている。
     */
    private fun cellOf(cameraPosition: FloatArray): Int {
        val c = center ?: return NO_CELL
        val dx = cameraPosition[0] - c[0]
        val dy = cameraPosition[1] - c[1]
        val dz = cameraPosition[2] - c[2]
        val n = sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
        // 被写体に寄りすぎていると方向が定まらない。ここで弾かないと
        // わずかな手ぶれでセルが飛び回る。
        if (!n.isFinite() || n < MIN_RADIUS_M) return NO_CELL

        val elevation = Math.toDegrees(asin((dy / n).coerceIn(-1f, 1f).toDouble())).toFloat()
        var band = 0
        while (band + 1 < BAND_COUNT && elevation >= BAND_LOWER_DEG[band + 1]) band++

        var a = atan2(dx.toDouble(), dz.toDouble())
        if (a < 0.0) a += TWO_PI
        val az = ((a / TWO_PI) * AZIMUTH_BINS).toInt().coerceIn(0, AZIMUTH_BINS - 1)

        return band * AZIMUTH_BINS + az
    }

    companion object {
        /** 方位角の分割数。周回して撮るとき 30 度ごとが目安になる。 */
        const val AZIMUTH_BINS = 12

        /** 水平とみなす仰角の幅 [度]。これを越えたら見上げ／見下ろしとして別の帯にする。 */
        const val BAND_EDGE_DEG = 20f

        /**
         * 仰角の帯の下限 [度]。被写体から見たカメラの高さで切る。
         *
         * band 0 = 見上げ（カメラが下）/ band 1 = 水平 / band 2 = 見下ろし（カメラが上）。
         *
         * **水平を中心に対称に切ること。** 以前は `[-90, 15, 40]` としていたが、
         * これだと band 0 が 105 度ぶんを飲み込む。立って手持ちで撮ると仰角は
         * ほぼそこに収まるので、外周しか埋まらず上下の区別が付かなかった。
         *
         * 境界の 20 度は、1 m 先の被写体に対してカメラを 36 cm 上下させた角度。
         * 意識して動かせば届き、普通に歩き回るだけでは越えない幅として選んだ。
         */
        val BAND_LOWER_DEG = floatArrayOf(-90f, -BAND_EDGE_DEG, BAND_EDGE_DEG)
        val BAND_COUNT = BAND_LOWER_DEG.size

        /** これより近いと方向が定まらないので判定しない。 */
        private const val MIN_RADIUS_M = 0.15f

        private const val CENTER_SMOOTHING = 0.2f
        private const val TWO_PI = 2.0 * PI
    }
}

/** [ViewCoverage] の状態を UI スレッドへ渡すための不変コピー。 */
class CoverageSnapshot(
    val covered: BooleanArray,
    val current: Int,
    val hasCenter: Boolean,
    val locked: Boolean,
    val capturedCount: Int,
) {
    val coveredCount: Int get() = covered.count { it }
    val cellCount: Int get() = covered.size
}
