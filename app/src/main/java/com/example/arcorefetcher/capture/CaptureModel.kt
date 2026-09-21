package com.example.arcorefetcher.capture

import com.google.ar.core.Pose
import java.io.File

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
     * `tracking_elapsed_ns` / `point_count` / `depth`・`depth_file_path` 一式は
     * **省略可能な追加項目**なので、これらを足しても既存の読み手は壊れない。
     * よって識別子は据え置く。
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

    /**
     * 深度画像が揃っている座標系。
     *
     * ARCore の深度は **GPU テクスチャ側**の画角に揃っており、`acquireCameraImage()`
     * で保存している CPU 画像とは画角が違いうる（端末により CPU 640x480 / テクスチャ
     * 1920x1080 など）。よって深度画素は JPEG 画素と 1 対 1 に対応しない。
     * 公式サンプルが深度の逆投影に `getTextureIntrinsics()` を使うのがその根拠。
     *
     * トップレベルの intrinsics（JPEG 用）で深度を逆投影すると黙って歪むので、
     * 深度側の intrinsics を別に書き出し、この宣言で取り違えを防ぐ。
     */
    const val DEPTH_ALIGNED_TO = "gpu_texture"

    /** 深度 PNG の画素値の意味。16bit グレースケール、単位はミリメートル。 */
    const val DEPTH_FORMAT = "png16_millimeter"

    /** 信頼度 PNG の画素値の意味。8bit グレースケール、0 が最低で 255 が最高。 */
    const val CONFIDENCE_FORMAT = "png8_uint8"

    /** 深度が取れなかった画素の値。raw 側は穴が多く、平滑側でも端では出る。 */
    const val DEPTH_INVALID_VALUE = 0

    /** depth_mode の許容値。端末が非対応なら DISABLED。 */
    val DEPTH_MODES = setOf("DISABLED", "AUTOMATIC", "RAW_DEPTH_ONLY")

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

    fun depthModeOf(name: String): String =
        if (name in DEPTH_MODES) name else "DISABLED"
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

/**
 * 深度画像 1 枚。画素値は mm で、0 は「深度なし」。
 *
 * ARCore は 16bit をまるごと mm として報告するので、符号なし 16bit として読むこと
 * （[ShortArray] に入れているのは領域の都合で、値としては 0-65535）。
 */
class DepthMap(
    val width: Int,
    val height: Int,
    val millimeters: ShortArray,
)

/** 信頼度画像 1 枚。画素値は 0-255 で、raw 深度の同じ画素に対応する。 */
class ConfidenceMap(
    val width: Int,
    val height: Int,
    val values: ByteArray,
)

/**
 * 1 フレーム分の深度一式。取れなかった種類は null。
 *
 * **取れなくてもフレームは捨てない。** 深度は平滑側・raw 側とも
 * `NotYetAvailableException` で普通に欠けるが、画像と姿勢は有効なので、
 * 深度の有無でフレームを選別してはいけない。
 *
 * [depth] は平滑・穴埋め済み（`AUTOMATIC` のときだけ取れる）。
 * [rawDepth] は穴だらけの生値で、[confidence] が同じ画素の確からしさを持つ。
 * どちらを使うか、どの信頼度で足切りするかは下流が決める。
 */
class DepthCapture(
    val depth: DepthMap?,
    val rawDepth: DepthMap?,
    val confidence: ConfidenceMap?,
    /**
     * 深度画像の解像度に対応した内部パラメータ。
     *
     * `getTextureIntrinsics()` を深度の解像度へスケールしたもの。
     * **JPEG 用の intrinsics とは別物**（[CaptureSpec.DEPTH_ALIGNED_TO]）。
     */
    val intrinsics: Intrinsics,
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
    /**
     * 実際に有効化できた `Config.DepthMode`。端末が非対応なら "DISABLED"。
     *
     * 深度が 1 枚も入っていないセッションについて、「端末が非対応だった」のか
     * 「対応しているが取得に失敗し続けた」のかは、これでしか区別できない。
     */
    val depthMode: String = "DISABLED",
)

/**
 * 書き出しの結果。
 *
 * [unrefreshedCount] が 0 でないセッションは、**補正後の座標系と補正前の座標系が
 * 1 つのファイルに混ざっている**。どのフレームがどちらかは transforms.json の
 * `pose_refreshed` で判別する。
 */
class WriteResult(
    val zip: File,
    val frameCount: Int,
    val unrefreshedCount: Int,
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
    /**
     * このフレームの深度。端末が非対応、または取得できなかったフレームは null。
     *
     * [nv21] と同じく GL スレッドでコピー済みの配列で、ARCore の Image は
     * ここへ載せる時点で close してある。
     */
    val depth: DepthCapture? = null,
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
    /** 書き出した深度 PNG の相対パス。そのフレームで取れなかったものは null。 */
    val depthPath: String?,
    val rawDepthPath: String?,
    val confidencePath: String?,
    val exposureNs: Long?,
    val iso: Int?,
)
