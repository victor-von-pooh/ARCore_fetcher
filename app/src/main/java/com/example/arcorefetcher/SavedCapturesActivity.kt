package com.example.arcorefetcher

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.arcorefetcher.capture.CaptureStore
import com.example.arcorefetcher.databinding.ActivitySavedCapturesBinding
import com.example.arcorefetcher.databinding.ItemSavedCaptureBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 書き出し済みデータの管理。取り出しと削除。
 *
 * 件数が多くても数十なので RecyclerView は使わず、行を [LinearLayout] に
 * 並べ直す。リストの再構築は [render] に一本化してあり、削除や選択の変更は
 * 状態を変えて [render] を呼ぶだけでよい。
 */
class SavedCapturesActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySavedCapturesBinding

    /** Activity が STARTED になる前に登録する必要があるのでフィールドで持つ。 */
    private val saveToDevice = SaveToDeviceLauncher(this)

    private var zips: List<File> = emptyList()

    /**
     * 選択中のファイル名。[File] ではなく名前で持つのは、削除のたびに
     * [zips] を作り直しても選択が生き残るようにするため。
     */
    private val selected = LinkedHashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySavedCapturesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.selectAllButton.setOnClickListener { toggleSelectAll() }
        binding.deleteButton.setOnClickListener { confirmDeleteSelected() }

        reload()
    }

    /** ディスクから読み直して描画する。削除のあとは必ずこちらを通す。 */
    private fun reload() {
        zips = CaptureStore.savedZips(this)
        // 消えたファイルの選択が残らないようにする。
        selected.retainAll(zips.map { it.name }.toSet())
        render()
    }

    private fun render() {
        binding.listContainer.removeAllViews()
        for (zip in zips) {
            binding.listContainer.addView(buildRow(zip))
        }

        val empty = zips.isEmpty()
        binding.emptyText.visibility = if (empty) View.VISIBLE else View.GONE
        binding.listScroll.visibility = if (empty) View.GONE else View.VISIBLE
        binding.actionBar.visibility = if (empty) View.GONE else View.VISIBLE

        val total = zips.sumOf { CaptureStore.totalSizeOf(it) }
        binding.summaryText.text =
            getString(R.string.saved_summary, zips.size, CaptureStore.formatSize(total))
        binding.summaryText.visibility = if (empty) View.GONE else View.VISIBLE

        renderActions()
    }

    private fun buildRow(zip: File): View {
        val row = ItemSavedCaptureBinding.inflate(layoutInflater, binding.listContainer, false)
        row.nameText.text = zip.name
        row.detailText.text = getString(
            R.string.saved_item_detail,
            CaptureStore.formatSize(CaptureStore.totalSizeOf(zip)),
            DATE_FORMAT.format(Date(zip.lastModified())),
        )

        // setChecked はリスナーも呼ぶので、付け替える前に状態を入れる。
        row.checkBox.setOnCheckedChangeListener(null)
        row.checkBox.isChecked = zip.name in selected
        row.checkBox.setOnCheckedChangeListener { _, checked ->
            if (checked) selected += zip.name else selected -= zip.name
            renderActions()
        }

        row.row.setOnClickListener { openCapture(zip) }
        return row.root
    }

    /**
     * 下のボタンの文言と有効/無効だけを更新する。
     *
     * チェックの付け外しでは行を作り直さない（タップ中のビューが差し替わるため）ので、
     * [render] とは別に呼べるように分けてある。
     */
    private fun renderActions() {
        val allSelected = zips.isNotEmpty() && selected.size == zips.size
        binding.selectAllButton.setText(
            if (allSelected) R.string.action_select_none else R.string.action_select_all
        )
        binding.deleteButton.isEnabled = selected.isNotEmpty()
        binding.deleteButton.text = if (selected.isEmpty()) {
            getString(R.string.action_delete_none)
        } else {
            getString(R.string.action_delete_selected, selected.size)
        }
    }

    /** 1 件を開く。取り出しと、この 1 件だけの削除ができる。 */
    private fun openCapture(zip: File) {
        Dialogs.showExportDone(
            activity = this,
            zip = zip,
            frameCount = null,
            onSaveToDevice = { saveToDevice.save(it) },
            onDelete = { confirmDelete(listOf(it)) },
        )
    }

    private fun toggleSelectAll() {
        if (selected.size == zips.size) selected.clear() else selected.addAll(zips.map { it.name })
        render()
    }

    private fun confirmDeleteSelected() {
        val targets = zips.filter { it.name in selected }
        if (targets.isEmpty()) return
        confirmDelete(targets)
    }

    /**
     * 削除の確認。
     *
     * 端末に保存していなければ元に戻せないので、件数と解放される容量を出したうえで
     * 明示的に確認する。既定のボタンは「キャンセル」側。
     */
    private fun confirmDelete(targets: List<File>) {
        val size = CaptureStore.formatSize(targets.sumOf { CaptureStore.totalSizeOf(it) })
        val message = if (targets.size == 1) {
            getString(R.string.delete_confirm_one, targets[0].name, size)
        } else {
            getString(R.string.delete_confirm_many, targets.size, size)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_confirm_title)
            .setMessage(message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ -> delete(targets) }
            .show()
    }

    private fun delete(targets: List<File>) {
        val failed = targets.filterNot { zip ->
            runCatching { CaptureStore.delete(zip) }
                .onFailure { e -> Log.e(TAG, "削除に失敗: ${zip.name}", e) }
                .getOrDefault(false)
        }
        reload()
        val message = if (failed.isEmpty()) {
            getString(R.string.msg_deleted, targets.size)
        } else {
            getString(R.string.msg_delete_failed, failed.size)
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /** システムバーの裏まで描くテーマなので、上下の端に余白を入れる。 */
    private fun applyWindowInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val toolbarBaseTop = binding.toolbar.paddingTop
        val actionBaseBottom = binding.actionBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            // リスナーは複数回呼ばれるので、XML の余白に毎回足し直す（累積させない）。
            binding.toolbar.updatePadding(top = toolbarBaseTop + bars.top)
            binding.actionBar.updatePadding(bottom = actionBaseBottom + bars.bottom)
            insets
        }
    }

    private companion object {
        const val TAG = "SavedCaptures"
        val DATE_FORMAT = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
    }
}
