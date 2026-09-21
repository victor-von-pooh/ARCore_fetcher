package com.example.arcorefetcher

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import com.example.arcorefetcher.capture.CaptureStore
import com.example.arcorefetcher.databinding.DialogExportDoneBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/**
 * タイトル画面と撮影画面が共有するダイアログ。
 *
 * どちらの画面からも同じ説明・同じ取り出し手段に行けるようにまとめてある。
 */
object Dialogs {

    /** 使い方。操作手順と、なぜ待たされるのかを説明する。 */
    fun showManual(activity: Activity) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_manual, null)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.manual_title)
            .setView(view)
            .setPositiveButton(R.string.action_close, null)
            .show()
    }

    /**
     * 書き出し完了。データの取り出し方を示す。
     *
     * **画面の外をタップしても閉じない**（`setCancelable(false)`）。
     * 誤タップでこのダイアログを飛ばしてしまい、撮り直しになる事故を防ぐのが目的。
     *
     * @param frameCount 表示用の枚数。過去データを開き直したときのように分からなければ null。
     * @param onSaveToDevice 「端末に保存」。保存先を選ぶ画面の起動は Activity 側が持つ。
     */
    fun showExportDone(
        activity: Activity,
        zip: File,
        frameCount: Int?,
        onSaveToDevice: (File) -> Unit,
    ) {
        val binding = DialogExportDoneBinding.inflate(activity.layoutInflater)
        binding.zipName.text = zip.name
        val size = CaptureStore.formatSize(zip.length())
        binding.zipDetail.text = if (frameCount == null) {
            activity.getString(R.string.export_detail_no_count, size)
        } else {
            activity.getString(R.string.export_detail, frameCount, size)
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.export_done_title)
            .setView(binding.root)
            .setCancelable(false)
            .create()

        // 保存も共有もキャンセルされうるので、押してもダイアログは閉じない。
        // 取り出せたかどうかはユーザーにしか分からない。閉じるのは明示操作だけ。
        binding.saveButton.setOnClickListener { onSaveToDevice(zip) }
        binding.shareButton.setOnClickListener { share(activity, zip) }
        binding.closeButton.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** 書き出し済みデータの一覧。選ぶと [showExportDone] が開く。 */
    fun showSavedCaptures(activity: Activity, onSaveToDevice: (File) -> Unit) {
        val zips = CaptureStore.savedZips(activity)
        if (zips.isEmpty()) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.saved_list_title)
                .setMessage(R.string.saved_list_empty)
                .setPositiveButton(R.string.action_close, null)
                .show()
            return
        }
        val labels = zips
            .map { "${it.name}\n${CaptureStore.formatSize(it.length())}" }
            .toTypedArray()
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.saved_list_title)
            .setItems(labels) { _, which ->
                showExportDone(activity, zips[which], frameCount = null, onSaveToDevice = onSaveToDevice)
            }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    private fun share(activity: Activity, zip: File) {
        val chooser = Intent.createChooser(
            CaptureStore.shareIntent(activity, zip),
            activity.getString(R.string.share_title),
        )
        try {
            activity.startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.msg_share_failed, Toast.LENGTH_LONG).show()
        }
    }
}
