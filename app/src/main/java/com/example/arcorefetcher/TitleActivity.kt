package com.example.arcorefetcher

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.arcorefetcher.capture.CaptureStore
import com.example.arcorefetcher.databinding.ActivityTitleBinding

/**
 * 起動直後の画面。
 *
 * 以前は起動していきなりカメラが開いていた。ARCore のセッションはここでは作らず、
 * 「撮影を行う」で [MainActivity] に入ってから初めて作る。
 * カメラ権限と ARCore のインストール要求も [MainActivity] 側に置いたままにしてある
 * （撮影を始めると決めた人にだけ尋ねるため）。
 */
class TitleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTitleBinding

    /** Activity が STARTED になる前に登録する必要があるのでフィールドで持つ。 */
    private val saveToDevice = SaveToDeviceLauncher(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTitleBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        binding.startButton.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        binding.manualButton.setOnClickListener { Dialogs.showManual(this) }
        binding.savedButton.setOnClickListener {
            Dialogs.showSavedCaptures(this) { zip -> saveToDevice.save(zip) }
        }
    }

    override fun onResume() {
        super.onResume()
        // 撮影から戻ってきた直後に件数が増えているので、毎回数え直す。
        refreshSavedCount()
    }

    private fun refreshSavedCount() {
        val count = CaptureStore.savedZips(this).size
        binding.savedButton.text = if (count == 0) {
            getString(R.string.action_saved_data_empty)
        } else {
            getString(R.string.action_saved_data, count)
        }
        binding.savedButton.isEnabled = count > 0
    }

    /** システムバーの裏まで描くテーマなので、中身に余白を入れる。 */
    private fun applyWindowInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val basePaddingTop = binding.content.paddingTop
        val basePaddingBottom = binding.content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            // リスナーは複数回呼ばれるので、XML の余白に毎回足し直す（累積させない）。
            binding.content.updatePadding(
                top = basePaddingTop + bars.top,
                bottom = basePaddingBottom + bars.bottom,
            )
            insets
        }
    }
}
