package com.example.arcorefetcher.capture

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 撮影セッションのディレクトリを 1 つの ZIP にまとめる（共有シートで渡すため）。 */
object Zip {

    /**
     * [dir] を丸ごと [target] に固める。ZIP 内のパスは `<dir名>/...` で始まる。
     * JPEG は既に圧縮済みなので格納レベルは低めにして時間を使わない。
     */
    fun zipDirectory(dir: File, target: File): File {
        ZipOutputStream(FileOutputStream(target).buffered()).use { zos ->
            zos.setLevel(1)
            addRecursively(zos, dir, dir.name)
        }
        return target
    }

    private fun addRecursively(zos: ZipOutputStream, file: File, entryPath: String) {
        if (file.isDirectory) {
            zos.putNextEntry(ZipEntry("$entryPath/"))
            zos.closeEntry()
            file.listFiles()?.sortedBy { it.name }?.forEach {
                addRecursively(zos, it, "$entryPath/${it.name}")
            }
            return
        }
        zos.putNextEntry(ZipEntry(entryPath))
        file.inputStream().buffered().use { it.copyTo(zos) }
        zos.closeEntry()
    }
}
