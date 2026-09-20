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
     * @param frames JPEG 書き出し済みのフレーム。pose は **anchor 相対**。
     * @param rootAnchorPose セッション終了直前に読み直した root Anchor の pose。
     *   これを掛け戻すことで、ドリフト補正後の world 座標系での姿勢になる。
     *   anchor を張れないまま終わった場合は null（= 恒等変換）。
     */
    fun build(
        meta: CaptureMeta,
        intrinsics: Intrinsics,
        frames: List<WrittenFrame>,
        rootAnchorPose: Pose?,
    ): String {
        val sb = StringBuilder(1024 + frames.size * 512)
        sb.append("{\n")
        sb.append("  ").append(str("spec_version")).append(": ")
            .append(str(CaptureSpec.SPEC_VERSION)).append(",\n\n")

        sb.append("  ").append(str("camera_model")).append(": ")
            .append(str(CaptureSpec.CAMERA_MODEL)).append(",\n")
        appendIntrinsics(sb, intrinsics, indent = "  ")
        sb.append("\n")

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
            .append(meta.originRefreshedAtEnd).append("\n")
        sb.append("  },\n\n")

        sb.append("  ").append(str("frames")).append(": [\n")
        frames.forEachIndexed { i, frame ->
            appendFrame(sb, frame, rootAnchorPose)
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

    private fun appendFrame(sb: StringBuilder, frame: WrittenFrame, rootAnchorPose: Pose?) {
        // 終了直前に読み直した anchor pose を掛け戻して world へ戻す。
        val world = if (rootAnchorPose == null) {
            frame.poseRelativeToAnchor
        } else {
            rootAnchorPose.compose(frame.poseRelativeToAnchor)
        }
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
            .append(str(frame.trackingFailureReason))

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
