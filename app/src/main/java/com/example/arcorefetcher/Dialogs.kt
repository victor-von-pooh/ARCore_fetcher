package com.example.arcorefetcher

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.view.View
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
     * @param onDelete 「このデータを削除」。null なら削除ボタンを出さない。
     *   撮った直後の完了ダイアログでは出さず、保存済みデータの管理画面からだけ出す。
     */
    fun showExportDone(
        activity: Activity,
        zip: File,
        frameCount: Int?,
        onSaveToDevice: (File) -> Unit,
        onDelete: ((File) -> Unit)? = null,
    ) {
        val binding = DialogExportDoneBinding.inflate(activity.layoutInflater)
        binding.zipName.text = zip.name
        // ここは「取り出したら何 MB になるか」なので ZIP のサイズ。
        // 端末上の占有量（ZIP + 展開済みディレクトリ）は保存済みデータの管理画面に出す。
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

        if (onDelete == null) {
            binding.deleteButton.visibility = View.GONE
        } else {
            // 削除の確認は呼び出し側が出す。消えたデータをここで開いたままにはしない。
            binding.deleteButton.setOnClickListener {
                dialog.dismiss()
                onDelete(zip)
            }
        }
        dialog.show()
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
