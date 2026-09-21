package com.example.arcorefetcher.capture

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * グレースケール PNG のエンコーダ。
 *
 * 深度は 16bit の mm 値なので、[android.graphics.Bitmap] を経由できない
 * （Bitmap に 16bit グレースケールの設定が無く、8bit に落ちて mm が壊れる）。
 * 依存を増やさないため最小限の PNG を手書きする。
 *
 * 出力は無加工の生値。**値の解釈（無効画素の除去、信頼度による足切り）は
 * 一切しない**。選別の基準は撮影後に変えられるべきなので、ここでは判断しない。
 *
 * PNG の画素値はビッグエンディアン（仕様）。ARCore の深度バッファは
 * リトルエンディアンなので、[writeGray16] に渡す時点で
 * [DepthImages] が mm の [ShortArray] に直してある。
 */
object Png {

    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    /** 16bit グレースケール。深度 (mm) 用。 */
    fun writeGray16(values: ShortArray, width: Int, height: Int, stream: OutputStream) {
        require(values.size == width * height) {
            "画素数が合いません: ${values.size} != ${width}x$height"
        }
        val raw = ByteArray(width * height * 2)
        var i = 0
        for (v in values) {
            val u = v.toInt()
            // PNG はビッグエンディアン。符号は見ずにビット列をそのまま並べる。
            raw[i++] = (u ushr 8).toByte()
            raw[i++] = u.toByte()
        }
        write(stream, width, height, bitDepth = 16, raw = raw)
    }

    /** 8bit グレースケール。信頼度 (0-255) 用。 */
    fun writeGray8(values: ByteArray, width: Int, height: Int, stream: OutputStream) {
        require(values.size == width * height) {
            "画素数が合いません: ${values.size} != ${width}x$height"
        }
        write(stream, width, height, bitDepth = 8, raw = values)
    }

    /** @param raw filter バイトを含まない画素列。行ごとに連続していること。 */
    private fun write(stream: OutputStream, width: Int, height: Int, bitDepth: Int, raw: ByteArray) {
        require(width > 0 && height > 0) { "解像度が不正です: ${width}x$height" }

        stream.write(SIGNATURE)

        val ihdr = ByteArray(13)
        putInt(ihdr, 0, width)
        putInt(ihdr, 4, height)
        ihdr[8] = bitDepth.toByte()
        ihdr[9] = COLOR_TYPE_GRAY
        ihdr[10] = 0 // compression: deflate
        ihdr[11] = 0 // filter: adaptive
        ihdr[12] = 0 // interlace: なし
        chunk(stream, "IHDR", ihdr)

        // 各行の先頭に filter type 0 (None) を挟む。深度は隣接画素の差が
        // 大きく、予測フィルタを掛けても縮まないので None で固定する。
        val rowBytes = width * (bitDepth / 8)
        val filtered = ByteArray((rowBytes + 1) * height)
        for (r in 0 until height) {
            val dst = r * (rowBytes + 1)
            filtered[dst] = 0
            System.arraycopy(raw, r * rowBytes, filtered, dst + 1, rowBytes)
        }
        chunk(stream, "IDAT", deflate(filtered))

        chunk(stream, "IEND", ByteArray(0))
    }

    private fun chunk(stream: OutputStream, type: String, data: ByteArray) {
        val header = ByteArray(4)
        putInt(header, 0, data.size)
        stream.write(header)

        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        stream.write(typeBytes)
        stream.write(data)

        // CRC は type と data に掛ける（長さは含めない）。
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        val tail = ByteArray(4)
        putInt(tail, 0, crc.value.toInt())
        stream.write(tail)
    }

    /** PNG の IDAT は zlib ラッパ付き。[Deflater] の既定がそれなので nowrap は指定しない。 */
    private fun deflate(src: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED)
        val out = ByteArrayOutputStream(src.size / 2 + 64)
        try {
            deflater.setInput(src)
            deflater.finish()
            val buf = ByteArray(16 * 1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                if (n > 0) out.write(buf, 0, n)
            }
        } finally {
            deflater.end()
        }
        return out.toByteArray()
    }

    private fun putInt(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = (value ushr 24).toByte()
        dst[offset + 1] = (value ushr 16).toByte()
        dst[offset + 2] = (value ushr 8).toByte()
        dst[offset + 3] = value.toByte()
    }

    private const val COLOR_TYPE_GRAY: Byte = 0
}
