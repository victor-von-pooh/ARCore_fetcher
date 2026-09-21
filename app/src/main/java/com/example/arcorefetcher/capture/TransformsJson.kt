package com.example.arcorefetcher.capture

import com.google.ar.core.Pose
import java.util.Locale

/**
 * transforms.json のシリアライザ。
 *
 * 依存を増やさないため手書きする。キー順は固定してある
 * （JSON 的には無意味だが、差分を読むときに効く）。
 */
object TransformsJson {

    /**
     * @param frames JPEG 書き出し済みのフレーム。world 姿勢はまだ確定していない。
     * @param refreshedPoses セッション終了直前に各フレームの Anchor から読み直した pose。
     *   添字は [WrittenFrame.anchorIndex]。ここに入っているのが ARCore の遡及補正を
     *   反映した world 姿勢で、これが本来の出力値。読み直せなかったフレームだけ
     *   [WrittenFrame.poseAtCapture] へ退避する。
     */
    fun build(
        meta: CaptureMeta,
        intrinsics: Intrinsics,
        depthIntrinsics: Intrinsics?,
        frames: List<WrittenFrame>,
        refreshedPoses: List<Pose?>,
    ): String {
        val sb = StringBuilder(1024 + frames.size * 512)
        sb.append("{\n")
        sb.append("  ").append(str("spec_version")).append(": ")
            .append(str(CaptureSpec.SPEC_VERSION)).append(",\n\n")

        sb.append("  ").append(str("camera_model")).append(": ")
            .append(str(CaptureSpec.CAMERA_MODEL)).append(",\n")
        appendIntrinsics(sb, intrinsics, indent = "  ")
        sb.append("\n")

        // 深度が 1 枚も取れなかったセッションではブロックごと省く。
        // 出ているときは必ず全キーそろう（読み手が分岐を持たなくて済む）。
        depthIntrinsics?.let {
            appendDepth(sb, it)
            sb.append("\n")
        }

        sb.append("  ").append(str("coordinate_convention")).append(": {\n")
        sb.append("    ").append(str("handedness")).append(": ")
            .append(str(CaptureSpec.HANDEDNESS)).append(",\n")
        sb.append("    ").append(str("camera_axes")).append(": ")
            .append(str(CaptureSpec.CAMERA_AXES)).append(",\n")
        sb.append("    ").append(str("matrix_layout")).append(": ")
            .append(str(CaptureSpec.MATRIX_LAYOUT)).append(",\n")
        sb.append("    ").append(str("quaternion_order")).append(": ")
            .append(str(CaptureSpec.QUATERNION_ORDER)).append(",\n")
        sb.append("    ").append(str("transform_direction")).append(": ")
            .append(str(CaptureSpec.TRANSFORM_DIRECTION)).append(",\n")
        sb.append("    ").append(str("world_up")).append(": ")
            .append(floatArray(CaptureSpec.WORLD_UP)).append(",\n")
        sb.append("    ").append(str("length_unit")).append(": ")
            .append(str(CaptureSpec.LENGTH_UNIT)).append("\n")
        sb.append("  },\n\n")

        sb.append("  ").append(str("capture")).append(": {\n")
        sb.append("    ").append(str("session_id")).append(": ")
            .append(str(meta.sessionId)).append(",\n")
        sb.append("    ").append(str("device_model")).append(": ")
            .append(str(meta.deviceModel)).append(",\n")
        sb.append("    ").append(str("arcore_version")).append(": ")
            .append(str(meta.arcoreVersion)).append(",\n")
        sb.append("    ").append(str("capture_mode")).append(": ")
            .append(str(meta.captureMode)).append(",\n")
        sb.append("    ").append(str("world_origin")).append(": ")
            .append(str(meta.worldOrigin)).append(",\n")
        sb.append("    ").append(str("origin_refreshed_at_end")).append(": ")
            .append(meta.originRefreshedAtEnd).append(",\n")
        // 深度が 1 枚も無いとき、端末が非対応だったのか取得に失敗し続けたのかは
        // これでしか分からない。トップレベルの depth ブロックとは役割が違う。
        sb.append("    ").append(str("depth_mode")).append(": ")
            .append(str(CaptureSpec.depthModeOf(meta.depthMode))).append("\n")
        sb.append("  },\n\n")

        sb.append("  ").append(str("frames")).append(": [\n")
        frames.forEachIndexed { i, frame ->
            appendFrame(sb, frame, refreshedPoses)
            sb.append(if (i == frames.lastIndex) "\n" else ",\n")
        }
        sb.append("  ]\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun appendIntrinsics(sb: StringBuilder, it: Intrinsics, indent: String) {
        sb.append(indent).append(str("fl_x")).append(": ").append(num(it.flX)).append(",\n")
        sb.append(indent).append(str("fl_y")).append(": ").append(num(it.flY)).append(",\n")
        sb.append(indent).append(str("cx")).append(": ").append(num(it.cx)).append(",\n")
        sb.append(indent).append(str("cy")).append(": ").append(num(it.cy)).append(",\n")
        sb.append(indent).append(str("w")).append(": ").append(it.w).append(",\n")
        sb.append(indent).append(str("h")).append(": ").append(it.h).append(",\n")
    }

    /**
     * 深度画像側の内部パラメータと画素値の意味。
     *
     * **トップレベルの intrinsics とは別のカメラ**として書く。ARCore の深度は
     * GPU テクスチャの画角に揃っており、保存した JPEG とは画角が違いうるので、
     * JPEG 用の fl_x / cx で深度を逆投影すると黙って歪む
     * （[CaptureSpec.DEPTH_ALIGNED_TO]）。姿勢は画像と共通で、フレームの
     * transform_matrix がそのまま深度カメラの姿勢でもある。
     */
    private fun appendDepth(sb: StringBuilder, it: Intrinsics) {
        sb.append("  ").append(str("depth")).append(": {\n")
        sb.append("    ").append(str("aligned_to")).append(": ")
            .append(str(CaptureSpec.DEPTH_ALIGNED_TO)).append(",\n")
        sb.append("    ").append(str("depth_format")).append(": ")
            .append(str(CaptureSpec.DEPTH_FORMAT)).append(",\n")
        sb.append("    ").append(str("confidence_format")).append(": ")
            .append(str(CaptureSpec.CONFIDENCE_FORMAT)).append(",\n")
        sb.append("    ").append(str("invalid_value")).append(": ")
            .append(CaptureSpec.DEPTH_INVALID_VALUE).append(",\n")
        appendIntrinsics(sb, it, indent = "    ")
        // appendIntrinsics は末尾にカンマを置くので、最後のキーをここで閉じる。
        sb.setLength(sb.length - 2)
        sb.append("\n  },\n")
    }

    private fun appendFrame(sb: StringBuilder, frame: WrittenFrame, refreshedPoses: List<Pose?>) {
        // フレーム専用 Anchor から読み直した pose がそのまま world 姿勢。
        // 読み直せなかったフレームだけ撮影時点の姿勢で埋める。
        val refreshed = refreshedPoses.getOrNull(frame.anchorIndex)
        val world = refreshed ?: frame.poseAtCapture
        val m = PoseMath.toRowMajorMatrix(world)
        val t = PoseMath.translation(world)
        val q = PoseMath.quaternionXyzw(world)

        sb.append("    {\n")
        sb.append("      ").append(str("file_path")).append(": ")
            .append(str(frame.filePath)).append(",\n")
        sb.append("      ").append(str("timestamp_ns")).append(": ")
            .append(frame.timestampNs).append(",\n\n")

        sb.append("      ").append(str("transform_matrix")).append(": [\n")
        for (row in 0..3) {
            sb.append("        ").append(floatArray(m[row]))
            sb.append(if (row == 3) "\n" else ",\n")
        }
        sb.append("      ],\n\n")

        sb.append("      ").append(str("translation")).append(": ")
            .append(floatArray(t)).append(",\n")
        sb.append("      ").append(str("quaternion_xyzw")).append(": ")
            .append(floatArray(q)).append(",\n\n")

        sb.append("      ").append(str("tracking_state")).append(": ")
            .append(str(frame.trackingState)).append(",\n")
        sb.append("      ").append(str("tracking_failure_reason")).append(": ")
            .append(str(frame.trackingFailureReason)).append(",\n")

        // 終了時に Anchor から姿勢を読み直せたか。
        // false のフレームは撮影時点の姿勢のままなので、ARCore がその後に行った
        // 座標の測り直しが反映されていない。true のフレームとは座標系が食い違いうる。
        // セッション単位の origin_refreshed_at_end だけでは、どれがそれか分からない。
        sb.append("      ").append(str("pose_refreshed")).append(": ").append(refreshed != null)

        // VIO は収束しきる前でも TRACKING を報告する。下流が初期化区間のフレームを
        // 見分けられるように、収束の手がかりを省略可能な項目として残す。
        frame.trackingElapsedNs?.let {
            sb.append(",\n      ").append(str("tracking_elapsed_ns")).append(": ").append(it)
        }
        frame.pointCount?.let {
            sb.append(",\n      ").append(str("point_count")).append(": ").append(it)
        }

        // 深度は取れないフレームが普通に混ざる。取れた種類だけ書き、
        // 無いフレームはキーごと省く（値が無いのに空文字を置かない）。
        frame.depthPath?.let {
            sb.append(",\n      ").append(str("depth_file_path")).append(": ").append(str(it))
        }
        frame.rawDepthPath?.let {
            sb.append(",\n      ").append(str("raw_depth_file_path")).append(": ").append(str(it))
        }
        frame.confidencePath?.let {
            sb.append(",\n      ").append(str("confidence_file_path")).append(": ").append(str(it))
        }

        frame.intrinsicsOverride?.let {
            sb.append(",\n\n")
            appendIntrinsicsOverride(sb, it)
        }
        frame.exposureNs?.let {
            sb.append(",\n      ").append(str("exposure_ns")).append(": ").append(it)
        }
        frame.iso?.let {
            sb.append(",\n      ").append(str("iso")).append(": ").append(it)
        }

        sb.append("\n    }")
    }

    private fun appendIntrinsicsOverride(sb: StringBuilder, it: Intrinsics) {
        // フレームで上書きするなら 6 キー全部そろえる（部分的な上書きは認めない）。
        sb.append("      ").append(str("fl_x")).append(": ").append(num(it.flX)).append(",\n")
        sb.append("      ").append(str("fl_y")).append(": ").append(num(it.flY)).append(",\n")
        sb.append("      ").append(str("cx")).append(": ").append(num(it.cx)).append(",\n")
        sb.append("      ").append(str("cy")).append(": ").append(num(it.cy)).append(",\n")
        sb.append("      ").append(str("w")).append(": ").append(it.w).append(",\n")
        sb.append("      ").append(str("h")).append(": ").append(it.h)
    }

    private fun floatArray(v: FloatArray): String =
        v.joinToString(prefix = "[", postfix = "]", separator = ", ") { num(it) }

    /**
     * 指数表記を避けた固定小数で出す。
     *
     * transform_matrix の平行移動成分と translation は同じ関数で整形するので、
     * バリデータの一致チェック (TRANS_TOL = 1e-5) は桁落ちの影響を受けない。
     */
    private fun num(v: Float): String {
        if (!v.isFinite()) return "0.0"
        return String.format(Locale.US, "%.9f", v)
    }

    private fun str(v: String): String {
        val sb = StringBuilder(v.length + 2)
        sb.append('"')
        for (c in v) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format(Locale.US, "\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
