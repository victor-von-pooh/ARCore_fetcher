package com.example.arcorefetcher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.Image
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.arcorefetcher.capture.CaptureMeta
import com.example.arcorefetcher.capture.CaptureSessionWriter
import com.example.arcorefetcher.capture.CaptureSpec
import com.example.arcorefetcher.capture.CaptureStore
import com.example.arcorefetcher.capture.CenterSample
import com.example.arcorefetcher.capture.ConfidenceMap
import com.example.arcorefetcher.capture.DepthCapture
import com.example.arcorefetcher.capture.DepthImages
import com.example.arcorefetcher.capture.DepthMap
import com.example.arcorefetcher.capture.Intrinsics
import com.example.arcorefetcher.capture.NO_ANCHOR
import com.example.arcorefetcher.capture.PendingFrame
import com.example.arcorefetcher.capture.PoseMath
import com.example.arcorefetcher.capture.WriteResult
import com.example.arcorefetcher.capture.YuvJpeg
import com.example.arcorefetcher.coverage.ViewCoverage
import com.example.arcorefetcher.databinding.ActivityMainBinding
import com.example.arcorefetcher.render.BackgroundRenderer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Camera
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.CameraIntrinsics
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableException
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 撮影画面。ARCore セッションの管理とシャッター処理。
 *
 * [TitleActivity] の「撮影を行う」から入る。カメラ権限の要求と ARCore の
 * インストール要求はここで行う（撮ると決めた人にだけ尋ねるため）。
 *
 * ## スレッド規約（壊すとクラッシュする）
 *
 * ARCore のオブジェクト（[Session] / [Frame] / [Camera] / [Anchor]）は
 * **すべて GL スレッド ([onDrawFrame]) からのみ触る**。
 * UI スレッドからは [captureRequested] / [finishRequested] のフラグ越しに依頼する。
 *
 * JPEG エンコードとディスク書き込みは [CaptureSessionWriter] のワーカースレッドへ逃がし、
 * 描画ループを止めない。
 */
class MainActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    private lateinit var binding: ActivityMainBinding
    private val backgroundRenderer = BackgroundRenderer()

    private var session: Session? = null
    private var installRequested = false

    /**
     * 実際に有効化できた depth モード。非対応端末では DISABLED のまま。
     *
     * [captureFrame] は `RAW_DEPTH_ONLY` のとき平滑済み深度を取りにいってはいけない
     * （ARCore が例外を投げる）ので、モードを覚えておく必要がある。
     */
    private var depthMode: Config.DepthMode = Config.DepthMode.DISABLED
    private var permissionRequested = false

    /** UI スレッド → GL スレッドの依頼。 */
    private val captureRequested = AtomicBoolean(false)
    private val finishRequested = AtomicBoolean(false)
    private val retakeCenterRequested = AtomicBoolean(false)

    /**
     * 撮った視点方向のガイド。**出力には影響しない**表示専用の機構。
     *
     * 物体を中心に周回して撮る用途を前提にした助言なので、用途が違えば邪魔になる。
     * [guideEnabled] で切れるようにしてあり、切っている間は GL スレッドで
     * 一切計算しない。
     */
    private val coverage = ViewCoverage()

    /** UI スレッドが書き、GL スレッドが読む。 */
    @Volatile
    private var guideEnabled = true

    /** 被写体中心の推定を間引くためのフレーム数え。固定後は深度を読まない。 */
    private var centerSampleTick = 0

    /** 毎フレーム使う一時バッファ。描画ループで確保を増やさないため。 */
    private val cameraPosition = FloatArray(3)

    /** Activity が STARTED になる前に登録する必要があるのでフィールドで持つ。 */
    private val saveToDevice = SaveToDeviceLauncher(this)

    /**
     * 書き込み中のセッション。GL スレッドが読み書きするが、
     * 戻るボタンの確認のために UI スレッドからも参照するので volatile。
     */
    @Volatile
    private var writer: CaptureSessionWriter? = null

    /**
     * 撮影 1 枚につき 1 つ張る Anchor。添字が [PendingFrame.anchorIndex] になる。
     *
     * ARCore はこの Anchor を追跡し続け、ループクローズ・再ローカライズのたびに
     * pose を**遡及的に**補正する。書き出し直前に読み直すことで、
     * VIO の収束前に撮ったフレームも収束後の座標系へ引き直される。
     */
    private val frameAnchors = ArrayList<Anchor>()

    /** TRACKING が連続し始めたフレームの timestamp。切れたら 0 に戻す。 */
    private var trackingSinceNs = 0L
    /** TRACKING が連続し始めた時点のカメラ姿勢。移動量の基準。 */
    private var warmupOrigin: Pose? = null
    private var warmupElapsedNs = 0L
    private var warmupMovedM = 0f

    /**
     * VIO が収束したとみなせるか。false の間はシャッターを無効にする。
     * UI スレッドからも読むので volatile。
     */
    @Volatile
    private var warmedUp = false

    /**
     * 書き出し待ちに入ったフレームの timestamp。0 なら待っていない。
     *
     * 全 Anchor の姿勢を**同じ 1 フレームで**読めるまで待つ。戻るボタンの判定で
     * UI スレッドからも読むので volatile。
     */
    @Volatile
    private var finalizeWaitSinceNs = 0L
    private var finalizeWaitElapsedNs = 0L

    @Volatile
    private var finishing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        binding.surfaceView.apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(this@MainActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        binding.statusText.text = getString(R.string.status_idle)
        // ウォームアップが済むまで撮らせない（VIO 収束前の姿勢は救えないため）。
        binding.shutterButton.isEnabled = false
        binding.shutterButton.setOnClickListener { captureRequested.set(true) }
        binding.finishButton.setOnClickListener {
            if (finishing) return@setOnClickListener
            finishRequested.set(true)
        }
        binding.helpButton.setOnClickListener {
            startActivity(Intent(this, ManualActivity::class.java))
        }
        binding.guideButton.setOnClickListener { setGuideEnabled(!guideEnabled) }
        // 背景の深度を拾っていたときの逃げ道。撮り直しではないので撮影済みは失わない。
        binding.coverageView.setOnClickListener {
            if (depthMode == Config.DepthMode.DISABLED) {
                toast(getString(R.string.msg_coverage_no_depth))
                return@setOnClickListener
            }
            retakeCenterRequested.set(true)
        }
        setGuideEnabled(guideEnabled)

        onBackPressedDispatcher.addCallback(this, backGuard)
    }

    /**
     * 書き出す前に画面を離れると撮影データが消えるので、いったん止めて確認する。
     *
     * 書き出し中（ZIP 生成中）は、そもそも離脱させない。
     */
    private val backGuard = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (finishing || finalizeWaitSinceNs != 0L) {
                toast(getString(R.string.msg_writing))
                return
            }
            val pending = writer?.submittedCount?.get() ?: 0
            if (pending == 0) {
                finish()
                return
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.discard_title)
                .setMessage(getString(R.string.discard_message, pending))
                .setPositiveButton(R.string.action_keep_capturing, null)
                .setNegativeButton(R.string.action_discard) { _, _ -> finish() }
                .show()
        }
    }

    /**
     * システムバーの裏までカメラ映像を描きつつ、UI がバーに隠れないようにする。
     *
     * targetSdk 35 以降は端から端まで描画するのが既定で、システムバーの領域を
     * 自動では避けてくれない。そのままだと撮影ボタンがナビゲーションバーの
     * 下に潜り込む。[WindowCompat.setDecorFitsSystemWindows] を false にして
     * 全 API で挙動をそろえたうえで、隠れては困る 2 つのビューにだけ
     * バーの高さ分の余白を足す。
     */
    private fun applyWindowInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // XML で指定した余白。インセットを足し込む前に控えておかないと、
        // リスナーが複数回呼ばれるたびに余白が累積する。
        val statusBasePadding = binding.statusBar.paddingTop
        val controlBasePadding = binding.controlBar.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.statusBar.updatePadding(top = statusBasePadding + bars.top)
            binding.controlBar.updatePadding(bottom = controlBasePadding + bars.bottom)
            insets
        }
    }

    // ------------------------------------------------------------------
    // ライフサイクル
    // ------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        if (!hasCameraPermission()) {
            requestCameraPermission()
            return
        }
        if (session == null && !createSession()) return

        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "カメラを取得できません", e)
            toast(getString(R.string.msg_session_failed, e.message ?: e.javaClass.simpleName))
            session = null
            return
        }
        binding.surfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (session != null) {
            // GLSurfaceView を先に止めてから Session を pause する（逆だと
            // 描画スレッドが解放済みの Session を触りうる）。
            binding.surfaceView.onPause()
            session?.pause()
        }
    }

    override fun onDestroy() {
        // Session の close も GL スレッドが触らなくなってから。
        // 書き出さずに終了した場合はここが Anchor の最後の解放機会。
        detachFrameAnchors()
        session?.close()
        session = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA) return
        if (!hasCameraPermission()) {
            toast(getString(R.string.msg_camera_permission_required))
            finish()
        }
    }

    // ------------------------------------------------------------------
    // セッション生成
    // ------------------------------------------------------------------

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        if (permissionRequested) return
        permissionRequested = true
        requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
    }

    /** @return セッションを使える状態にできたら true。 */
    private fun createSession(): Boolean {
        try {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    return false
                }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
            }

            val session = Session(this)
            selectLargestCpuImageConfig(session)

            val config = session.config
            config.focusMode = Config.FocusMode.AUTO
            config.updateMode = Config.UpdateMode.BLOCKING
            // 撮影に不要な処理は切って、CPU 画像の取得にフレーム時間を回す。
            config.planeFindingMode = Config.PlaneFindingMode.DISABLED
            config.lightEstimationMode = Config.LightEstimationMode.DISABLED
            depthMode = selectDepthMode(session)
            config.depthMode = depthMode
            session.configure(config)

            // session を代入した瞬間に GL スレッドが描画を始め、ガイドも触り出す。
            // ガイドの初期化は必ずその前に済ませる。
            coverage.reset()
            binding.coverageView.update(null)
            // 深度が無ければガイドは成り立たない。ボタンごと畳んで期待させない。
            applyGuideAvailability()

            this.session = session
            return true
        } catch (e: UnavailableApkTooOldException) {
            // 端末側の Google Play Services for AR が古い。端末が非対応なわけでは
            // ないので、「対応していません」と言わず更新を促す。
            Log.e(TAG, "Google Play Services for AR が古い", e)
            toast(getString(R.string.msg_arcore_needs_update))
            return false
        } catch (e: UnavailableException) {
            Log.e(TAG, "ARCore を利用できません", e)
            toast(getString(R.string.msg_arcore_unavailable))
            return false
        } catch (e: Exception) {
            Log.e(TAG, "セッションの生成に失敗", e)
            toast(getString(R.string.msg_session_failed, e.message ?: e.javaClass.simpleName))
            return false
        }
    }

    /**
     * CPU 画像が一番大きいカメラ設定を選ぶ。
     *
     * 端末によっては 640x480 までしか選べない。取れた解像度は intrinsics と
     * transforms.json の w/h に正しく反映される（[intrinsicsFor]）ので、
     * 低くても仕様違反にはならない。
     */
    private fun selectLargestCpuImageConfig(session: Session) {
        val filter = CameraConfigFilter(session)
        val configs: List<CameraConfig> = session.getSupportedCameraConfigs(filter)
        val best = configs.maxByOrNull { it.imageSize.width.toLong() * it.imageSize.height } ?: return
        session.cameraConfig = best
        Log.i(TAG, "camera config: cpu=${best.imageSize}, gpu=${best.textureSize}")
    }

    /**
     * 端末が対応している中で一番情報量の多い depth モードを選ぶ。
     *
     * AUTOMATIC は平滑・穴埋め済みの深度に加えて raw 深度と信頼度も取れるが、
     * RAW_DEPTH_ONLY では平滑済みの方が取れない。どれを使うかは下流が決めるので、
     * 撮影側は取れるものが多い方に倒す。
     *
     * 深度は**足せない情報**である一方、非対応でも撮影自体は成立する。
     * よって非対応なら DISABLED のまま黙って続ける（撮影を止めない）。
     */
    private fun selectDepthMode(session: Session): Config.DepthMode = when {
        session.isDepthModeSupported(Config.DepthMode.AUTOMATIC) ->
            Config.DepthMode.AUTOMATIC
        session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY) ->
            Config.DepthMode.RAW_DEPTH_ONLY
        else ->
            Config.DepthMode.DISABLED
    }.also { Log.i(TAG, "depth mode: $it") }

    // ------------------------------------------------------------------
    // GLSurfaceView.Renderer — ここから下はすべて GL スレッド
    // ------------------------------------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        try {
            backgroundRenderer.createOnGlThread()
        } catch (e: Exception) {
            Log.e(TAG, "背景レンダラの初期化に失敗", e)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: 0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
        session?.setDisplayGeometry(rotation, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val session = this.session ?: return
        if (!backgroundRenderer.isInitialized) return

        val frame: Frame
        try {
            session.setCameraTextureName(backgroundRenderer.textureId)
            frame = session.update()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "カメラが使えなくなりました", e)
            return
        }

        backgroundRenderer.draw(frame)

        val camera = frame.camera
        updateWarmup(frame, camera)
        updateCoverage(frame, camera)

        if (captureRequested.getAndSet(false)) {
            runCatching { captureFrame(session, frame, camera) }
                .onFailure { Log.e(TAG, "撮影に失敗", it) }
        }
        if (finishRequested.getAndSet(false)) {
            runCatching { beginFinalize(frame) }
                .onFailure { Log.e(TAG, "書き出しの開始に失敗", it) }
        }
        if (finalizeWaitSinceNs != 0L) {
            runCatching { tryFinalize(frame, camera) }
                .onFailure { Log.e(TAG, "書き出しに失敗", it) }
        }
        updateStatus(camera)
    }

    // ------------------------------------------------------------------
    // 撮影
    // ------------------------------------------------------------------

    /** 初回シャッターで writer を作る。撮らずに終わったセッションのディレクトリは残さない。 */
    private fun ensureWriter(): CaptureSessionWriter? {
        writer?.let { return it }
        if (finishing) return null
        val outputRoot = CaptureStore.outputRoot(this)
        writer = runCatching {
            CaptureSessionWriter(outputRoot, buildMeta())
        }.onFailure {
            Log.e(TAG, "writer を作成できません", it)
        }.getOrNull()
        lastStatus = null
        return writer
    }

    /**
     * VIO が収束したかを見張り、収束するまでシャッターを無効にする。
     *
     * ARCore は VIO が収束しきる前でも `TRACKING` を報告する。その区間で撮ると、
     * 画像に写っているのに姿勢は見当違いの方向を向いた、下流から検出できない
     * フレームができる。[TrackingState.TRACKING] の**継続時間**と、その間に
     * 実際に動いた**距離**の両方を条件にして防ぐ。
     *
     * 距離を見るのは、三角測量に足るベースラインを踏まないと VIO の
     * スケールと姿勢が定まらないため。端末を置いたまま待っても収束しない。
     *
     * トラッキングが一度切れたら計測はやり直す。再ローカライズ直後も同じく
     * 座標系が動くので、初回と同じだけ待たせる。
     */
    private fun updateWarmup(frame: Frame, camera: Camera) {
        if (camera.trackingState != TrackingState.TRACKING) {
            trackingSinceNs = 0L
            warmupOrigin = null
            warmupElapsedNs = 0L
            warmupMovedM = 0f
            setWarmedUp(false)
            return
        }

        val pose = camera.pose
        val origin = warmupOrigin
        if (origin == null) {
            trackingSinceNs = frame.timestamp
            warmupOrigin = pose
            warmupElapsedNs = 0L
            warmupMovedM = 0f
            return
        }

        warmupElapsedNs = frame.timestamp - trackingSinceNs
        // 最大到達距離で見る。往復して戻ってきてもベースラインは踏んでいる。
        warmupMovedM = maxOf(warmupMovedM, PoseMath.distance(pose, origin))
        setWarmedUp(warmupElapsedNs >= WARMUP_MIN_NS && warmupMovedM >= WARMUP_MIN_MOVE_M)
    }

    /** GL スレッドから呼ぶ。変化したときだけ UI へ渡す。 */
    private fun setWarmedUp(value: Boolean) {
        if (warmedUp == value) return
        warmedUp = value
        runOnUiThread { applyShutterEnabled() }
    }

    private fun applyShutterEnabled() {
        binding.shutterButton.isEnabled = warmedUp && !finishing
    }

    /**
     * 撮れた合図。画面全体を一瞬光らせる。
     *
     * **押した瞬間ではなく、実際にフレームを預けられたときに呼ぶ。** 押しても撮れない
     * ことがある（ウォームアップ前、CPU 画像がまだ来ていない）ので、押下の手応えと
     * 撮影成功を同じ演出にまとめると、撮れていないのに撮れたと誤解させる。
     * 押した手応えはシャッター自身が中の丸を縮めて返す（[ShutterButton]）。
     */
    private fun flashCaptured() {
        val flash = binding.flashView
        flash.animate().cancel()
        flash.alpha = 0f
        flash.visibility = View.VISIBLE
        // 立ち上がりは速く、戻りはゆっくり。一瞬で消えると撮れたか分からない。
        flash.animate()
            .alpha(FLASH_PEAK_ALPHA)
            .setDuration(FLASH_IN_MS)
            .withEndAction {
                flash.animate()
                    .alpha(0f)
                    .setDuration(FLASH_OUT_MS)
                    .withEndAction { flash.visibility = View.GONE }
                    .start()
            }
            .start()

        // 手触りだけ返す。シャッター音は鳴らさない（撮影中の環境音を汚さないため）。
        binding.shutterButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    // ------------------------------------------------------------------
    // 撮った視点方向のガイド（表示専用。出力には影響しない）
    // ------------------------------------------------------------------

    /**
     * 被写体中心を追いながら、いま向いているセルを更新する。
     *
     * 中心が固定される前だけ深度を読む。固定後（最初の 1 枚を撮ったあと）は
     * カメラ位置の計算しか走らないので、描画ループへの負荷はほぼ無い。
     */
    private fun updateCoverage(frame: Frame, camera: Camera) {
        if (!guideEnabled || depthMode == Config.DepthMode.DISABLED) return
        if (camera.trackingState != TrackingState.TRACKING) return

        var changed = false

        if (retakeCenterRequested.getAndSet(false)) {
            subjectCenterFrom(frame, camera)?.let {
                coverage.retake(it)
                changed = true
                runOnUiThread { toast(getString(R.string.msg_coverage_retaken)) }
            }
        }

        // 固定前は画面中央の深度で中心を追う。毎フレーム読む必要は無いので間引く。
        if (!coverage.isLocked && centerSampleTick++ % CENTER_SAMPLE_INTERVAL == 0) {
            subjectCenterFrom(frame, camera)?.let {
                coverage.observeCenter(it)
                changed = true
            }
        }

        camera.pose.getTranslation(cameraPosition, 0)
        if (coverage.updateCurrent(cameraPosition)) changed = true

        if (changed) pushCoverage()
    }

    /**
     * 画面中央が指している点の world 座標。深度が取れなければ null。
     *
     * プレビューは中央を保ったまま切り取られるので、**画面中央は深度画像の
     * 幾何中心**に対応する。principal point とは限らないので、そこは intrinsics で
     * きちんと戻す。逆投影の符号は README「深度は JPEG と画角が違う」の式と同じで、
     * 落とすと点が上下・前後に裏返る。
     */
    private fun subjectCenterFrom(frame: Frame, camera: Camera): FloatArray? {
        val acquire: () -> Image = if (depthMode == Config.DepthMode.AUTOMATIC) {
            { frame.acquireDepthImage16Bits() }
        } else {
            { frame.acquireRawDepthImage16Bits() }
        }
        val sample: CenterSample = withImage("深度 (中心)", acquire) {
            DepthImages.centerSample(it)
        } ?: return null

        val k = intrinsicsFor(camera.textureIntrinsics, sample.width, sample.height)
        val t = sample.millimeters / 1000f
        val x = (sample.width / 2f - k.cx) * t / k.flX
        val y = -(sample.height / 2f - k.cy) * t / k.flY
        return PoseMath.transformToWorld(camera.pose, x, y, -t)
    }

    private fun pushCoverage() {
        val snap = coverage.snapshot()
        runOnUiThread { binding.coverageView.update(snap) }
    }

    private fun setGuideEnabled(value: Boolean) {
        guideEnabled = value
        binding.guideButton.alpha = if (value) 1f else DISABLED_ALPHA
        applyGuideAvailability()
    }

    /** 深度非対応ならガイドは成り立たないので、表示ごと畳む。 */
    private fun applyGuideAvailability() {
        val available = depthMode != Config.DepthMode.DISABLED
        binding.coverageView.visibility = if (guideEnabled && available) View.VISIBLE else View.GONE
        binding.guideButton.isEnabled = available
    }

    private fun captureFrame(session: Session, frame: Frame, camera: Camera) {
        // ボタンは無効にしてあるが、無効化が届く前のタップが残りうる。
        if (!warmedUp || camera.trackingState != TrackingState.TRACKING) {
            toast(getString(R.string.msg_waiting_tracking))
            return
        }
        val writer = ensureWriter() ?: return

        val image = try {
            frame.acquireCameraImage()
        } catch (e: NotYetAvailableException) {
            Log.w(TAG, "CPU 画像がまだ来ていません", e)
            return
        }

        val width: Int
        val height: Int
        val nv21: ByteArray
        try {
            width = image.width
            height = image.height
            // Image はプロセス共有バッファなので、コピーしたら即 close する。
            nv21 = YuvJpeg.toNv21(image)
        } finally {
            image.close()
        }

        // getPose()（物理カメラ姿勢）を使う。getDisplayOrientedPose() は描画用で、
        // getImageIntrinsics() と軸が合わないため使ってはいけない。
        val cameraPose = camera.pose

        // world 絶対姿勢をここで確定させない。このフレーム専用の Anchor として
        // ARCore に預け、書き出し直前に読み直す。ARCore はループクローズ・
        // 再ローカライズのたびに Anchor の pose を遡及的に補正するので、
        // 撮影後に起きた補正もフレーム単位で反映される。
        val anchor = runCatching { session.createAnchor(cameraPose) }
            .onFailure { Log.e(TAG, "フレーム Anchor を張れません", it) }
            .getOrNull()
        val anchorIndex = if (anchor == null) {
            NO_ANCHOR
        } else {
            frameAnchors += anchor
            frameAnchors.lastIndex
        }

        // TRACKING 以外も捨てずに記録する。選別は撮影後に行う。
        writer.submitFrame(
            PendingFrame(
                timestampNs = frame.timestamp,
                nv21 = nv21,
                imageWidth = width,
                imageHeight = height,
                anchorIndex = anchorIndex,
                poseAtCapture = cameraPose,
                trackingState = CaptureSpec.trackingStateOf(camera.trackingState.name),
                trackingFailureReason = CaptureSpec.failureReasonOf(camera.trackingFailureReason.name),
                intrinsics = intrinsicsFor(camera.imageIntrinsics, width, height),
                trackingElapsedNs = warmupElapsedNs,
                pointCount = pointCountOf(frame),
                depth = depthOf(frame, camera),
            )
        )

        // ガイドのセルを埋める。ここで中心も固定される（撮り始めたあとに中心が
        // 動くと、それまでに埋めたセルの意味が変わってしまうため）。
        if (guideEnabled && depthMode != Config.DepthMode.DISABLED) {
            camera.pose.getTranslation(cameraPosition, 0)
            coverage.addCapture(cameraPosition)
            pushCoverage()
        }

        // ここまで来たフレームは writer に預かられている。撮れた合図はこの時点で出す。
        runOnUiThread { flashCaptured() }
    }

    /**
     * このフレームの深度一式。端末が非対応なら null。
     *
     * 深度が取れなくてもフレームは捨てない。深度は `NotYetAvailableException` で
     * 普通に欠けるが、画像と姿勢はそのフレームで有効なので、深度の有無で
     * 選別してはいけない（選別の基準は下流が決める）。
     *
     * intrinsics は **`textureIntrinsics`** を深度の解像度へスケールして作る。
     * 深度は GPU テクスチャの画角に揃っていて、`imageIntrinsics`（CPU 画像）とは
     * 画角が違いうる。ここで imageIntrinsics を使うと、下流は気づかないまま
     * 歪んだ点群を得る。
     */
    private fun depthOf(frame: Frame, camera: Camera): DepthCapture? {
        if (depthMode == Config.DepthMode.DISABLED) return null

        // 平滑・穴埋め済みの深度は AUTOMATIC のときだけ取れる。
        val smooth = if (depthMode == Config.DepthMode.AUTOMATIC) {
            acquireDepth("depth") { frame.acquireDepthImage16Bits() }
        } else {
            null
        }
        val raw = acquireDepth("raw depth") { frame.acquireRawDepthImage16Bits() }
        val confidence = acquireConfidence(frame)

        // 深度が 1 枚も取れなければ信頼度だけ残しても使い道がないので捨てる。
        // 3 種類は同じ解像度で返ると ARCore が保証しているので、intrinsics は
        // 取れた方の解像度から作ればよい。
        val reference = smooth ?: raw ?: return null
        return DepthCapture(
            depth = smooth,
            rawDepth = raw,
            confidence = confidence,
            intrinsics = intrinsicsFor(
                camera.textureIntrinsics, reference.width, reference.height,
            ),
        )
    }

    private fun acquireDepth(label: String, acquire: () -> Image): DepthMap? =
        withImage(label, acquire) { DepthImages.toMillimeters(it) }

    private fun acquireConfidence(frame: Frame): ConfidenceMap? =
        withImage("confidence", { frame.acquireRawDepthConfidenceImage() }) {
            DepthImages.toConfidence(it)
        }

    /**
     * ARCore の [Image] を開いてコピーし、必ず閉じる。
     *
     * CPU 画像と同じくプロセス共有バッファなので、握ったままにすると
     * カメラパイプラインが止まる。取得に失敗しても撮影は続けるので、
     * 警告だけ残して null を返す。
     */
    private fun <T> withImage(label: String, acquire: () -> Image, copy: (Image) -> T): T? =
        runCatching {
            val image = acquire()
            try {
                copy(image)
            } finally {
                image.close()
            }
        }.onFailure { Log.w(TAG, "$label を取得できません", it) }.getOrNull()

    /**
     * このフレームで見えている特徴点の数。取れなければ null。
     *
     * 姿勢そのものの正しさは示さないが、特徴点が乏しいフレームは推定が弱い。
     * 下流で品質の切り分けに使う。
     */
    private fun pointCountOf(frame: Frame): Int? = runCatching {
        // points は 1 点あたり (x, y, z, confidence) の 4 float。
        frame.acquirePointCloud().use { it.points.remaining() / 4 }
    }.onFailure {
        Log.w(TAG, "点群を取得できません", it)
    }.getOrNull()

    /**
     * intrinsics は **実際に保存した画像の解像度**に対応させる。
     *
     * ARCore が報告する解像度と `acquireCameraImage()` の解像度がずれる端末が
     * あるため、同率スケールを掛ける。
     */
    private fun intrinsicsFor(src: CameraIntrinsics, imageW: Int, imageH: Int): Intrinsics {
        val focal = FloatArray(2)
        val principal = FloatArray(2)
        src.getFocalLength(focal, 0)
        src.getPrincipalPoint(principal, 0)
        val dims = src.imageDimensions
        val reportedW = dims.getOrElse(0) { imageW }.takeIf { it > 0 } ?: imageW
        val reportedH = dims.getOrElse(1) { imageH }.takeIf { it > 0 } ?: imageH

        val sx = imageW.toFloat() / reportedW
        val sy = imageH.toFloat() / reportedH
        return Intrinsics(
            flX = focal[0] * sx,
            flY = focal[1] * sy,
            cx = principal[0] * sx,
            cy = principal[1] * sy,
            w = imageW,
            h = imageH,
        )
    }

    // ------------------------------------------------------------------
    // 書き出し
    // ------------------------------------------------------------------

    /**
     * 「書き出し」が押された。すぐには書き出さず、姿勢を確定できる状態になるのを待つ。
     */
    private fun beginFinalize(frame: Frame) {
        val writer = this.writer ?: return
        if (writer.submittedCount.get() == 0) {
            toast(getString(R.string.msg_no_frames))
            return
        }
        if (finalizeWaitSinceNs != 0L) return
        finalizeWaitSinceNs = frame.timestamp
        finalizeWaitElapsedNs = 0L
        runOnUiThread {
            binding.shutterButton.isEnabled = false
            binding.finishButton.isEnabled = false
        }
    }

    /**
     * 全 Anchor の姿勢を**同じ 1 フレームで**読めたら書き出す。読めなければ待つ。
     *
     * ここが本質的に重要な箇所。ARCore はループクローズや再ローカライズで
     * world 座標を後から測り直す。Anchor はその補正を受けるが、
     * **別々のフレームで読むと、読んだ時点ごとに違う座標系の姿勢が混ざる**。
     * だから「1 フレームで全部」でなければならない。
     *
     * トラッキングが外れている間は Anchor の姿勢が未定義で読めない。ここで待たずに
     * 書き出すと、全フレームが撮影時点の姿勢のまま出て、測り直しが一切反映されない。
     * 実機データでは、それが 1 セッション内で 48.8 cm の座標ジャンプとして出た。
     *
     * 待っても戻らないことはあるので上限を切る。その場合は読めたぶんだけ補正し、
     * 読めなかったフレームには `pose_refreshed: false` を立てて下流に知らせる。
     */
    private fun tryFinalize(frame: Frame, camera: Camera) {
        val writer = this.writer ?: run { finalizeWaitSinceNs = 0L; return }

        finalizeWaitElapsedNs = frame.timestamp - finalizeWaitSinceNs
        val ready = camera.trackingState == TrackingState.TRACKING &&
            frameAnchors.all { it.trackingState == TrackingState.TRACKING }
        if (!ready && finalizeWaitElapsedNs < FINALIZE_TIMEOUT_NS) return

        // ここから下は同じフレームの中で完結させる。
        val refreshed: List<Pose?> = frameAnchors.map { anchor ->
            if (anchor.trackingState == TrackingState.TRACKING) anchor.pose else null
        }
        if (!ready) {
            Log.w(TAG, "姿勢を確定できないまま書き出します: " +
                "${refreshed.count { it == null }} / ${refreshed.size} 個の Anchor が読めません")
        }

        finalizeWaitSinceNs = 0L
        finishing = true
        this.writer = null
        runOnUiThread {
            binding.shutterButton.isEnabled = false
            binding.finishButton.isEnabled = false
            binding.statusText.text = getString(R.string.msg_writing)
        }

        writer.finish(refreshed) { result ->
            runOnUiThread { onWriteComplete(result) }
        }

        // pose は回収済みなので、ここで解放してよい。
        detachFrameAnchors()
    }

    /** Anchor を ARCore へ返す。二重 detach しても落ちないように握りつぶす。 */
    private fun detachFrameAnchors() {
        frameAnchors.forEach { runCatching { it.detach() } }
        frameAnchors.clear()
    }

    private fun onWriteComplete(result: Result<WriteResult>) {
        finishing = false
        // シャッターはウォームアップ状態に従う。無条件に戻すと、
        // 収束していないのに撮れてしまう。
        applyShutterEnabled()
        binding.finishButton.isEnabled = true
        lastStatus = null
        binding.statusText.text = getString(R.string.status_idle)

        result.onSuccess { written ->
            // 共有シートを直接開かない。誤タップで閉じると取り出す手段を見失うため、
            // 閉じない完了ダイアログを挟んで、保存・共有を何度でもやり直せるようにする。
            // trailing lambda は最後の引数（onDelete）に付くので、名前付きで渡す。
            Dialogs.showExportDone(
                activity = this,
                zip = written.zip,
                frameCount = written.frameCount,
                unrefreshedCount = written.unrefreshedCount,
                onSaveToDevice = { saveToDevice.save(it) },
            )
        }.onFailure { e ->
            Log.e(TAG, "書き出しに失敗", e)
            toast(getString(R.string.msg_write_failed, e.message ?: e.javaClass.simpleName))
        }
    }

    // ------------------------------------------------------------------
    // 小物
    // ------------------------------------------------------------------

    private fun buildMeta(): CaptureMeta = CaptureMeta(
        // ISO 8601 基本形式（例 20260920T103104+0900）。
        sessionId = SimpleDateFormat("yyyyMMdd'T'HHmmssZ", Locale.US).format(Date()),
        deviceModel = Build.MODEL ?: "unknown",
        arcoreVersion = arCoreVersion(),
        depthMode = CaptureSpec.depthModeOf(depthMode.name),
    )

    /**
     * 実機に入っている Google Play Services for AR のバージョン。
     *
     * 取得に失敗したときは、**ランタイム版と取り違えられない文字列**を返す。
     * ここでクライアントライブラリの版番号をそのまま返すと、
     * JSON には実機と無関係な値がもっともらしく残ってしまう。
     */
    private fun arCoreVersion(): String = runCatching {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(AR_CORE_PACKAGE, 0).versionName
    }.getOrNull() ?: "unknown (client $AR_CORE_CLIENT_VERSION)"

    private fun updateStatus(camera: Camera) {
        if (finishing) return
        val text = when {
            finalizeWaitSinceNs != 0L -> finalizeStatusText()
            warmedUp -> capturingStatusText(camera)
            else -> warmupStatusText(camera)
        }
        // ウォームアップ中は毎フレーム値が動くので、文字列が変わったときだけ渡す。
        if (text == lastStatus) return
        lastStatus = text
        runOnUiThread { binding.statusText.text = text }
    }

    private fun capturingStatusText(camera: Camera): String {
        val writer = this.writer
        val submitted = writer?.submittedCount?.get() ?: 0
        val tracking = writer?.trackingCount?.get() ?: 0
        return getString(R.string.status_frames, submitted, tracking) +
            "  /  " + camera.trackingState.name
    }

    /** 収束まであと何が足りないかを出す。「待てばいい」のか「動かすべき」のかを分ける。 */
    private fun warmupStatusText(camera: Camera): String {
        if (camera.trackingState != TrackingState.TRACKING) {
            return getString(R.string.status_waiting_tracking)
        }
        val sec = (warmupElapsedNs / 1_000_000_000.0).coerceAtMost(WARMUP_MIN_SEC)
        return getString(
            R.string.status_warmup,
            sec, WARMUP_MIN_SEC,
            warmupMovedM.coerceAtMost(WARMUP_MIN_MOVE_M), WARMUP_MIN_MOVE_M,
        )
    }

    /** 待っている理由と、あと何秒待つかを出す。黙って止まったように見せない。 */
    private fun finalizeStatusText(): String {
        val left = ((FINALIZE_TIMEOUT_NS - finalizeWaitElapsedNs) / 1_000_000_000L + 1)
            .coerceAtLeast(0)
        return getString(R.string.status_finalizing, left)
    }

    private var lastStatus: String? = null

    private fun toast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    private companion object {
        const val TAG = "ARCoreFetcher"

        /** 被写体中心を測り直すフレーム間隔。中心が固定される前しか走らない。 */
        const val CENTER_SAMPLE_INTERVAL = 3

        /** 入切できるボタンを「切」に見せるための不透明度。 */
        const val DISABLED_ALPHA = 0.5f

        /** 撮れたときの発光。白飛びさせず、撮れたと分かる程度に留める。 */
        const val FLASH_PEAK_ALPHA = 0.85f
        const val FLASH_IN_MS = 45L
        const val FLASH_OUT_MS = 220L
        const val REQUEST_CAMERA = 1001
        const val AR_CORE_PACKAGE = "com.google.ar.core"

        /** `app/build.gradle.kts` の `com.google.ar:core` と揃えること。 */
        const val AR_CORE_CLIENT_VERSION = "1.47.0"

        /**
         * 書き出し前に、全 Anchor の姿勢が読めるようになるのを待つ上限。
         *
         * 短すぎると補正を取りこぼし、長すぎると戻らない状況で待たせ続ける。
         */
        val FINALIZE_TIMEOUT_NS = 15_000_000_000L

        /** ウォームアップの条件: TRACKING の連続時間と、その間の最大移動距離。 */
        const val WARMUP_MIN_SEC = 3.0
        const val WARMUP_MIN_MOVE_M = 0.15f
        // const にすると toLong() が定数式でないと言われるので通常の val。
        val WARMUP_MIN_NS = (WARMUP_MIN_SEC * 1_000_000_000).toLong()
    }
}
