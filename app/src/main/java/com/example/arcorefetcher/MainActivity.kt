package com.example.arcorefetcher

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.util.Log
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
import com.example.arcorefetcher.capture.Intrinsics
import com.example.arcorefetcher.capture.NO_ANCHOR
import com.example.arcorefetcher.capture.PendingFrame
import com.example.arcorefetcher.capture.PoseMath
import com.example.arcorefetcher.capture.YuvJpeg
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
    private var permissionRequested = false

    /** UI スレッド → GL スレッドの依頼。 */
    private val captureRequested = AtomicBoolean(false)
    private val finishRequested = AtomicBoolean(false)

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
        binding.helpButton.setOnClickListener { Dialogs.showManual(this) }

        onBackPressedDispatcher.addCallback(this, backGuard)
    }

    /**
     * 書き出す前に画面を離れると撮影データが消えるので、いったん止めて確認する。
     *
     * 書き出し中（ZIP 生成中）は、そもそも離脱させない。
     */
    private val backGuard = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (finishing) {
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
            session.configure(config)

            this.session = session
            return true
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

        if (captureRequested.getAndSet(false)) {
            runCatching { captureFrame(session, frame, camera) }
                .onFailure { Log.e(TAG, "撮影に失敗", it) }
        }
        if (finishRequested.getAndSet(false)) {
            runCatching { finishSession() }
                .onFailure { Log.e(TAG, "書き出しの開始に失敗", it) }
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
            )
        )
    }

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

    private fun finishSession() {
        val writer = this.writer ?: return
        if (writer.submittedCount.get() == 0) {
            toast(getString(R.string.msg_no_frames))
            return
        }
        finishing = true
        // 完了ダイアログの表示に使う。writer を手放す前に控えておく。
        lastFrameCount = writer.submittedCount.get()
        this.writer = null
        runOnUiThread {
            binding.shutterButton.isEnabled = false
            binding.finishButton.isEnabled = false
            binding.statusText.text = getString(R.string.msg_writing)
        }

        // 終了直前に各フレームの Anchor の pose を読み直す。
        // ここで読んだ pose には、それまでのループクローズ・再ローカライズによる
        // 遡及補正が反映されている。
        //
        // TRACKING でない Anchor の pose は ARCore の規約上未定義なので回収しない。
        // その場合は撮影時点の姿勢がそのまま出力され、
        // origin_refreshed_at_end に false が立つ。
        val refreshed: List<Pose?> = frameAnchors.map { anchor ->
            if (anchor.trackingState == TrackingState.TRACKING) anchor.pose else null
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

    private fun onWriteComplete(result: Result<File>) {
        finishing = false
        // シャッターはウォームアップ状態に従う。無条件に戻すと、
        // 収束していないのに撮れてしまう。
        applyShutterEnabled()
        binding.finishButton.isEnabled = true
        lastStatus = null
        binding.statusText.text = getString(R.string.status_idle)

        result.onSuccess { zip ->
            // 共有シートを直接開かない。誤タップで閉じると取り出す手段を見失うため、
            // 閉じない完了ダイアログを挟んで、保存・共有を何度でもやり直せるようにする。
            Dialogs.showExportDone(this, zip, lastFrameCount) { saveToDevice.save(it) }
        }.onFailure { e ->
            Log.e(TAG, "書き出しに失敗", e)
            toast(getString(R.string.msg_write_failed, e.message ?: e.javaClass.simpleName))
        }
        lastFrameCount = null
    }

    // ------------------------------------------------------------------
    // 小物
    // ------------------------------------------------------------------

    private fun buildMeta(): CaptureMeta = CaptureMeta(
        // ISO 8601 基本形式（例 20260920T103104+0900）。
        sessionId = SimpleDateFormat("yyyyMMdd'T'HHmmssZ", Locale.US).format(Date()),
        deviceModel = Build.MODEL ?: "unknown",
        arcoreVersion = arCoreVersion(),
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
        val text = if (warmedUp) capturingStatusText(camera) else warmupStatusText(camera)
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

    private var lastStatus: String? = null

    /** 書き出し完了ダイアログに出す枚数。writer を手放す前に控える。 */
    private var lastFrameCount: Int? = null

    private fun toast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    private companion object {
        const val TAG = "ARCoreFetcher"
        const val REQUEST_CAMERA = 1001
        const val AR_CORE_PACKAGE = "com.google.ar.core"

        /** `app/build.gradle.kts` の `com.google.ar:core` と揃えること。 */
        const val AR_CORE_CLIENT_VERSION = "1.47.0"

        /** ウォームアップの条件: TRACKING の連続時間と、その間の最大移動距離。 */
        const val WARMUP_MIN_SEC = 3.0
        const val WARMUP_MIN_MOVE_M = 0.15f
        // const にすると toLong() が定数式でないと言われるので通常の val。
        val WARMUP_MIN_NS = (WARMUP_MIN_SEC * 1_000_000_000).toLong()
    }
}
