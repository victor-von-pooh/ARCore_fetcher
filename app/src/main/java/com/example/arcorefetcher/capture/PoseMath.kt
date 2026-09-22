package com.example.arcorefetcher.capture

import com.google.ar.core.Pose
import kotlin.math.sqrt

/**
 * ARCore の [Pose] を書き出し用の表現へ変換する。
 *
 * ここは転置とクォータニオン順序という取り違えやすい変換が集まっている箇所。
 * 出力は transform_matrix と translation + quaternion_xyzw の二重表現になるので、
 * 変更したら両者が同じ姿勢を指したままか必ず確かめること。
 */
object PoseMath {

    /**
     * camera-to-world の 4x4 を **行優先 (row-major)** で返す。
     *
     * [Pose.toMatrix] が返すのは OpenGL 慣習の **列優先 (column-major)** で、
     * 添字は `m[col * 4 + row]`。JSON には行優先で入れる必要があるため、
     * ここで `out[row][col] = m[col * 4 + row]` と読み替える。
     */
    fun toRowMajorMatrix(pose: Pose): Array<FloatArray> {
        val m = FloatArray(16)
        pose.toMatrix(m, 0)
        return Array(4) { row -> FloatArray(4) { col -> m[col * 4 + row] } }
    }

    /**
     * 回転を **(x, y, z, w) 順**で返す。ARCore が返す順序そのまま。
     *
     * (w, x, y, z) を要求する規約もあるが、ここで並べ替えてはいけない。
     * 順序変換は読み手側で行う。
     */
    fun quaternionXyzw(pose: Pose): FloatArray {
        val q = FloatArray(4)
        pose.getRotationQuaternion(q, 0)
        return normalized(q)
    }

    /** 平行移動をメートルで返す。 */
    fun translation(pose: Pose): FloatArray {
        val t = FloatArray(3)
        pose.getTranslation(t, 0)
        return t
    }

    /** pose の合成を重ねた結果の丸め誤差を落として、単位クォータニオンを保つ。 */
    private fun normalized(q: FloatArray): FloatArray {
        val n = sqrt((q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]).toDouble())
        if (n <= 0.0 || !n.isFinite()) return floatArrayOf(0f, 0f, 0f, 1f)
        return FloatArray(4) { (q[it] / n).toFloat() }
    }

    /**
     * camera 座標の点を world 座標へ移す。
     *
     * transforms.json が宣言している規約（camera-to-world の行優先行列）を
     * そのまま使う。深度から被写体の位置を出す経路もここを通るので、
     * 宣言と実際の計算がずれていれば画面上で破綻して気づける。
     */
    fun transformToWorld(pose: Pose, x: Float, y: Float, z: Float): FloatArray {
        val m = toRowMajorMatrix(pose)
        return floatArrayOf(
            m[0][0] * x + m[0][1] * y + m[0][2] * z + m[0][3],
            m[1][0] * x + m[1][1] * y + m[1][2] * z + m[1][3],
            m[2][0] * x + m[2][1] * y + m[2][2] * z + m[2][3],
        )
    }

    /** 2 つの pose の平行移動成分の距離 [m]。ウォームアップ判定の移動量に使う。 */
    fun distance(a: Pose, b: Pose): Float {
        val dx = a.tx() - b.tx()
        val dy = a.ty() - b.ty()
        val dz = a.tz() - b.tz()
        return sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
    }
}
