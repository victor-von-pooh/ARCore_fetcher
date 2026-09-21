package com.example.arcorefetcher

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.example.arcorefetcher.databinding.ActivityManualBinding
import com.example.arcorefetcher.databinding.ItemManualPageBinding

/** 使い方 1 ページ分。図と文の組。 */
private class ManualPage(
    @DrawableRes val figure: Int,
    @StringRes val heading: Int,
    @StringRes val body: Int,
)

/**
 * 使い方。図 1 枚につき 1 ページで、めくって読む。
 *
 * 以前は 1 枚のダイアログに全文を縦に並べていたが、手順が上から下へ流れるだけで
 * どこまで読んだか分からなかった。1 ページ 1 論点にして、図に状況を持たせている。
 *
 * ページは撮影の流れと同じ順に並ぶ。「何が記録されるか」から始めて
 * 「データは端末に残る」で終わる。
 *
 * カメラ画面からも開く。Activity なので ARCore セッションは一度止まり、
 * 戻るとウォームアップからやり直しになるが、トラッキングが切れた以上それが正しい。
 */
class ManualActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManualBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManualBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.pager.adapter = PageAdapter()
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = renderNav(position)
        })

        buildDots()
        binding.prevButton.setOnClickListener { go(-1) }
        binding.nextButton.setOnClickListener {
            // 最終ページの「次へ」は閉じるボタンとして働く。
            if (binding.pager.currentItem == PAGES.lastIndex) finish() else go(+1)
        }
        renderNav(binding.pager.currentItem)
    }

    private fun go(delta: Int) {
        val next = (binding.pager.currentItem + delta).coerceIn(PAGES.indices)
        binding.pager.setCurrentItem(next, true)
    }

    private fun buildDots() {
        val size = resources.getDimensionPixelSize(R.dimen.manual_dot_size)
        val gap = resources.getDimensionPixelSize(R.dimen.manual_dot_gap)
        repeat(PAGES.size) { i ->
            val dot = View(this).apply {
                setBackgroundResource(R.drawable.manual_dot)
                layoutParams = ViewGroup.MarginLayoutParams(size, size).apply {
                    marginStart = if (i == 0) 0 else gap
                }
            }
            binding.dots.addView(dot)
        }
    }

    private fun renderNav(position: Int) {
        for (i in 0 until binding.dots.childCount) {
            binding.dots.getChildAt(i).alpha = if (i == position) 1f else 0.28f
        }
        // 1 ページ目に「戻る」は要らないが、消すとボタンが動くので不可視にする。
        binding.prevButton.visibility = if (position == 0) View.INVISIBLE else View.VISIBLE
        binding.nextButton.setText(
            if (position == PAGES.lastIndex) R.string.action_close else R.string.action_next
        )
        binding.toolbar.subtitle = getString(R.string.manual_page_of, position + 1, PAGES.size)
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            PageHolder(ItemManualPageBinding.inflate(layoutInflater, parent, false))

        override fun getItemCount() = PAGES.size

        override fun onBindViewHolder(holder: PageHolder, position: Int) =
            holder.bind(PAGES[position])
    }

    private class PageHolder(
        private val binding: ItemManualPageBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(page: ManualPage) {
            binding.figure.setImageResource(page.figure)
            binding.heading.setText(page.heading)
            binding.body.setText(page.body)
        }
    }

    /** システムバーの裏まで描くテーマなので、上下の端に余白を入れる。 */
    private fun applyWindowInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val toolbarBaseTop = binding.toolbar.paddingTop
        val navBaseBottom = binding.navBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            // リスナーは複数回呼ばれるので、XML の余白に毎回足し直す（累積させない）。
            binding.toolbar.updatePadding(top = toolbarBaseTop + bars.top)
            binding.navBar.updatePadding(bottom = navBaseBottom + bars.bottom)
            insets
        }
    }

    private companion object {
        /** 撮影の流れと同じ順。図 1 枚につき 1 ページ。 */
        val PAGES = listOf(
            ManualPage(R.drawable.fig_what_is_recorded,
                R.string.manual_p1_title, R.string.manual_p1_body),
            ManualPage(R.drawable.fig_start_tracking,
                R.string.manual_p2_title, R.string.manual_p2_body),
            ManualPage(R.drawable.fig_wait_for_convergence,
                R.string.manual_p3_title, R.string.manual_p3_body),
            ManualPage(R.drawable.fig_keep_tracking,
                R.string.manual_p4_title, R.string.manual_p4_body),
            ManualPage(R.drawable.fig_depends_on_purpose,
                R.string.manual_p5_title, R.string.manual_p5_body),
            ManualPage(R.drawable.fig_orbit_example,
                R.string.manual_p6_title, R.string.manual_p6_body),
            ManualPage(R.drawable.fig_export_and_take_out,
                R.string.manual_p7_title, R.string.manual_p7_body),
            ManualPage(R.drawable.fig_storage,
                R.string.manual_p8_title, R.string.manual_p8_body),
        )
    }
}
