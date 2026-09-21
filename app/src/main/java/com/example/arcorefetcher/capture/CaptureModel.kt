package com.example.arcorefetcher.capture

import com.google.ar.core.Pose

/**
 * 書き出す capture format の固定値。
 *
 * coordinate_convention は読み手が「想定外の規約のデータ」を弾くための宣言なので、
 * ランタイムで動かしてはいけない。ここの値と実際の書き出し内容がずれると、
 * 読み手は気づかないまま誤った座標系で解釈する。
 */
object CaptureSpec {
    /**
     * 出力ファイルに書き込む形式識別子。破壊的変更のとき major を上げる。
     *
     * `tracking_elapsed_ns` / `point_count` は**省略可能な追加項目**なので、
     * これらを足しても既存の読み手は壊れない。よって識別子は据え置く。
     */
    const val SPEC_VERSION = "arcore-fetcher/capture/1.0"
    const val CAMERA_MODEL = "PINHOLE"

    const val HANDEDNESS = "right"
    const val CAMERA_AXES = "OpenGL (+X right, +Y up, -Z forward)"
    const val MATRIX_LAYOUT = "row-major"
    const val QUATERNION_ORDER = "xyzw"
    const val TRANSFORM_DIRECTION = "camera-to-world"
    const val LENGTH_UNIT = "meter"
    val WORLD_UP = floatArrayOf(0f, 1f, 0f)

    /** 画像ファイル名は timestamp_ns の 18 桁ゼロ埋め。連番はフレーム欠損時に破綻するので使わない。 */
    const val FILENAME_PAD = 18

    /** tracking_state の許容値。 */
    val TRACKING_STATES = setOf("TRACKING", "PAUSED", "STOPPED")

    /** tracking_failure_reason の許容値。 */
    val FAILURE_REASONS = setOf(
        "NONE",
        "BAD_STATE",
        "INSUFFICIENT_LIGHT",
        "EXCESSIVE_MOTION",
        "INSUFFICIENT_FEATURES",
        "CAMERA_UNAVAILABLE",
    )

    /**
     * ARCore の enum 名をそのまま書き出すが、将来 ARCore 側に値が増えても
     * 不正な JSON を吐かないように既知の値へ丸める。
     */
    fun trackingStateOf(name: String): String =
        if (name in TRACKING_STATES) name else "STOPPED"

    fun failureReasonOf(name: String): String =
        if (name in FAILURE_REASONS) name else "BAD_STATE"
}

/**
 * カメラの内部パラメータ。
 *
 * **実際に保存した画像の解像度に対応した値**であること。
 * ARCore の報告解像度と保存解像度が違う場合は同率スケールしてから入れる。
 * ここがずれると、画像と intrinsics が食い違ったまま出力される。
 */
data class Intrinsics(
    val flX: Float,
    val flY: Float,
    val cx: Float,
    val cy: Float,
    val w: Int,
    val h: Int,
)

/** 撮影セッション単位のメタ情報。 */
data class CaptureMeta(
    val sessionId: String,
    val deviceModel: String,
    val arcoreVersion: String,
    /** 本アプリは Frame.acquireCameraImage() の YUV を JPEG 化するので cpu_image 固定。 */
    val captureMode: String = "cpu_image",
    /**
     * 各フレームの姿勢はフレーム専用 Anchor として ARCore に預け、
     * 終了直前に読み直してから書き出す。回収した pose は ARCore world 系なので
     * "anchor:root" ではなく "session"。
     */
    val worldOrigin: String = "session",
    /**
     * **全フレーム**の姿勢を終了直前に Anchor から読み直せたか。
     *
     * Anchor を張れなかった・終了時に TRACKING でなかったフレームが 1 枚でもあると
     * false になる。その場合、該当フレームは撮影時点の姿勢のまま出力される。
     */
    val originRefreshedAtEnd: Boolean = true,
)

/** Anchor を張れなかったフレームの [PendingFrame.anchorIndex]。 */
const val NO_ANCHOR = -1

/**
 * GL スレッドが writer スレッドへ渡す 1 フレーム分の荷物。
 *
 * ARCore の [com.google.ar.core.Image] はプロセス共有バッファなので持ち回さない。
 * GL スレッドで NV21 にコピーし、ここに載せてから即 close する。
 */
class PendingFrame(
    val timestampNs: Long,
    val nv21: ByteArray,
    val imageWidth: Int,
    val imageHeight: Int,
    /**
     * このフレーム専用に張った Anchor の番号。[NO_ANCHOR] なら Anchor を張れていない。
     *
     * world 絶対姿勢は撮影時点では確定させない。ARCore はループクローズ・再ローカライズの
     * たびに Anchor の pose を遡及的に補正するので、**書き出し直前に番号で引き直す**。
     */
    val anchorIndex: Int,
    /**
     * 撮影時点の world 姿勢。
     *
     * Anchor から pose を回収できなかったときにだけ使う保険であって、通常は使われない。
     * （Anchor を張れなかった／終了時に Anchor が TRACKING でなかった場合）
     */
    val poseAtCapture: Pose,
    val trackingState: String,
    val trackingFailureReason: String,
    val intrinsics: Intrinsics,
    /**
     * フレーム単位で intrinsics が変わる場合の上書き。
     * shared_camera を入れるときに使う。cpu_image では null。
     */
    val intrinsicsOverride: Intrinsics? = null,
    /**
     * TRACKING が連続し始めてからの経過時間。
     *
     * VIO は収束しきる前でも TRACKING を報告するため、tracking_state だけでは
     * 初期化区間のフレームを下流で見分けられない。その切り分け用。
     */
    val trackingElapsedNs: Long? = null,
    /** 特徴点数。少ないフレームは姿勢推定の信頼度が低い。 */
    val pointCount: Int? = null,
    val exposureNs: Long? = null,
    val iso: Int? = null,
)

/** JPEG を書き終えたフレーム。world 姿勢はまだ確定していない（[anchorIndex] で引く）。 */
class WrittenFrame(
    val filePath: String,
    val timestampNs: Long,
    val anchorIndex: Int,
    val poseAtCapture: Pose,
    val trackingState: String,
    val trackingFailureReason: String,
    val intrinsicsOverride: Intrinsics?,
    val trackingElapsedNs: Long?,
    val pointCount: Int?,
    val exposureNs: Long?,
    val iso: Int?,
)
