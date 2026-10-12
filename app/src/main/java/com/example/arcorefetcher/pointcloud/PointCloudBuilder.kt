package com.example.arcorefetcher.pointcloud

import android.util.Log
import com.example.arcorefetcher.capture.PendingFrame
import com.google.ar.core.Pose
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * 撮ったフレームの深度から点群を作る。1 撮影セッションに 1 つ。
 *
 * ## 姿勢は後から当てる
 *
 * フレームごとの点は**カメラ座標のまま**持ち、world へは書き出すときに移す。
 * その時点で Anchor から読み直した姿勢（transforms.json に書くのと同じ姿勢）を使うので、
 * 撮影後に ARCore が座標を測り直しても、点群と画像の姿勢が食い違わない。
 * 撮影時点で world に固めると、点群だけがその補正から取り残される。
 *
 * ## スレッド
 *
 * 逆投影とボクセル化は専用のワーカースレッドで行う。GL スレッドからは
 * [add] と [finish] だけを呼ぶ。どちらもブロックしない。
 * 受け取った配列（NV21・深度）は writer と共有するが、どちらも読むだけ。
 */
class PointCloudBuilder {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "point-cloud").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    /** ワーカースレッドからのみ触る。 */
    private val frames = ArrayList<FramePoints>()

    @Volatile
    private var closed = false

    /**
     * 1 フレームを預ける。raw 深度と信頼度がそろっていなければ何もしない。
     *
     * 点が出なくてもフレームは writer 側に残る。点群に使えないことは、
     * そのフレームを捨てる理由にならない。
     */
    fun add(frame: PendingFrame) {
        if (closed) return
        val depth = frame.depth ?: return
        val raw = depth.rawDepth ?: return
        val confidence = depth.confidence ?: return
        executor.execute {
            runCatching {
                DepthUnprojector.unproject(
                    depth = raw,
                    confidence = confidence,
                    depthIntrinsics = depth.intrinsics,
                    nv21 = frame.nv21,
                    imageIntrinsics = frame.intrinsics,
                    minConfidence = PointCloudSpec.MIN_CONFIDENCE,
                    stride = PointCloudSpec.PIXEL_STRIDE,
                )
            }.onSuccess {
                if (it.size > 0) frames += FramePoints(frame.anchorIndex, frame.poseAtCapture, it)
            }.onFailure {
                Log.e(TAG, "深度から点を戻せません", it)
            }
        }
    }

    /**
     * 預かったフレームを最後まで処理してから点群を作る。以後 [add] は無視する。
     *
     * @param refreshedPoses 書き出し直前に Anchor から読み直した姿勢。添字は
     *   [PendingFrame.anchorIndex]。読み直せなかったフレームは撮影時点の姿勢を使う
     *   （transforms.json と同じ規則）。
     * @param subjectCenter 被写体中心（world 座標）。null ならカメラからの距離で切る。
     */
    fun finish(refreshedPoses: List<Pose?>, subjectCenter: FloatArray?): Future<PointCloud> {
        closed = true
        val crop = if (subjectCenter != null) {
            Crop.Sphere(subjectCenter.copyOf(), PointCloudSpec.SUBJECT_RADIUS_M)
        } else {
            Crop.MaxDepth(PointCloudSpec.MAX_DEPTH_M)
        }
        val poses = refreshedPoses.toList()
        val future = executor.submit(Callable { build(poses, crop) })
        executor.shutdown()
        return future
    }

    /** 書き出さずに終わるときの後始末。 */
    fun close() {
        closed = true
        executor.shutdownNow()
    }

    private fun build(refreshedPoses: List<Pose?>, crop: Crop): PointCloud {
        val grid = VoxelGrid(PointCloudSpec.VOXEL_SIZE_M)

        // 点ごとに型を見ないよう、切り出しの条件を数値にしておく。
        val maxDepth = (crop as? Crop.MaxDepth)?.maxDepthM ?: Float.POSITIVE_INFINITY
        val sphere = crop as? Crop.Sphere
        val r2 = sphere?.let { it.radiusM * it.radiusM } ?: Float.POSITIVE_INFINITY
        val cx = sphere?.center?.get(0) ?: 0f
        val cy = sphere?.center?.get(1) ?: 0f
        val cz = sphere?.center?.get(2) ?: 0f

        // Pose.toMatrix は列優先（m[col * 4 + row]）。
        val m = FloatArray(16)
        for (f in frames) {
            val pose = refreshedPoses.getOrNull(f.anchorIndex) ?: f.poseAtCapture
            pose.toMatrix(m, 0)
            val p = f.points
            for (i in 0 until p.size) {
                val x = p.xyz[i * 3]
                val y = p.xyz[i * 3 + 1]
                val z = p.xyz[i * 3 + 2]
                if (-z > maxDepth) continue

                val wx = m[0] * x + m[4] * y + m[8] * z + m[12]
                val wy = m[1] * x + m[5] * y + m[9] * z + m[13]
                val wz = m[2] * x + m[6] * y + m[10] * z + m[14]
                val dx = wx - cx
                val dy = wy - cy
                val dz = wz - cz
                if (dx * dx + dy * dy + dz * dz > r2) continue

                grid.add(
                    wx, wy, wz,
                    p.rgb[i * 3].toInt() and 0xFF,
                    p.rgb[i * 3 + 1].toInt() and 0xFF,
                    p.rgb[i * 3 + 2].toInt() and 0xFF,
                )
            }
        }
        return grid.toPointCloud(crop, frames.size)
    }

    private class FramePoints(
        val anchorIndex: Int,
        val poseAtCapture: Pose,
        val points: CameraPoints,
    )

    private companion object {
        const val TAG = "PointCloudBuilder"
    }
}
