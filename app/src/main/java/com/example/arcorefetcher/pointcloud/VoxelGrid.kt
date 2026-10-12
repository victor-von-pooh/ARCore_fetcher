package com.example.arcorefetcher.pointcloud

import kotlin.math.floor

/**
 * 点をボクセルごとに平均する。
 *
 * 同じ場所を何枚も撮ると点が重なって増え続けるので、ボクセル 1 つにつき 1 点へまとめる。
 * 平均すると深度のばらつきも少し均される。
 *
 * 1 セッションで数十万〜百万点を入れるので、`HashMap<Long, …>` の箱詰めを避けて
 * 開番地法の表を手で持つ。
 */
internal class VoxelGrid(private val voxelSize: Float) {

    private var keys = LongArray(INITIAL_CAPACITY).also { it.fill(EMPTY) }
    private var slots = IntArray(INITIAL_CAPACITY)

    private var sumX = DoubleArray(INITIAL_CAPACITY)
    private var sumY = DoubleArray(INITIAL_CAPACITY)
    private var sumZ = DoubleArray(INITIAL_CAPACITY)
    private var sumR = IntArray(INITIAL_CAPACITY)
    private var sumG = IntArray(INITIAL_CAPACITY)
    private var sumB = IntArray(INITIAL_CAPACITY)
    private var counts = IntArray(INITIAL_CAPACITY)

    var size = 0
        private set

    fun add(x: Float, y: Float, z: Float, r: Int, g: Int, b: Int) {
        val key = keyOf(x, y, z)
        if (key == EMPTY) return
        val s = slotFor(key)
        sumX[s] += x.toDouble()
        sumY[s] += y.toDouble()
        sumZ[s] += z.toDouble()
        sumR[s] += r
        sumG[s] += g
        sumB[s] += b
        counts[s]++
    }

    fun toPointCloud(crop: Crop, frameCount: Int): PointCloud {
        val xyz = FloatArray(size * 3)
        val rgb = ByteArray(size * 3)
        for (i in 0 until size) {
            val c = counts[i]
            xyz[i * 3] = (sumX[i] / c).toFloat()
            xyz[i * 3 + 1] = (sumY[i] / c).toFloat()
            xyz[i * 3 + 2] = (sumZ[i] / c).toFloat()
            rgb[i * 3] = (sumR[i] / c).toByte()
            rgb[i * 3 + 1] = (sumG[i] / c).toByte()
            rgb[i * 3 + 2] = (sumB[i] / c).toByte()
        }
        return PointCloud(size, xyz, rgb, counts.copyOf(size), crop, frameCount)
    }

    /** ボクセルの番号を 21bit ずつ 1 つの Long に詰める。範囲外（±10 km 超）なら [EMPTY]。 */
    private fun keyOf(x: Float, y: Float, z: Float): Long {
        val ix = floor(x / voxelSize).toLong()
        val iy = floor(y / voxelSize).toLong()
        val iz = floor(z / voxelSize).toLong()
        if (ix !in -OFFSET until OFFSET || iy !in -OFFSET until OFFSET || iz !in -OFFSET until OFFSET) {
            return EMPTY
        }
        return ((ix + OFFSET) shl 42) or ((iy + OFFSET) shl 21) or (iz + OFFSET)
    }

    /** [key] の集計先の番号。無ければ作る。 */
    private fun slotFor(key: Long): Int {
        if ((size + 1) * 2 > keys.size) grow()
        val mask = keys.size - 1
        var i = hash(key) and mask
        while (true) {
            val k = keys[i]
            if (k == key) return slots[i]
            if (k == EMPTY) {
                keys[i] = key
                slots[i] = size
                return size++
            }
            i = (i + 1) and mask
        }
    }

    private fun grow() {
        val oldKeys = keys
        val oldSlots = slots
        val capacity = oldKeys.size * 2
        keys = LongArray(capacity).also { it.fill(EMPTY) }
        slots = IntArray(capacity)
        val mask = capacity - 1
        for (j in oldKeys.indices) {
            val k = oldKeys[j]
            if (k == EMPTY) continue
            var i = hash(k) and mask
            while (keys[i] != EMPTY) i = (i + 1) and mask
            keys[i] = k
            slots[i] = oldSlots[j]
        }
        // 集計の配列は表の半分あれば足りる（使用率を 1/2 以下に保っているため）。
        sumX = sumX.copyOf(capacity / 2)
        sumY = sumY.copyOf(capacity / 2)
        sumZ = sumZ.copyOf(capacity / 2)
        sumR = sumR.copyOf(capacity / 2)
        sumG = sumG.copyOf(capacity / 2)
        sumB = sumB.copyOf(capacity / 2)
        counts = counts.copyOf(capacity / 2)
    }

    private fun hash(key: Long): Int = ((key * HASH_MULTIPLIER) ushr 32).toInt()

    private companion object {
        const val INITIAL_CAPACITY = 1 shl 14
        /** 詰めた番号は必ず 0 以上になるので、負の値を空き番地の印に使える。 */
        const val EMPTY = -1L
        const val OFFSET = 1L shl 20
        const val HASH_MULTIPLIER = -7046029254386353131L // 0x9E3779B97F4A7C15
    }
}
