package com.example.arcorefetcher

import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.example.arcorefetcher.capture.CaptureStore
import java.io.File

/**
 * 「端末に保存」の配線。
 *
 * [ComponentActivity.registerForActivityResult] は Activity が STARTED になる前に
 * 呼ぶ必要があるため、Activity のフィールド初期化子から生成すること
 * （onCreate の途中や、ボタンを押した時点で登録すると落ちる）。
 *
 * 保存先を選ぶ画面をキャンセルしても ZIP はアプリ内に残るので、何度でもやり直せる。
 */
class SaveToDeviceLauncher(private val activity: ComponentActivity) {

    private var pending: File? = null

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.CreateDocument(CaptureStore.MIME_ZIP)
    ) { uri ->
        val zip = pending
        pending = null
        if (uri == null || zip == null) return@registerForActivityResult
        runCatching { CaptureStore.copyTo(activity, zip, uri) }
            .onSuccess {
                toast(activity.getString(R.string.msg_saved_to_device, zip.name))
            }
            .onFailure {
                Log.e(TAG, "端末への保存に失敗", it)
                toast(activity.getString(R.string.msg_save_failed, it.message ?: it.javaClass.simpleName))
            }
    }

    fun save(zip: File) {
        pending = zip
        runCatching { launcher.launch(zip.name) }.onFailure {
            Log.e(TAG, "保存先を選ぶ画面を開けません", it)
            pending = null
            toast(activity.getString(R.string.msg_save_failed, it.javaClass.simpleName))
        }
    }

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val TAG = "SaveToDevice"
    }
}
