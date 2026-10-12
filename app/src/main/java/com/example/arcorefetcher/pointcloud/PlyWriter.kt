package com.example.arcorefetcher.pointcloud

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 点群を PLY (binary_little_endian) で書く。
 *
 * 頂点は `x y z`（float, world 座標 [m]）、`red green blue`（uchar）、
 * `count`（uint, ボクセルに入った点の数）。どれも PLY の標準的な型と名前なので、
 * 一般的な点群ツールでそのまま読める。`count` を知らない読み手は無視する。
 *
 * 依存を増やさないため手書きする。
 */
object PlyWriter {

    private const val BYTES_PER_VERTEX = 4 * 3 + 3 + 4
    private const val VERTICES_PER_CHUNK = 4096

    fun write(cloud: PointCloud, stream: OutputStream) {
        val header = buildString {
            append("ply\n")
            append("format binary_little_endian 1.0\n")
            append("comment source ").append(PointCloudSpec.SOURCE).append('\n')
            append("element vertex ").append(cloud.size).append('\n')
            append("property float x\n")
            append("property float y\n")
            append("property float z\n")
            append("property uchar red\n")
            append("property uchar green\n")
            append("property uchar blue\n")
            append("property uint count\n")
            append("end_header\n")
        }
        stream.write(header.toByteArray(Charsets.US_ASCII))

        val buf = ByteBuffer.allocate(BYTES_PER_VERTEX * VERTICES_PER_CHUNK)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until cloud.size) {
            buf.putFloat(cloud.xyz[i * 3])
            buf.putFloat(cloud.xyz[i * 3 + 1])
            buf.putFloat(cloud.xyz[i * 3 + 2])
            buf.put(cloud.rgb[i * 3])
            buf.put(cloud.rgb[i * 3 + 1])
            buf.put(cloud.rgb[i * 3 + 2])
            buf.putInt(cloud.counts[i])
            if (!buf.hasRemaining()) {
                stream.write(buf.array(), 0, buf.position())
                buf.clear()
            }
        }
        if (buf.position() > 0) stream.write(buf.array(), 0, buf.position())
    }
}
