package com.example.arcorefetcher.capture

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * `Frame.acquireCameraImage()` の YUV_420_888 を JPEG にする。
 *
 * [toNv21] は GL スレッドで呼び、直後に [Image] を close すること。
 * ARCore の Image はプロセス共有バッファで、握ったままにすると
 * カメラパイプラインが止まる。
 *
 * 画像は **回転させない**。intrinsics は `getImageIntrinsics()` の
 * 未回転のセンサ座標で報告されるので、回転すると intrinsics と画像が食い違う。
 *
 * EXIF は書かない。YUV→JPEG 変換の実装によって EXIF は容易に欠落するため、
 * 姿勢・内部パラメータ・タイムスタンプを EXIF で伝えてはいけない。
 * それらの唯一の正は transforms.json。
 */
object YuvJpeg {

    const val DEFAULT_QUALITY = 95

    /** YUV_420_888 を NV21 (Y 平面 + V,U インターリーブ) にコピーする。 */
    fun toNv21(image: Image): ByteArray {
        require(image.format == ImageFormat.YUV_420_888) {
            "想定外の画像フォーマット: ${image.format}"
        }
        val width = image.width
        val height = image.height
        val ySize = width * height
        val chromaW = width / 2
        val chromaH = height / 2
        val out = ByteArray(ySize + chromaW * chromaH * 2)

        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]

        copyPlane(y.buffer, y.rowStride, y.pixelStride, width, height, out, 0, 1)
        // NV21 の色差は V が先、U が後。
        copyPlane(v.buffer, v.rowStride, v.pixelStride, chromaW, chromaH, out, ySize, 2)
        copyPlane(u.buffer, u.rowStride, u.pixelStride, chromaW, chromaH, out, ySize + 1, 2)

        return out
    }

    fun compressToJpeg(
        nv21: ByteArray,
        width: Int,
        height: Int,
        stream: OutputStream,
        quality: Int = DEFAULT_QUALITY,
    ) {
        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        if (!yuv.compressToJpeg(Rect(0, 0, width, height), quality, stream)) {
            throw IllegalStateException("JPEG エンコードに失敗しました (${width}x${height})")
        }
    }

    /**
     * rowStride / pixelStride を踏んで 1 平面を取り出す。
     *
     * 端末によっては rowStride > width、pixelStride == 2 （UV インターリーブ済み）
     * で返ってくるため、素朴な一括コピーはできない。
     */
    private fun copyPlane(
        src: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        out: ByteArray,
        outOffset: Int,
        outPixelStride: Int,
    ) {
        val buf = src.duplicate()
        var dst = outOffset

        if (pixelStride == 1 && outPixelStride == 1 && rowStride == width) {
            buf.position(0)
            buf.get(out, dst, minOf(width * height, buf.remaining()))
            return
        }

        val rowLen = (width - 1) * pixelStride + 1
        val row = ByteArray(rowLen)
        for (r in 0 until height) {
            val start = r * rowStride
            if (start >= buf.limit()) break
            buf.position(start)
            val n = minOf(rowLen, buf.remaining())
            buf.get(row, 0, n)
            var srcIndex = 0
            var c = 0
            while (c < width && srcIndex < n) {
                out[dst] = row[srcIndex]
                srcIndex += pixelStride
                dst += outPixelStride
                c++
            }
            // 読めなかった分は 0 のまま詰める（行が途中で切れる端末対策）。
            dst += (width - c) * outPixelStride
        }
    }
}
