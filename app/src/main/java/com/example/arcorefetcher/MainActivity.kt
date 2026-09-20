package com.example.arcorefetcher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.arcorefetcher.capture.CaptureMeta
import com.example.arcorefetcher.capture.CaptureSessionWriter
import com.example.arcorefetcher.capture.CaptureSpec
import com.example.arcorefetcher.capture.Intrinsics
import com.example.arcorefetcher.capture.PendingFrame
import com.example.arcorefetcher.capture.PoseMath
import com.example.arcorefetcher.capture.YuvJpeg
import com.example.arcorefetcher.databinding.ActivityMainBinding
import com.example.arcorefetcher.render.BackgroundRenderer
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Camera
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.CameraIntrinsics
import com.google.ar.core.Config
import com.google.ar.core.Frame
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
 * ARCore セッションの管理とシャッター処理。
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

    /** GL スレッドからのみ触る。 */
    private var writer: CaptureSessionWriter? = null
    private var rootAnchor: Anchor? = null

    @Volatile
    private var finishing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.surfaceView.apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(this@MainActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        binding.statusText.text = getString(R.string.status_idle)
        binding.shutterButton.setOnClickListener { captureRequested.set(true) }
        binding.finishButton.setOnClickListener {
            if (finishing) return@setOnClickListener
            finishRequested.set(true)
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
        // root Anchor はセッション冒頭に張る。writer は初回シャッターまで作らない。
        ensureRootAnchor(session, camera)

        if (captureRequested.getAndSet(false)) {
            runCatching { captureFrame(frame, camera) }
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
        val outputRoot = File(getExternalFilesDir(null), "captures")
        writer = runCatching {
            CaptureSessionWriter(outputRoot, buildMeta())
        }.onFailure {
            Log.e(TAG, "writer を作成できません", it)
        }.getOrNull()
        lastSubmitted = -1
        return writer
    }

    /**
     * セッション冒頭に root Anchor を 1 つだけ張る。
     *
     * 回転を単位クォータニオンにして生成することで、ARCore world の重力アライン
     * （`+Y` = 上）がそのまま anchor 座標系に引き継がれる。
     */
    private fun ensureRootAnchor(session: Session, camera: Camera) {
        if (rootAnchor != null) return
        if (camera.trackingState != TrackingState.TRACKING) return
        rootAnchor = runCatching {
            session.createAnchor(PoseMath.withIdentityRotation(camera.pose))
        }.onFailure {
            Log.e(TAG, "root Anchor を張れません", it)
        }.getOrNull()
    }

    private fun captureFrame(frame: Frame, camera: Camera) {
        val anchor = rootAnchor
        if (anchor == null) {
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

        // world 絶対ではなく anchor 相対で保持する。
        // world 座標系はループクローズで後から書き換わる。
        val relative = anchor.pose.inverse().compose(cameraPose)

        // TRACKING 以外も捨てずに記録する。選別は撮影後に行う。
        writer.submitFrame(
            PendingFrame(
                timestampNs = frame.timestamp,
                nv21 = nv21,
                imageWidth = width,
                imageHeight = height,
                poseRelativeToAnchor = relative,
                trackingState = CaptureSpec.trackingStateOf(camera.trackingState.name),
                trackingFailureReason = CaptureSpec.failureReasonOf(camera.trackingFailureReason.name),
                intrinsics = intrinsicsFor(camera.imageIntrinsics, width, height),
            )
        )
    }

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
        this.writer = null
        runOnUiThread {
            binding.shutterButton.isEnabled = false
            binding.finishButton.isEnabled = false
            binding.statusText.text = getString(R.string.msg_writing)
        }

        // 終了直前に root Anchor の pose を読み直す。
        // ここで読んだ pose には、それまでのループクローズ・再ローカライズによる
        // 遡及補正が反映されている。
        val anchor = rootAnchor
        val refreshedAnchorPose = anchor?.pose

        writer.finish(refreshedAnchorPose) { result ->
            runOnUiThread { onWriteComplete(result) }
        }

        anchor?.detach()
        rootAnchor = null
    }

    private fun onWriteComplete(result: Result<File>) {
        finishing = false
        binding.shutterButton.isEnabled = true
        binding.finishButton.isEnabled = true
        binding.statusText.text = getString(R.string.status_idle)

        result.onSuccess { zip ->
            toast(getString(R.string.msg_write_done, zip.name))
            shareZip(zip)
        }.onFailure { e ->
            Log.e(TAG, "書き出しに失敗", e)
            toast(getString(R.string.msg_write_failed, e.message ?: e.javaClass.simpleName))
        }
    }

    private fun shareZip(zip: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", zip)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, zip.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_title)))
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

    /** Google Play Services for AR のバージョン。取れなければクライアントライブラリ版。 */
    private fun arCoreVersion(): String = runCatching {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(AR_CORE_PACKAGE, 0).versionName
    }.getOrNull() ?: AR_CORE_CLIENT_VERSION

    private fun updateStatus(camera: Camera) {
        if (finishing) return
        val writer = this.writer
        val submitted = writer?.submittedCount?.get() ?: 0
        val tracking = writer?.trackingCount?.get() ?: 0
        if (submitted == lastSubmitted && camera.trackingState == lastTrackingState) return
        lastSubmitted = submitted
        lastTrackingState = camera.trackingState
        runOnUiThread {
            binding.statusText.text =
                getString(R.string.status_frames, submitted, tracking) +
                    "  /  " + camera.trackingState.name
        }
    }

    private var lastSubmitted = -1
    private var lastTrackingState: TrackingState? = null

    private fun toast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    private companion object {
        const val TAG = "ARCoreFetcher"
        const val REQUEST_CAMERA = 1001
        const val AR_CORE_PACKAGE = "com.google.ar.core"
        const val AR_CORE_CLIENT_VERSION = "1.47.0"
    }
}
