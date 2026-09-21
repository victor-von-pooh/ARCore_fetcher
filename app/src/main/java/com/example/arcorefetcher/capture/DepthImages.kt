package com.example.arcorefetcher.capture

import android.media.Image
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ARCore の深度 [Image] を素の配列にコピーする。
 *
 * [YuvJpeg.toNv21] と同じ理由で、GL スレッドでコピーしたら即 [Image] を
 * close すること。ARCore の Image はプロセス共有バッファで、握ったままにすると
 * カメラパイプラインが止まる。
 *
 * **値は解釈しない。** ARCore は 16bit をまるごと mm として報告するので、
 * マスクもクランプも掛けずにそのまま写す。無効画素は 0 で来る。
 */
object DepthImages {

    /** DEPTH16 を mm の [ShortArray] にする。無効画素は 0。 */
    fun toMillimeters(image: Image): DepthMap {
        val width = image.width
        val height = image.height
        val plane = image.planes[0]
        // ARCore の深度バッファはリトルエンディアン。getShort に読ませる前に宣言する。
        val buf = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val out = ShortArray(width * height)
        forEachPixel(buf, plane.rowStride, plane.pixelStride.orDefault(2), width, height, 2) { i, p ->
            out[i] = buf.getShort(p)
        }
        return DepthMap(width, height, out)
    }

    /** Y8 の信頼度を [ByteArray] にする。0 が最低、255 が最高。 */
    fun toConfidence(image: Image): ConfidenceMap {
        val width = image.width
        val height = image.height
        val plane = image.planes[0]
        val buf = plane.buffer.duplicate()
        val out = ByteArray(width * height)
        forEachPixel(buf, plane.rowStride, plane.pixelStride.orDefault(1), width, height, 1) { i, p ->
            out[i] = buf.get(p)
        }
        return ConfidenceMap(width, height, out)
    }

    /**
     * rowStride / pixelStride を踏んで 1 画素ずつ渡す。
     *
     * [YuvJpeg] の平面コピーと同じ事情で、rowStride > width の端末があるため
     * 素朴な一括コピーはできない。バッファが途中で切れた場合、残りは 0 のまま
     * 残す（= 無効画素扱い。深度の 0 は「値なし」なので意味が合う）。
     */
    private inline fun forEachPixel(
        buf: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        bytesPerPixel: Int,
        read: (index: Int, position: Int) -> Unit,
    ) {
        val limit = buf.limit()
        for (r in 0 until height) {
            val base = r * rowStride
            if (base >= limit) return
            for (c in 0 until width) {
                val p = base + c * pixelStride
                if (p + bytesPerPixel > limit) break
                read(r * width + c, p)
            }
        }
    }

    /** pixelStride を 0 で返す実装があるので、フォーマットの既定値に倒す。 */
    private fun Int.orDefault(fallback: Int): Int = if (this > 0) this else fallback
}
