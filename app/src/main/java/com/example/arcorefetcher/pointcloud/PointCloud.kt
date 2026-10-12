package com.example.arcorefetcher.pointcloud

/**
 * 点群の作り方の固定値。transforms.json の `point_cloud` ブロックにそのまま書く。
 *
 * **点群は MVS ではなく ARCore の深度を重ねたもの**で、精度は深度に縛られる
 * （実機でフレーム間の食い違いが約 5 cm）。細部を取るためのものではなく、
 * 「おおよその形と色」を初期点群として渡すためのもの。何に使うかは下流が決める。
 *
 * 作り方を変えたら、ここの値を変えて transforms.json の宣言と実際の中身を揃えること。
 */
object PointCloudSpec {
    /** セッションディレクトリ直下に置く PLY のファイル名。 */
    const val FILE_NAME = "points.ply"

    /** 何から作った点群か。MVS と取り違えられないように明示する。 */
    const val SOURCE = "arcore_depth_fusion"

    /**
     * 使う深度。平滑済み深度は全画素を推定で埋めてくるので、実測でない点が
     * 初期点群に混ざる。raw 深度を信頼度で足切りして使う。
     */
    const val DEPTH_SOURCE = "raw_depth"

    /** 信頼度 (0-255) の足切り。ARCore のドキュメントが例に挙げている値。 */
    const val MIN_CONFIDENCE = 128

    /** 深度画素の間引き。160x90 なら 1 フレーム最大 80x45 点。 */
    const val PIXEL_STRIDE = 2

    /**
     * ボクセルの一辺 [m]。点はボクセルごとに平均する。
     *
     * フレーム間の食い違いが約 5 cm あるので、これより細かくしても形は細かくならない。
     */
    const val VOXEL_SIZE_M = 0.01f

    /** 被写体中心が決まっているとき、その周りに残す半径 [m]。 */
    const val SUBJECT_RADIUS_M = 0.75f

    /** 被写体中心が無いとき（ガイドを切っていた等）、カメラから残す距離 [m]。 */
    const val MAX_DEPTH_M = 3.0f
}

/**
 * 点群に残す範囲。
 *
 * フレームごとの点は切り出す前の状態で持っておき、書き出すときにここで切る。
 * 被写体中心を取り直しても、撮影済みの点を失わずに切り直せる。
 */
sealed class Crop {
    /** 被写体中心（world 座標）の周りの球。 */
    class Sphere(val center: FloatArray, val radiusM: Float) : Crop()

    /** カメラからの距離。被写体中心が無いときの代わり。 */
    class MaxDepth(val maxDepthM: Float) : Crop()
}

/**
 * 1 フレームの深度から戻した点。座標は**そのフレームのカメラ座標**（OpenGL 系、
 * +X 右 / +Y 上 / -Z 前）で、world にはまだ移していない。
 *
 * world 姿勢は撮影時点では確定しないので（README「座標とドリフト補正が肝」）、
 * カメラ座標のまま持ち、Anchor から読み直した姿勢で後から移す。
 */
class CameraPoints(
    val size: Int,
    /** `x, y, z` を [size] 個並べたもの [m]。 */
    val xyz: FloatArray,
    /** `r, g, b` を [size] 個並べたもの。 */
    val rgb: ByteArray,
)

/** world 座標に移してボクセルにまとめた点群。 */
class PointCloud(
    val size: Int,
    /** `x, y, z` を [size] 個並べたもの。transforms.json と同じ world 座標 [m]。 */
    val xyz: FloatArray,
    /** `r, g, b` を [size] 個並べたもの。ボクセル内の平均。 */
    val rgb: ByteArray,
    /** ボクセルに入った点の数。少ないほど 1 回しか見えていない点で、外れ値を含みやすい。 */
    val counts: IntArray,
    val crop: Crop,
    /** 点を 1 つ以上出したフレームの数。 */
    val frameCount: Int,
)
