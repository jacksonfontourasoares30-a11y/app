package com.procam.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@OptIn(ExperimentalCamera2Interop::class)
class MainActivity : AppCompatActivity() {

    private enum class Tab(val title: String) {
        ISO("ISO"), SHUTTER("Obturador"), FOCUS("Foco"), WB("Branco"), EV("EV")
    }

    // Views
    private lateinit var previewView: PreviewView
    private lateinit var gridOverlay: GridOverlayView
    private lateinit var histogramView: HistogramView
    private lateinit var flashView: View
    private lateinit var tvInfo: TextView
    private lateinit var btnGrid: TextView
    private lateinit var btnSwitch: TextView
    private lateinit var presetContainer: LinearLayout
    private lateinit var tabContainer: LinearLayout
    private lateinit var tvControlLabel: TextView
    private lateinit var btnAuto: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnCapture: View
    private val tabViews = LinkedHashMap<Tab, TextView>()
    private val presetViews = LinkedHashMap<String, TextView>()

    // Câmera
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var caps: CameraCaps? = null
    private lateinit var analysisExecutor: ExecutorService
    private var orientationListener: OrientationEventListener? = null

    // Estado
    private lateinit var store: SettingsStore
    private var settings = ProSettings()
    private var currentPreset = Presets.AUTO
    private var currentTab = Tab.ISO

    // Valores que o modo automático está usando agora (lidos da câmera)
    @Volatile private var liveIso = 0
    @Volatile private var liveExposureNs = 0L
    @Volatile private var liveFocus = 0f

    private val mainHandler = Handler(Looper.getMainLooper())
    private val liveRefresh = object : Runnable {
        override fun run() {
            refreshTabs()
            mainHandler.postDelayed(this, 400)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.CAMERA] == true) {
                startCamera()
            } else {
                tvInfo.text = "Sem permissão de câmera. Libere nas configurações do Android."
            }
        }

    // ---------------------------------------------------------------------
    // Ciclo de vida
    // ---------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.previewView)
        gridOverlay = findViewById(R.id.gridOverlay)
        histogramView = findViewById(R.id.histogram)
        flashView = findViewById(R.id.flashView)
        tvInfo = findViewById(R.id.tvInfo)
        btnGrid = findViewById(R.id.btnGrid)
        btnSwitch = findViewById(R.id.btnSwitch)
        presetContainer = findViewById(R.id.presetContainer)
        tabContainer = findViewById(R.id.tabContainer)
        tvControlLabel = findViewById(R.id.tvControlLabel)
        btnAuto = findViewById(R.id.btnAuto)
        seekBar = findViewById(R.id.seekBar)
        btnCapture = findViewById(R.id.btnCapture)

        analysisExecutor = Executors.newSingleThreadExecutor()
        store = SettingsStore(this)
        settings = store.load("current") ?: ProSettings()
        currentPreset = store.preset
        gridOverlay.showGrid = store.gridOn
        btnGrid.isSelected = store.gridOn

        applyInsets()
        buildPresetChips()
        buildTabs()
        setupListeners()
        setupOrientation()
        refreshAll()

        if (hasPermissions()) startCamera() else permissionLauncher.launch(requiredPermissions())
    }

    override fun onResume() {
        super.onResume()
        mainHandler.post(liveRefresh)
        orientationListener?.enable()
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(liveRefresh)
        orientationListener?.disable()
        persist()
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }

    // ---------------------------------------------------------------------
    // Câmera
    // ---------------------------------------------------------------------

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            arrayOf(Manifest.permission.CAMERA)
        }

    private fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        provider.unbindAll()
        camera = null
        caps = null

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        // Preview + leitura dos valores que o automático está usando (ISO, obturador, foco)
        val previewBuilder = Preview.Builder()
        Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { liveIso = it }
                    result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { liveExposureNs = it }
                    result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { liveFocus = it }
                }
            }
        )
        val preview = previewBuilder.build()
        preview.setSurfaceProvider(previewView.surfaceProvider)

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .build()

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(320, 240),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        )
                    )
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(analysisExecutor, HistogramAnalyzer { bins ->
            histogramView.post { histogramView.update(bins) }
        })

        camera = try {
            provider.bindToLifecycle(this, selector, preview, capture, analysis)
        } catch (e: Exception) {
            // Alguns aparelhos não aguentam 3 fluxos ao mesmo tempo: sem histograma então.
            try {
                provider.unbindAll()
                histogramView.visibility = View.GONE
                provider.bindToLifecycle(this, selector, preview, capture)
            } catch (e2: Exception) {
                tvInfo.text = "Não foi possível abrir a câmera: ${e2.message}"
                null
            }
        }
        imageCapture = capture

        val cam = camera ?: return
        val c = CameraCaps.from(cam)
        caps = c
        settings = settings.sanitized(c)
        if (currentPreset != Presets.MANUAL && currentPreset in Presets.all) {
            settings = Presets.build(currentPreset, c)
        }
        if (!tabSupported(currentTab, c)) {
            currentTab = Tab.entries.firstOrNull { tabSupported(it, c) } ?: Tab.EV
        }
        updateInfo(c)
        applySettings()
        refreshAll()
    }

    /** Envia os ajustes manuais para a câmera. */
    private fun applySettings() {
        val cam = camera ?: return
        val c = caps ?: return
        val s = settings

        val b = CaptureRequestOptions.Builder()
        if (!s.autoExposure) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, s.iso)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, s.exposureNs)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(s.exposureNs, 33_333_333L))
        }
        if (!s.autoFocus) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, s.focusDiopters)
        }
        b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, s.wbMode)
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build())

        if (c.evSupported) {
            cam.cameraControl.setExposureCompensationIndex(if (s.autoExposure) s.evIndex else 0)
        }
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val name = "ProCam_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
            .format(System.currentTimeMillis())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/ProCam")
            }
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values,
        ).build()

        btnCapture.isEnabled = false
        flashView.alpha = 0.7f
        flashView.animate().alpha(0f).setDuration(250).start()

        capture.takePicture(options, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    btnCapture.isEnabled = true
                    Toast.makeText(this@MainActivity, "Foto salva em Pictures/ProCam", Toast.LENGTH_SHORT).show()
                }

                override fun onError(exception: ImageCaptureException) {
                    btnCapture.isEnabled = true
                    Toast.makeText(this@MainActivity, "Erro ao salvar: ${exception.message}", Toast.LENGTH_LONG).show()
                }
            })
    }

    // ---------------------------------------------------------------------
    // Interface
    // ---------------------------------------------------------------------

    private fun applyInsets() {
        val root = findViewById<View>(R.id.root)
        val top = findViewById<View>(R.id.topContainer)
        val bottom = findViewById<View>(R.id.bottomPanel)
        val topPad = top.paddingTop
        val bottomPad = bottom.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            top.setPadding(top.paddingLeft, topPad + bars.top, top.paddingRight, top.paddingBottom)
            bottom.setPadding(bottom.paddingLeft, bottom.paddingTop, bottom.paddingRight, bottomPad + bars.bottom)
            insets
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun buildPresetChips() {
        presetContainer.removeAllViews()
        presetViews.clear()
        for (name in Presets.all) {
            val chip = TextView(this).apply {
                text = name
                textSize = 13f
                setTextColor(ContextCompat.getColorStateList(this@MainActivity, R.color.chip_text))
                setBackgroundResource(R.drawable.chip_bg)
                setPadding(dp(14), dp(6), dp(14), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(8) }
                setOnClickListener { selectPreset(name) }
            }
            presetContainer.addView(chip)
            presetViews[name] = chip
        }
    }

    private fun buildTabs() {
        tabContainer.removeAllViews()
        tabViews.clear()
        for (tab in Tab.entries) {
            val tv = TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(ContextCompat.getColorStateList(this@MainActivity, R.color.tab_text))
                setPadding(0, dp(6), 0, dp(6))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    currentTab = tab
                    refreshTabs()
                    refreshControl()
                }
            }
            tabContainer.addView(tv)
            tabViews[tab] = tv
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupListeners() {
        btnCapture.setOnClickListener { takePhoto() }

        btnGrid.setOnClickListener {
            val on = !store.gridOn
            store.gridOn = on
            gridOverlay.showGrid = on
            btnGrid.isSelected = on
        }

        btnSwitch.setOnClickListener {
            val provider = cameraProvider ?: return@setOnClickListener
            val next = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            val sel = CameraSelector.Builder().requireLensFacing(next).build()
            val available = try { provider.hasCamera(sel) } catch (e: Exception) { false }
            if (available) {
                lensFacing = next
                bindCamera()
            } else {
                Toast.makeText(this, "Este aparelho não tem outra câmera", Toast.LENGTH_SHORT).show()
            }
        }

        btnAuto.setOnClickListener {
            val c = caps ?: return@setOnClickListener
            settings = when (currentTab) {
                Tab.ISO, Tab.SHUTTER ->
                    if (settings.autoExposure) enterManualExposure(c) else settings.copy(autoExposure = true)
                Tab.FOCUS ->
                    if (settings.autoFocus) settings.copy(autoFocus = false, focusDiopters = liveFocus)
                    else settings.copy(autoFocus = true)
                else -> settings
            }.sanitized(c)
            currentPreset = Presets.MANUAL
            applySettings()
            persist()
            refreshAll()
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onSeek(progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                persist()
            }
        })

        // Toque na imagem para focar (quando o foco está no automático)
        previewView.setOnTouchListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_UP) {
                val cam = camera
                if (cam != null && settings.autoFocus) {
                    val point = previewView.meteringPointFactory.createPoint(ev.x, ev.y)
                    val flags = if (settings.autoExposure)
                        FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                    else FocusMeteringAction.FLAG_AF
                    cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point, flags).build())
                }
                v.performClick()
            }
            true
        }
    }

    private fun setupOrientation() {
        // A tela fica em pé, mas a foto sai na posição certa se você virar o celular.
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                imageCapture?.targetRotation = rotation
            }
        }
    }

    /** Passa para exposição manual partindo do que o automático estava usando. */
    private fun enterManualExposure(c: CameraCaps): ProSettings {
        if (!settings.autoExposure) return settings
        return settings.copy(
            autoExposure = false,
            iso = if (liveIso > 0) liveIso.coerceIn(c.isoMin, c.isoMax) else settings.iso,
            exposureNs = if (liveExposureNs > 0) liveExposureNs.coerceIn(c.exposureMinNs, c.exposureMaxNs)
            else settings.exposureNs,
        )
    }

    private fun onSeek(progress: Int) {
        val c = caps ?: return
        val f = progress.toDouble() / SEEK_MAX
        settings = when (currentTab) {
            Tab.ISO -> enterManualExposure(c).copy(
                iso = logValue(f, c.isoMin.toDouble(), c.isoMax.toDouble()).roundToInt()
            )
            Tab.SHUTTER -> enterManualExposure(c).copy(
                exposureNs = logValue(f, c.exposureMinNs.toDouble(), c.exposureMaxNs.toDouble()).roundToLong()
            )
            Tab.FOCUS -> settings.copy(autoFocus = false, focusDiopters = (c.minFocusDiopters * f).toFloat())
            Tab.WB -> settings.copy(wbMode = c.wbModes[progress.coerceIn(0, c.wbModes.size - 1)].mode)
            Tab.EV -> settings.copy(evIndex = c.evMin + progress)
        }.sanitized(c)
        currentPreset = Presets.MANUAL
        applySettings()
        refreshPresets()
        refreshTabs()
        refreshControl(updateSeek = false)
    }

    private fun selectPreset(name: String) {
        val c = caps ?: return
        settings = if (name == Presets.MANUAL) {
            (store.load("manual") ?: settings).sanitized(c)
        } else {
            Presets.build(name, c)
        }
        currentPreset = name
        applySettings()
        persist()
        refreshAll()
        Toast.makeText(this, Presets.description(name), Toast.LENGTH_SHORT).show()
    }

    private fun persist() {
        if (!::store.isInitialized) return
        store.save("current", settings)
        store.preset = currentPreset
        if (currentPreset == Presets.MANUAL) store.save("manual", settings)
    }

    private fun updateInfo(c: CameraCaps) {
        fun yn(b: Boolean) = if (b) "sim" else "não"
        val side = if (lensFacing == CameraSelector.LENS_FACING_BACK) "Traseira" else "Frontal"
        tvInfo.text = "$side · nível ${c.levelName}\n" +
            "ISO/obturador manual: ${yn(c.manualExposure)} · foco manual: ${yn(c.manualFocus)} · RAW: ${yn(c.rawSupported)}"
    }

    private fun tabSupported(tab: Tab, c: CameraCaps): Boolean = when (tab) {
        Tab.ISO, Tab.SHUTTER -> c.manualExposure
        Tab.FOCUS -> c.manualFocus
        Tab.WB -> c.wbModes.size > 1
        Tab.EV -> c.evSupported
    }

    private fun refreshAll() {
        refreshPresets()
        refreshTabs()
        refreshControl()
    }

    private fun refreshPresets() {
        for ((name, chip) in presetViews) chip.isSelected = name == currentPreset
    }

    private fun refreshTabs() {
        val c = caps
        for ((tab, tv) in tabViews) {
            val value = if (c == null) "—" else tabValue(tab, c)
            tv.text = "${tab.title}\n$value"
            tv.isSelected = tab == currentTab
            tv.isEnabled = c != null && tabSupported(tab, c)
        }
    }

    private fun tabValue(tab: Tab, c: CameraCaps): String {
        if (!tabSupported(tab, c)) return "—"
        val s = settings
        return when (tab) {
            Tab.ISO ->
                if (s.autoExposure) (if (liveIso > 0) "A $liveIso" else "A") else s.iso.toString()
            Tab.SHUTTER ->
                if (s.autoExposure) (if (liveExposureNs > 0) "A ${formatShutter(liveExposureNs)}" else "A")
                else formatShutter(s.exposureNs)
            Tab.FOCUS -> if (s.autoFocus) "AF" else formatFocus(s.focusDiopters)
            Tab.WB -> c.wbLabel(s.wbMode)
            Tab.EV -> formatEv(s.evIndex * c.evStep)
        }
    }

    private fun refreshControl(updateSeek: Boolean = true) {
        val c = caps
        if (c == null) {
            tvControlLabel.text = "Abrindo câmera…"
            seekBar.isEnabled = false
            btnAuto.visibility = View.INVISIBLE
            return
        }
        val s = settings
        var max = SEEK_MAX
        var progress = 0
        var enabled = tabSupported(currentTab, c)
        var showAuto = enabled

        when (currentTab) {
            Tab.ISO -> {
                val shown = if (s.autoExposure && liveIso > 0) liveIso else s.iso
                progress = logPos(shown.toDouble(), c.isoMin.toDouble(), c.isoMax.toDouble())
                tvControlLabel.text = when {
                    !enabled -> "ISO manual não é suportado neste aparelho"
                    s.autoExposure -> "ISO automático — arraste para fixar"
                    else -> "ISO ${s.iso}"
                }
                btnAuto.isSelected = s.autoExposure
            }
            Tab.SHUTTER -> {
                val shown = if (s.autoExposure && liveExposureNs > 0) liveExposureNs else s.exposureNs
                progress = logPos(shown.toDouble(), c.exposureMinNs.toDouble(), c.exposureMaxNs.toDouble())
                tvControlLabel.text = when {
                    !enabled -> "Obturador manual não é suportado neste aparelho"
                    s.autoExposure -> "Obturador automático — arraste para fixar"
                    else -> "Obturador ${formatShutter(s.exposureNs)}"
                }
                btnAuto.isSelected = s.autoExposure
            }
            Tab.FOCUS -> {
                val shown = if (s.autoFocus) liveFocus else s.focusDiopters
                progress = if (c.minFocusDiopters > 0f)
                    (shown / c.minFocusDiopters * SEEK_MAX).roundToInt().coerceIn(0, SEEK_MAX) else 0
                tvControlLabel.text = when {
                    !enabled -> "Foco manual não é suportado nesta câmera"
                    s.autoFocus -> "Foco automático — toque na imagem para focar"
                    else -> "Foco ${formatFocus(s.focusDiopters)}  (esquerda = longe)"
                }
                btnAuto.isSelected = s.autoFocus
            }
            Tab.WB -> {
                max = (c.wbModes.size - 1).coerceAtLeast(1)
                progress = c.wbModes.indexOfFirst { it.mode == s.wbMode }.coerceAtLeast(0)
                tvControlLabel.text = "Balanço de branco: ${c.wbLabel(s.wbMode)}"
                showAuto = false
            }
            Tab.EV -> {
                max = (c.evMax - c.evMin).coerceAtLeast(1)
                progress = s.evIndex - c.evMin
                tvControlLabel.text = when {
                    !enabled -> "Compensação de exposição não suportada"
                    !s.autoExposure -> "EV só funciona com ISO/obturador no automático"
                    else -> "Compensação de exposição: ${formatEv(s.evIndex * c.evStep)}"
                }
                enabled = enabled && s.autoExposure
                showAuto = false
            }
        }

        seekBar.isEnabled = enabled
        btnAuto.visibility = if (showAuto) View.VISIBLE else View.INVISIBLE
        if (updateSeek) {
            seekBar.max = max
            seekBar.progress = progress
        }
    }
}
