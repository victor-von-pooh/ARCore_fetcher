package com.example.arcorefetcher.capture

import android.util.Log
import com.google.ar.core.Pose
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * 1 撮影セッション = 1 ディレクトリ = 1 transforms.json を書き出す。
 *
 * JPEG エンコードとディスク書き込みは専用ワーカースレッドで行い、
 * 描画ループ (GL スレッド) を止めない。GL スレッドからは [submitFrame] と
 * [finish] だけを呼ぶ。
 */
class CaptureSessionWriter(
    outputRoot: File,
    private val meta: CaptureMeta,
) {
    /** 出力レイアウト: capture_<stamp>/{transforms.json, images/} */
    val sessionDir: File = File(outputRoot, "capture_${meta.sessionId.dirStamp()}")
    private val imagesDir = File(sessionDir, IMAGES_DIR)

    private val queue = LinkedBlockingQueue<Task>()
    private val written = ArrayList<WrittenFrame>()

    /** UI 表示用。描画スレッドから読むので atomic にしておく。 */
    val submittedCount = AtomicInteger(0)
    val trackingCount = AtomicInteger(0)

    /** セッション中 intrinsics は不変なのでトップレベルに置く。 */
    private var baseIntrinsics: Intrinsics? = null

    @Volatile
    private var closed = false

    private val worker = Thread({ runLoop() }, "capture-writer").apply {
        priority = Thread.NORM_PRIORITY - 1
    }

    init {
        if (!imagesDir.exists() && !imagesDir.mkdirs()) {
            throw IllegalStateException("出力先を作成できません: $imagesDir")
        }
        worker.start()
    }

    /**
     * GL スレッドから 1 フレームを預ける。呼び出しはブロックしない。
     *
     * TRACKING 以外のフレームも捨てずに記録する。品質による選別は
     * 撮影後に基準を変えられるべきなので、撮影側では判断しない。
     */
    fun submitFrame(frame: PendingFrame) {
        if (closed) return
        submittedCount.incrementAndGet()
        if (frame.trackingState == "TRACKING") trackingCount.incrementAndGet()
        queue.put(Task.Write(frame))
    }

    /**
     * 書き出しを締める。
     *
     * @param refreshedPoses セッション終了直前に各フレームの Anchor から読み直した pose。
     *   添字は [PendingFrame.anchorIndex]。読み直せなかった要素は null。
     * @param onDone ワーカースレッドから呼ばれる。成功なら ZIP、失敗なら例外。
     */
    fun finish(refreshedPoses: List<Pose?>, onDone: (Result<File>) -> Unit) {
        if (closed) return
        closed = true
        queue.put(Task.Finish(refreshedPoses, onDone))
    }

    private fun runLoop() {
        while (true) {
            when (val task = queue.take()) {
                is Task.Write -> runCatching { writeFrame(task.frame) }
                    .onFailure { Log.e(TAG, "フレームの書き出しに失敗", it) }
                is Task.Finish -> {
                    task.onDone(runCatching { finalizeSession(task.refreshedPoses) })
                    return
                }
            }
        }
    }

    private fun writeFrame(frame: PendingFrame) {
        if (baseIntrinsics == null) baseIntrinsics = frame.intrinsics

        // ファイル名は timestamp_ns の 18 桁ゼロ埋め。
        val name = frame.timestampNs.toString().padStart(CaptureSpec.FILENAME_PAD, '0') + ".jpg"
        val file = File(imagesDir, name)
        FileOutputStream(file).buffered().use { out ->
            YuvJpeg.compressToJpeg(frame.nv21, frame.imageWidth, frame.imageHeight, out)
        }

        // セッション途中で intrinsics が変わった場合だけフレーム側で上書きする。
        val override = frame.intrinsicsOverride
            ?: frame.intrinsics.takeIf { it != baseIntrinsics }

        written += WrittenFrame(
            filePath = "$IMAGES_DIR/$name",
            timestampNs = frame.timestampNs,
            anchorIndex = frame.anchorIndex,
            poseAtCapture = frame.poseAtCapture,
            trackingState = frame.trackingState,
            trackingFailureReason = frame.trackingFailureReason,
            intrinsicsOverride = override,
            trackingElapsedNs = frame.trackingElapsedNs,
            pointCount = frame.pointCount,
            exposureNs = frame.exposureNs,
            iso = frame.iso,
        )
    }

    private fun finalizeSession(refreshedPoses: List<Pose?>): File {
        if (written.isEmpty()) throw IllegalStateException("フレームが 1 枚もありません")

        // frames は timestamp_ns 昇順で並べる。
        written.sortBy { it.timestampNs }

        val intrinsics = baseIntrinsics
            ?: throw IllegalStateException("intrinsics が確定していません")

        // 1 枚でも撮影時点の姿勢のまま残ったなら、補正済みと言い切れないので false。
        val allRefreshed = written.all { refreshedPoses.getOrNull(it.anchorIndex) != null }
        if (!allRefreshed) {
            Log.w(TAG, "Anchor から読み直せなかったフレームがあります")
        }
        val effectiveMeta = meta.copy(originRefreshedAtEnd = allRefreshed)

        val json = TransformsJson.build(effectiveMeta, intrinsics, written, refreshedPoses)
        File(sessionDir, "transforms.json").writeText(json, Charsets.UTF_8)

        return Zip.zipDirectory(sessionDir, File(sessionDir.parentFile, "${sessionDir.name}.zip"))
    }

    private sealed class Task {
        class Write(val frame: PendingFrame) : Task()
        class Finish(val refreshedPoses: List<Pose?>, val onDone: (Result<File>) -> Unit) : Task()
    }

    companion object {
        private const val TAG = "CaptureSessionWriter"
        private const val IMAGES_DIR = "images"

        /** session_id `20260920T103104+0900` からディレクトリ名用の `20260920T103104` を取る。 */
        private fun String.dirStamp(): String =
            takeWhile { it.isDigit() || it == 'T' }.ifEmpty { this }
    }
}
