package com.example.arcorefetcher.pointcloud

import com.example.arcorefetcher.capture.CaptureSpec
import com.example.arcorefetcher.capture.ConfidenceMap
import com.example.arcorefetcher.capture.DepthMap
import com.example.arcorefetcher.capture.Intrinsics

/**
 * 深度画像をカメラ座標の点に戻し、CPU 画像から色を取る。
 *
 * 深度は GPU テクスチャの画角、CPU 画像は別の画角でありうる
 * （README「深度は JPEG と画角が違う」）。そこで画素同士を対応させず、
 * **深度の intrinsics で 3 次元に戻してから、JPEG の intrinsics で投影し直す**。
 * 姿勢は共通なので、画角が違う端末でもこれで正しく対応する。
 * JPEG の外に落ちた点は色が無いので捨てる。
 */
object DepthUnprojector {

    fun unproject(
        depth: DepthMap,
        confidence: ConfidenceMap,
        depthIntrinsics: Intrinsics,
        nv21: ByteArray,
        imageIntrinsics: Intrinsics,
        minConfidence: Int,
        stride: Int,
    ): CameraPoints {
        require(confidence.width == depth.width && confidence.height == depth.height) {
            "深度と信頼度の解像度が違います: ${depth.width}x${depth.height} / " +
                "${confidence.width}x${confidence.height}"
        }
        val w = depth.width
        val h = depth.height
        val maxPoints = ((w + stride - 1) / stride) * ((h + stride - 1) / stride)
        val xyz = FloatArray(maxPoints * 3)
        val rgb = ByteArray(maxPoints * 3)

        val dk = depthIntrinsics
        val ik = imageIntrinsics
        val imageW = ik.w
        val imageH = ik.h
        // NV21 は Y 平面のあとに V,U が交互に並ぶ。色差は縦横とも半分の解像度。
        val chromaBase = imageW * imageH
        val chromaW = imageW / 2
        val chromaH = imageH / 2
        if (chromaW == 0 || chromaH == 0 || nv21.size < chromaBase + chromaW * chromaH * 2) {
            throw IllegalArgumentException("NV21 の長さが解像度と合いません: ${nv21.size} / ${imageW}x$imageH")
        }

        var n = 0
        for (v in 0 until h step stride) {
            for (u in 0 until w step stride) {
                val i = v * w + u
                val mm = depth.millimeters[i].toInt() and 0xFFFF
                if (mm == CaptureSpec.DEPTH_INVALID_VALUE) continue
                if ((confidence.values[i].toInt() and 0xFF) < minConfidence) continue

                // README の逆投影の式。画像の v は下向きで camera は +Y up、
                // 深度は正の距離で camera は -Z を向く。符号を落とすと点群が裏返る。
                val t = mm / 1000f
                val x = (u - dk.cx) * t / dk.flX
                val y = -(v - dk.cy) * t / dk.flY

                // 同じ点を JPEG 側のカメラへ投影する（-z = t）。
                val pu = ik.flX * x / t + ik.cx
                val pv = ik.cy - ik.flY * y / t
                if (pu < -0.5f || pv < -0.5f) continue
                val iu = (pu + 0.5f).toInt()
                val iv = (pv + 0.5f).toInt()
                if (iu >= imageW || iv >= imageH) continue

                val yy = nv21[iv * imageW + iu].toInt() and 0xFF
                val c = chromaBase +
                    (iv / 2).coerceAtMost(chromaH - 1) * chromaW * 2 +
                    (iu / 2).coerceAtMost(chromaW - 1) * 2
                val vv = (nv21[c].toInt() and 0xFF) - 128
                val uu = (nv21[c + 1].toInt() and 0xFF) - 128

                xyz[n * 3] = x
                xyz[n * 3 + 1] = y
                xyz[n * 3 + 2] = -t
                // カメラの YUV はフルレンジの BT.601（JFIF と同じ）。
                rgb[n * 3] = clamp(yy + 1.402f * vv)
                rgb[n * 3 + 1] = clamp(yy - 0.344136f * uu - 0.714136f * vv)
                rgb[n * 3 + 2] = clamp(yy + 1.772f * uu)
                n++
            }
        }
        return CameraPoints(n, xyz.copyOf(n * 3), rgb.copyOf(n * 3))
    }

    private fun clamp(v: Float): Byte = v.toInt().coerceIn(0, 255).toByte()
}
