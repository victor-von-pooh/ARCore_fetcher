package com.example.arcorefetcher.capture

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

/**
 * 書き出し済み ZIP の置き場。
 *
 * ここに残っている限りデータは失われない。共有シートを閉じてしまっても
 * タイトル画面の「保存済みデータ」から取り出し直せる、というのがこの層の役割。
 */
object CaptureStore {

    const val MIME_ZIP = "application/zip"

    /** 出力先。[CaptureSessionWriter] のコンストラクタに渡すのと同じディレクトリ。 */
    fun outputRoot(context: Context): File = File(context.getExternalFilesDir(null), "captures")

    /** 書き出し済みの ZIP を新しい順に返す。 */
    fun savedZips(context: Context): List<File> =
        outputRoot(context)
            .listFiles { f -> f.isFile && f.name.endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** 共有シートへ渡す Intent。FileProvider 経由でないと他アプリから読めない。 */
    fun shareIntent(context: Context, zip: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
        return Intent(Intent.ACTION_SEND).apply {
            type = MIME_ZIP
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, zip.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * 端末の任意の場所（ダウンロード・Drive など）へコピーする。
     *
     * アプリをアンインストールすると [outputRoot] ごと消えるので、
     * 残したいデータはここでアプリの外へ出してもらう。
     */
    fun copyTo(context: Context, zip: File, target: Uri) {
        context.contentResolver.openOutputStream(target)?.use { out ->
            zip.inputStream().buffered().use { it.copyTo(out) }
        } ?: throw IllegalStateException("保存先を開けません: $target")
    }

    /** `12.3 MB` のような表示用の文字列。 */
    fun formatSize(bytes: Long): String {
        val mb = bytes / 1024.0 / 1024.0
        if (mb >= 1.0) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }
}
