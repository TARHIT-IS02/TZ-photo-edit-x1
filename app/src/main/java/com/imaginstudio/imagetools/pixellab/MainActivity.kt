package com.imaginstudio.imagetools.pixellab

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import com.imaginstudio.imagetools.pixellab.databinding.ActivityMainBinding
import com.imaginstudio.imagetools.pixellab.engine.PixelLabRenderer
import com.imaginstudio.imagetools.pixellab.model.AppDefaults
import com.imaginstudio.imagetools.pixellab.model.ArrowLayer
import com.imaginstudio.imagetools.pixellab.model.AssetRepository
import com.imaginstudio.imagetools.pixellab.model.BackgroundType
import com.imaginstudio.imagetools.pixellab.model.BezierLayer
import com.imaginstudio.imagetools.pixellab.model.BoundedShapeLabel
import com.imaginstudio.imagetools.pixellab.model.CanvasLayer
import com.imaginstudio.imagetools.pixellab.model.DrawingLayer
import com.imaginstudio.imagetools.pixellab.model.GridState
import com.imaginstudio.imagetools.pixellab.model.ImageLayer
import com.imaginstudio.imagetools.pixellab.model.JsonPresetCodec
import com.imaginstudio.imagetools.pixellab.model.ProjectState
import com.imaginstudio.imagetools.pixellab.model.SavedBackgroundPreset
import com.imaginstudio.imagetools.pixellab.model.SavedTextStyle
import com.imaginstudio.imagetools.pixellab.model.ShapeLayer
import com.imaginstudio.imagetools.pixellab.model.StudioTab
import com.imaginstudio.imagetools.pixellab.model.TextLayer
import com.imaginstudio.imagetools.pixellab.ui.Dialogs
import com.imaginstudio.imagetools.pixellab.ui.LayersDrawerController
import android.provider.OpenableColumns
import com.imaginstudio.imagetools.pixellab.ui.OptionExpandController
import com.imaginstudio.imagetools.pixellab.undo.HistoryManager
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val history = HistoryManager()

    private var project: ProjectState = createInitialProject()
    private var selectedLayerId: String? = project.layers.firstOrNull()?.id
    private var activeTab: StudioTab = StudioTab.TEXT
    private var isFullscreen: Boolean = false

    private lateinit var layersDrawerController: LayersDrawerController
    private lateinit var optionExpandController: OptionExpandController

    private var reselectingImageLayerId: String? = null
    private var pendingExportJson: String? = null
    private var pendingImportCallback: ((String) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val autosaveExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val importCounter = AtomicInteger(0)
    private val autosaveRunnable = Runnable {
        val snapshotJson = runCatching { project.toJson().toString() }.getOrNull() ?: return@Runnable
        autosaveExecutor.execute {
            runCatching {
                File(filesDir, "autosave.plab").writeText(snapshotJson)
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private val exportJsonLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val payload = pendingExportJson
            pendingExportJson = null
            if (uri != null && payload != null) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(payload.toByteArray(Charsets.UTF_8))
                    }
                }.onSuccess {
                    Toast.makeText(this, "Exported JSON", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(this, "Failed to export JSON", Toast.LENGTH_SHORT).show()
                }
            }
        }

    private val importJsonLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val cb = pendingImportCallback
            pendingImportCallback = null
            if (uri != null && cb != null) {
                runCatching {
                    contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                }.onSuccess { text ->
                    if (!text.isNullOrBlank()) {
                        cb(text)
                    } else {
                        Toast.makeText(this, "Selected file is empty", Toast.LENGTH_SHORT).show()
                    }
                }.onFailure {
                    Toast.makeText(this, "Failed to read JSON file", Toast.LENGTH_SHORT).show()
                }
            }
        }

    private fun requestExportJson(suggestedName: String, content: String) {
        pendingExportJson = content
        exportJsonLauncher.launch(suggestedName)
    }

    private fun requestImportJson(onLoaded: (String) -> Unit) {
        pendingImportCallback = onLoaded
        importJsonLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
    }

    private val pickLayerImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val reselectId = reselectingImageLayerId
        reselectingImageLayerId = null
        if (uri != null) {
            if (reselectId != null) {
                val localPath = copyUriToLocalCache(uri, "layer_img") ?: uri.toString()
                val dims = readBitmapDimensions(localPath)
                val maxSide = 520f
                val scale = kotlin.math.min(
                    maxSide / dims.first.coerceAtLeast(1),
                    maxSide / dims.second.coerceAtLeast(1)
                ).coerceAtMost(1f)
                val w = (dims.first * scale).coerceAtLeast(120f)
                val h = (dims.second * scale).coerceAtLeast(120f)
                mutateProject(pushToHistory = true) { p ->
                    p.copy(layers = p.layers.map { l ->
                        if (l is ImageLayer && l.id == reselectId) {
                            l.copy(uriString = localPath, width = w, height = h)
                        } else l
                    })
                }
            } else {
                importMultipleLayerImages(listOf(uri))
            }
        }
    }

    private val pickMultipleLayerImagesLauncher =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            reselectingImageLayerId = null
            if (!uris.isNullOrEmpty()) {
                importMultipleLayerImages(uris)
            }
        }

    private fun importMultipleLayerImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val total = uris.size
        val canvasW = project.width.toFloat()
        val canvasH = project.height.toFloat()
        if (total > 3) {
            Toast.makeText(this, "Importing $total images...", Toast.LENGTH_SHORT).show()
        }
        autosaveExecutor.execute {
            val newLayers = ArrayList<ImageLayer>(total)
            for ((idx, uri) in uris.withIndex()) {
                val localPath = copyUriToLocalCache(uri, "layer_img") ?: continue
                val dims = readBitmapDimensions(localPath)
                val maxSide = 520f
                val scale = kotlin.math.min(
                    maxSide / dims.first.coerceAtLeast(1),
                    maxSide / dims.second.coerceAtLeast(1)
                ).coerceAtMost(1f)
                val w = (dims.first * scale).coerceAtLeast(120f)
                val h = (dims.second * scale).coerceAtLeast(120f)

                val posX: Float
                val posY: Float
                if (total == 1) {
                    posX = canvasW / 2f
                    posY = canvasH / 2f
                } else {
                    val col = (idx % 5) - 2
                    val row = ((idx / 5) % 5) - 2
                    posX = (canvasW / 2f + col * 28f).coerceIn(w / 4f, (canvasW - w / 4f).coerceAtLeast(w / 4f))
                    posY = (canvasH / 2f + row * 28f).coerceIn(h / 4f, (canvasH - h / 4f).coerceAtLeast(h / 4f))
                }

                val displayName = resolveUriDisplayName(uri)
                    ?: if (total == 1) "Image" else "Image ${idx + 1}"

                newLayers.add(
                    ImageLayer(
                        id = "img_${System.currentTimeMillis()}_${importCounter.incrementAndGet()}",
                        name = displayName,
                        x = posX,
                        y = posY,
                        width = w,
                        height = h,
                        uriString = localPath
                    )
                )
            }

            mainHandler.post {
                if (isDestroyed || isFinishing) return@post
                if (newLayers.isEmpty()) {
                    Toast.makeText(this, "Failed to load selected images", Toast.LENGTH_SHORT).show()
                    return@post
                }
                mutateProject(pushToHistory = true) { p ->
                    p.copy(layers = p.layers + newLayers)
                }
                selectLayer(newLayers.last().id)
                if (newLayers.size > 1) {
                    Toast.makeText(this, "Added ${newLayers.size} images", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val pickBgImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val localPath = copyUriToLocalCache(uri, "bg_img") ?: uri.toString()
            mutateProject(pushToHistory = true) { p ->
                p.copy(
                    background = p.background.copy(
                        type = BackgroundType.IMAGE,
                        imageUri = localPath
                    )
                )
            }
        }
    }

    private val pickBgPresetImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            var lastSavedPath: String? = null
            var addedCount = 0
            for (uri in uris) {
                val seq = importCounter.incrementAndGet()
                val dest = File(filesDir, "bg_presets/preset_${System.currentTimeMillis()}_${seq}.png").apply {
                    parentFile?.mkdirs()
                }
                val savedPath = runCatching {
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(dest).use { output -> input.copyTo(output) }
                    }
                    dest.absolutePath
                }.getOrNull()

                if (savedPath != null) {
                    lastSavedPath = savedPath
                    addedCount++
                    JsonPresetCodec.saveBgPreset(
                        this,
                        SavedBackgroundPreset(
                            id = "bg_img_${System.currentTimeMillis()}_${seq}",
                            name = resolveUriDisplayName(uri) ?: "Custom Image",
                            type = BackgroundType.IMAGE,
                            imageUri = savedPath,
                            isCustom = true
                        )
                    )
                }
            }

            if (lastSavedPath != null) {
                if (optionExpandController.isOpen() && optionExpandController.activeToolId == "bg_presets") {
                    applyProjectLive(
                        project.copy(
                            background = project.background.copy(
                                type = BackgroundType.IMAGE,
                                imageUri = lastSavedPath
                            )
                        )
                    )
                    optionExpandController.refreshActiveTool()
                } else {
                    mutateProject(pushToHistory = true) { p ->
                        p.copy(
                            background = p.background.copy(
                                type = BackgroundType.IMAGE,
                                imageUri = lastSavedPath
                            )
                        )
                    }
                }
                val msg = if (addedCount > 1) "$addedCount background presets added" else "Background preset added"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
            }
        }

    private val quotesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val quote = res.data?.getStringExtra(QuotesActivity.EXTRA_QUOTE) ?: return@registerForActivityResult
            val selText = getSelectedLayer() as? TextLayer
            if (selText != null) {
                mutateProject(pushToHistory = true) { p ->
                    val updated = selText.copy(text = quote)
                    val (nw, nh) = PixelLabRenderer.measureTextBounds(updated, this)
                    p.copy(layers = p.layers.map { if (it.id == selText.id) updated.copy(width = nw, height = nh) else it })
                }
            } else {
                val tl = TextLayer(
                    id = "txt_${System.currentTimeMillis()}",
                    name = quote.take(18),
                    x = project.width / 2f,
                    y = project.height / 2f,
                    text = quote,
                    fontSize = 68f
                )
                val (nw, nh) = PixelLabRenderer.measureTextBounds(tl, this)
                addLayer(tl.copy(width = nw, height = nh))
            }
        }
    }

    private var pendingCameraFile: File? = null
    private var pendingCameraForBg: Boolean = false

    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = pendingCameraFile
        pendingCameraFile = null
        if (success && file != null && file.exists() && file.length() > 0L) {
            val path = normalizeCapturedCameraImage(file)
            if (pendingCameraForBg) {
                mutateProject(pushToHistory = true) { p ->
                    p.copy(background = p.background.copy(type = BackgroundType.IMAGE, imageUri = path))
                }
            } else {
                val dims = readBitmapDimensions(path)
                val maxSide = 520f
                val scale = kotlin.math.min(maxSide / dims.first.coerceAtLeast(1), maxSide / dims.second.coerceAtLeast(1)).coerceAtMost(1f)
                addLayer(
                    ImageLayer(
                        id = "cam_${System.currentTimeMillis()}",
                        name = "Camera Photo",
                        x = project.width / 2f,
                        y = project.height / 2f,
                        width = (dims.first * scale).coerceAtLeast(120f),
                        height = (dims.second * scale).coerceAtLeast(120f),
                        uriString = path
                    )
                )
            }
        }
    }

    private val pickTextureLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val localPath = copyUriToLocalCache(uri, "tex_img") ?: uri.toString()
            mutateProject(pushToHistory = true) { p ->
                p.copy(layers = p.layers.map { l ->
                    when {
                        l is TextLayer && l.id == selectedLayerId -> l.copy(textureUri = localPath)
                        l is ShapeLayer && l.id == selectedLayerId -> l.copy(textureUri = localPath)
                        else -> l
                    }
                })
            }
        }
    }

    private fun launchCamera(forBackground: Boolean) {
        runCatching {
            val dir = File(filesDir, "media").apply { mkdirs() }
            val file = File(dir, "cam_${System.currentTimeMillis()}.jpg")
            pendingCameraFile = file
            pendingCameraForBg = forBackground
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            takePictureLauncher.launch(uri)
        }.onFailure {
            Toast.makeText(this, "Camera unavailable", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestHighRefreshRate() {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        } ?: return
        val supported = display.supportedModes
        val active = display.mode
        val bestMode = supported
            .filter { it.physicalWidth == active.physicalWidth && it.physicalHeight == active.physicalHeight }
            .maxByOrNull { it.refreshRate }
        if (bestMode != null && bestMode.modeId != active.modeId) {
            window.attributes = window.attributes.apply {
                preferredDisplayModeId = bestMode.modeId
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            window.statusBarColor = Color.BLACK
            window.navigationBarColor = Color.BLACK
        }
        requestHighRefreshRate()
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupWindowInsets()

        if (!loadAutoSaveIfAvailable()) {
            project = createDefaultPresetProject()
            selectedLayerId = project.layers.firstOrNull()?.id
        }

        layersDrawerController = LayersDrawerController(
            context = this,
            binding = binding,
            getProject = { project },
            getSelectedId = { selectedLayerId },
            onSelectLayer = { id -> selectLayer(id) },
            onMutateProject = { transform -> mutateProject(pushToHistory = true, transform = transform) },
            onUpdateProjectLive = { updated -> applyProjectLive(updated) },
            onPushHistory = { snapshot ->
                history.push(snapshot)
                syncHistoryButtons()
            }
        )

        optionExpandController = OptionExpandController(
            context = this,
            binding = binding,
            canvasStage = binding.canvasStage,
            getProject = { project },
            getSelectedLayer = { getSelectedLayer() },
            pushHistory = { snapshot ->
                history.push(snapshot)
                syncHistoryButtons()
            },
            updateProjectLive = { updated -> applyProjectLive(updated) },
            updateSelectedLayerLive = { updatedLayer ->
                applyProjectLive(
                    project.copy(layers = project.layers.map { if (it.id == updatedLayer.id) updatedLayer else it })
                )
            },
            addLayer = { layer -> addLayer(layer) },
            onPickBgImage = { pickBgImageLauncher.launch("image/*") },
            onPickBgPresetImage = { pickBgPresetImageLauncher.launch("image/*") },
            onPickTextureImage = { pickTextureLauncher.launch("image/*") },
            onRequestImportJson = { cb -> requestImportJson(cb) },
            onRequestExportJson = { name, json -> requestExportJson(name, json) },
            onToolClosed = { renderBottomOptionsStrip() },
            onRestoreSelection = { prevSelId ->
                selectedLayerId = prevSelId
                syncUi()
            }
        )

        setupCanvasStage()
        setupTopBar()
        setupStylesPopup()
        setupTabs()

        syncUi()
    }

    private fun createDefaultPresetProject(): ProjectState {
        return runCatching {
            AssetRepository.loadDefaultPreset(this)
        }.getOrNull() ?: createInitialProject()
    }

    private fun createInitialProject(): ProjectState {
        val defaultText = TextLayer(
            id = "txt_default",
            name = "New Text",
            x = 640f,
            y = 640f,
            text = "New Text",
            fontSize = 112f
        )
        val (w, h) = PixelLabRenderer.measureTextBounds(defaultText)
        return ProjectState(
            id = "proj_default",
            name = "Untitled Design",
            width = 1280,
            height = 1280,
            layers = listOf(defaultText.copy(width = w, height = h))
        )
    }

    private fun setupCanvasStage() {
        binding.canvasStage.project = project
        binding.canvasStage.selectedLayerId = selectedLayerId

        binding.canvasStage.onLayerSelected = { id ->
            hideStylesPopup()
            selectLayer(id)
        }
        binding.canvasStage.onBeforeTransform = {
            hideStylesPopup()
            history.push(project)
            syncHistoryButtons()
        }
        binding.canvasStage.onLayerTransform = { updatedLayer ->
            project = project.copy(
                layers = project.layers.map { if (it.id == updatedLayer.id) updatedLayer else it }
            )
            binding.canvasStage.project = project
        }
        binding.canvasStage.onTransformEnd = {
            saveAutoSave()
            layersDrawerController.refresh()
        }
        binding.canvasStage.onDoubleTapText = { textLayer ->
            hideStylesPopup()
            openTextEditorFor(textLayer)
        }
        binding.canvasStage.onZoomChanged = { pct, _ ->
            binding.zoomIndicatorPill.text = "$pct%"
        }
        binding.btnZoomOut.setOnClickListener {
            hideStylesPopup()
            binding.canvasStage.zoomOut()
        }
        binding.zoomIndicatorPill.setOnClickListener {
            hideStylesPopup()
            binding.canvasStage.zoomTo100()
        }
        binding.btnZoomIn.setOnClickListener {
            hideStylesPopup()
            binding.canvasStage.zoomIn()
        }
        binding.btnZoomFit.setOnClickListener {
            hideStylesPopup()
            binding.canvasStage.resetZoomToFit()
        }
    }

    private fun setupTopBar() {
        binding.btnAdd.setOnClickListener { anchor ->
            hideStylesPopup()
            val menu = PopupMenu(this, anchor)
            menu.menu.add(0, 1, 0, "Text")
            menu.menu.add(0, 2, 1, "Current Date")
            menu.menu.add(0, 3, 2, "Shapes")
            menu.menu.add(0, 4, 3, "From Gallery")
            menu.menu.add(0, 5, 4, "From Camera")
            menu.menu.add(0, 6, 5, "Draw")
            menu.menu.add(0, 7, 6, "Bezier")
            menu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> addNewText("New Text")
                    2 -> {
                        val today = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
                            .format(java.util.Date())
                        addNewText(today)
                    }
                    3 -> optionExpandController.openTool("pick_shape", "Shapes")
                    4 -> {
                        reselectingImageLayerId = null
                        pickMultipleLayerImagesLauncher.launch("image/*")
                    }
                    5 -> launchCamera(forBackground = false)
                    6 -> optionExpandController.openTool("draw", "Draw")
                    7 -> startNewBezier()
                }
                true
            }
            menu.show()
        }

        binding.btnSave.setOnClickListener { anchor ->
            hideStylesPopup()
            val menu = PopupMenu(this, anchor)
            menu.menu.add(0, 1, 0, getString(R.string.save_project))
            menu.menu.add(0, 2, 1, getString(R.string.save_image))
            menu.menu.add(0, 3, 2, getString(R.string.my_projects))
            menu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> Dialogs.showSaveProjectDialog(this, project) { name ->
                        project = project.copy(name = name)
                        if (activeTab == StudioTab.THEMES && !optionExpandController.isOpen()) {
                            renderBottomOptionsStrip()
                        }
                    }
                    2 -> Dialogs.showExportDialog(this, project)
                    3 -> Dialogs.showProjectsDialog(this) { loaded ->
                        optionExpandController.discardOrFinishForExternalAction(commitLiveEdits = false)
                        history.push(project)
                        project = loaded
                        selectedLayerId = loaded.layers.firstOrNull()?.id
                        syncUi()
                    }
                }
                true
            }
            menu.show()
        }

        binding.btnShare.setOnClickListener {
            hideStylesPopup()
            Dialogs.showExportDialog(this, project)
        }

        syncHandToolUi()
        binding.btnHandTool.setOnClickListener {
            hideStylesPopup()
            val next = !binding.canvasStage.zoomModeEnabled
            binding.canvasStage.zoomModeEnabled = next
            syncHandToolUi()
        }

        binding.btnMore.setOnClickListener { anchor ->
            hideStylesPopup()
            val menu = PopupMenu(this, anchor)
            menu.menu.add(0, 1, 0, "Image Size")
            menu.menu.add(0, 2, 1, "Quotes")
            menu.menu.add(0, 3, 2, getString(R.string.save_project))
            menu.menu.add(0, 4, 3, getString(R.string.my_projects))
            menu.menu.add(0, 5, 4, "Fullscreen")
            menu.menu.add(0, 6, 5, "Reset Canvas")
            menu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> Dialogs.showImageSizeDialog(this, project.width, project.height) { nw, nh ->
                        mutateProject(pushToHistory = true) { p -> p.copy(width = nw, height = nh) }
                    }
                    2 -> quotesLauncher.launch(Intent(this, QuotesActivity::class.java))
                    3 -> Dialogs.showSaveProjectDialog(this, project) { name ->
                        project = project.copy(name = name)
                        if (activeTab == StudioTab.THEMES && !optionExpandController.isOpen()) {
                            renderBottomOptionsStrip()
                        }
                    }
                    4 -> Dialogs.showProjectsDialog(this) { loaded ->
                        optionExpandController.discardOrFinishForExternalAction(commitLiveEdits = false)
                        history.push(project)
                        project = loaded
                        selectedLayerId = loaded.layers.firstOrNull()?.id
                        syncUi()
                    }
                    5 -> setFullscreen(true)
                    6 -> {
                        optionExpandController.discardOrFinishForExternalAction(commitLiveEdits = false)
                        val defaultProj = createDefaultPresetProject()
                        mutateProject(pushToHistory = true) { defaultProj }
                        selectedLayerId = project.layers.firstOrNull()?.id
                        activeTab = StudioTab.TEXT
                        binding.canvasStage.zoomModeEnabled = false
                        syncHandToolUi()
                        binding.canvasStage.resetZoom()
                        binding.canvasStage.grid = GridState()
                        binding.btnGrid.alpha = 0.75f
                        binding.layersDrawer.visibility = View.GONE
                        syncUi()
                        saveAutoSave(immediate = true)
                    }
                }
                true
            }
            menu.show()
        }

        binding.btnUndo.setOnClickListener {
            hideStylesPopup()
            optionExpandController.discardOrFinishForExternalAction(commitLiveEdits = true)
            val prev = history.undo(project)
            if (prev != null) {
                project = prev
                if (project.layers.none { it.id == selectedLayerId }) {
                    selectedLayerId = project.layers.lastOrNull()?.id
                }
                syncUi()
                saveAutoSave()
            } else {
                Toast.makeText(this, "Nothing to undo", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnRedo.setOnClickListener {
            hideStylesPopup()
            optionExpandController.discardOrFinishForExternalAction(commitLiveEdits = true)
            val next = history.redo(project)
            if (next != null) {
                project = next
                if (project.layers.none { it.id == selectedLayerId }) {
                    selectedLayerId = project.layers.lastOrNull()?.id
                }
                syncUi()
                saveAutoSave()
            } else {
                Toast.makeText(this, "Nothing to redo", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnGrid.setOnClickListener { anchor ->
            hideStylesPopup()
            val curGrid = binding.canvasStage.grid
            val menu = PopupMenu(this, anchor)
            menu.menu.add(0, 1, 0, if (curGrid.enabled) "Hide Grid" else "Show Grid")
            menu.menu.add(0, 2, 1, if (curGrid.snap) "Disable Magnet Snap" else "Enable Magnet Snap")
            menu.menu.add(0, 3, 2, "3x3 Guides")
            menu.menu.add(0, 4, 3, "4x4 Guides")
            menu.setOnMenuItemClickListener { item ->
                binding.canvasStage.grid = when (item.itemId) {
                    1 -> curGrid.copy(enabled = !curGrid.enabled)
                    2 -> curGrid.copy(snap = !curGrid.snap)
                    3 -> GridState(enabled = true, snap = true, hGuides = listOf(0.333f, 0.666f), vGuides = listOf(0.333f, 0.666f))
                    4 -> GridState(enabled = true, snap = true, hGuides = listOf(0.25f, 0.5f, 0.75f), vGuides = listOf(0.25f, 0.5f, 0.75f))
                    else -> curGrid
                }
                binding.btnGrid.alpha = if (binding.canvasStage.grid.enabled) 1f else 0.75f
                true
            }
            menu.show()
        }

        binding.btnExitFullscreen.setOnClickListener {
            setFullscreen(false)
        }

        binding.btnLayers.setOnClickListener {
            hideStylesPopup()
            layersDrawerController.toggleDrawer()
        }
    }

    private fun setupStylesPopup() {
        binding.btnPopupBrowse.setOnClickListener {
            hideStylesPopup()
            Dialogs.showStylesDialog(
                context = this,
                project = project,
                onApplySavedStyle = { savedStyle ->
                    applySavedTextStyle(savedStyle)
                },
                onApplyAssetStyle = { assetStyle ->
                    applyTemplateTextLayer(assetStyle.template)
                },
                onRequestImportStyles = { onImported ->
                    requestImportJson { raw ->
                        runCatching {
                            val imported = JsonPresetCodec.importStylesJson(raw)
                            if (imported.isEmpty()) {
                                Toast.makeText(this, "No valid styles found in JSON", Toast.LENGTH_SHORT).show()
                            } else {
                                onImported(imported)
                            }
                        }.onFailure {
                            Toast.makeText(this, "Invalid styles JSON file", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onRequestExportStyles = { toExport ->
                    val json = JsonPresetCodec.exportStylesJson(toExport)
                    requestExportJson("pixellab_styles.json", json)
                }
            )
        }

        binding.btnPopupSave.setOnClickListener {
            hideStylesPopup()
            val sel = getSelectedLayer() as? TextLayer ?: return@setOnClickListener
            Dialogs.showConfirmDialog(
                context = this,
                title = "Confirmation :",
                message = "Save the current text as a new style ?"
            ) {
                val style = SavedTextStyle.fromTextLayer(sel)
                JsonPresetCodec.appendMyStyle(this, style)
                Toast.makeText(this, "Saved to My Styles!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleStylesPopup() {
        if (binding.stylesPopup.visibility == View.VISIBLE) {
            hideStylesPopup()
        } else {
            binding.stylesPopup.visibility = View.VISIBLE
            binding.stylesPopup.translationY = dp(18).toFloat()
            binding.stylesPopup.alpha = 0f
            binding.stylesPopup.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(150L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun hideStylesPopup() {
        if (binding.stylesPopup.visibility == View.VISIBLE) {
            binding.stylesPopup.clearAnimation()
            binding.stylesPopup.visibility = View.GONE
        }
    }

    private fun applySavedTextStyle(s: SavedTextStyle) {
        val sel = getSelectedLayer() as? TextLayer ?: return
        mutateProject(pushToHistory = true) { p ->
            val applied = s.applyTo(sel)
            val (nw, nh) = PixelLabRenderer.measureTextBounds(applied, this)
            p.copy(layers = p.layers.map { if (it.id == sel.id) applied.copy(width = nw, height = nh) else it })
        }
    }

    private fun applyTemplateTextLayer(template: TextLayer) {
        val sel = getSelectedLayer() as? TextLayer ?: return
        mutateProject(pushToHistory = true) { p ->
            val applied = sel.copy(
                fontName = template.fontName,
                bold = template.bold,
                italic = template.italic,
                underline = template.underline,
                strikethrough = template.strikethrough,
                letterSpacing = template.letterSpacing,
                fillType = template.fillType,
                color = template.color,
                gradient = template.gradient,
                textureUri = template.textureUri,
                stroke = template.stroke,
                shadow = template.shadow,
                innerShadow = template.innerShadow,
                emboss = template.emboss,
                background = template.background,
                threeD = template.threeD,
                reflection = template.reflection
            )
            val (nw, nh) = PixelLabRenderer.measureTextBounds(applied, this)
            p.copy(layers = p.layers.map { if (it.id == sel.id) applied.copy(width = nw, height = nh) else it })
        }
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val sysBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            if (!isFullscreen) {
                binding.statusBarSpacer.visibility = if (sysBars.top > 0) View.VISIBLE else View.GONE
                binding.statusBarSpacer.updateLayoutParams { height = sysBars.top }
                binding.navBarSpacer.visibility = if (sysBars.bottom > 0) View.VISIBLE else View.GONE
                binding.navBarSpacer.updateLayoutParams { height = sysBars.bottom }
            }
            binding.root.setPadding(sysBars.left, 0, sysBars.right, 0)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun setFullscreen(full: Boolean) {
        isFullscreen = full
        binding.topBar.visibility = if (full) View.GONE else View.VISIBLE
        binding.bottomDockContainer.visibility = if (full) View.GONE else View.VISIBLE
        binding.bottomControls.visibility = if (full) View.GONE else View.VISIBLE
        binding.statusBarSpacer.visibility = if (full) View.GONE else View.VISIBLE
        binding.navBarSpacer.visibility = if (full) View.GONE else View.VISIBLE
        binding.zoomControlPill.visibility = if (full) View.GONE else View.VISIBLE
        binding.btnExitFullscreen.visibility = if (full) View.VISIBLE else View.GONE
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (full) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            hideStylesPopup()
            optionExpandController.closePanel(immediate = true)
            binding.layersDrawer.visibility = View.GONE
        } else {
            binding.bottomDockContainer.visibility = View.VISIBLE
            binding.bottomControls.visibility = View.VISIBLE
            binding.bottomControls.translationY = 0f
            binding.bottomControls.alpha = 1f
            controller.show(WindowInsetsCompat.Type.systemBars())
            ViewCompat.requestApplyInsets(binding.root)
        }
    }

    private fun duplicateSelectedLayer() {
        val sel = getSelectedLayer() ?: return
        val clone = sel.copyBase(
            id = "copy_${System.currentTimeMillis()}",
            name = "${sel.name} Copy",
            x = sel.x + 40f,
            y = sel.y + 40f
        )
        addLayer(clone)
    }

    private fun bringSelectedToFront() {
        val selId = selectedLayerId ?: return
        mutateProject(pushToHistory = true) { p ->
            val target = p.layers.firstOrNull { it.id == selId } ?: return@mutateProject p
            p.copy(layers = p.layers.filterNot { it.id == selId } + target)
        }
    }

    private fun sendSelectedToBack() {
        val selId = selectedLayerId ?: return
        mutateProject(pushToHistory = true) { p ->
            val target = p.layers.firstOrNull { it.id == selId } ?: return@mutateProject p
            p.copy(layers = listOf(target) + p.layers.filterNot { it.id == selId })
        }
    }

    private fun deleteSelectedLayer() {
        val selId = selectedLayerId ?: return
        mutateProject(pushToHistory = true) { p ->
            p.copy(layers = p.layers.filterNot { it.id == selId })
        }
        selectedLayerId = null
        syncUi()
    }

    private fun setupTabs() {
        binding.tabThemes.setOnClickListener { switchTab(StudioTab.THEMES) }
        binding.tabText.setOnClickListener { switchTab(StudioTab.TEXT) }
        binding.tabShapes.setOnClickListener { switchTab(StudioTab.SHAPES) }
        binding.tabBg.setOnClickListener { switchTab(StudioTab.BG) }
        binding.tabEffects.setOnClickListener { switchTab(StudioTab.EFFECTS) }
    }

    private fun switchTab(tab: StudioTab) {
        activeTab = tab
        hideStylesPopup()
        optionExpandController.discardOrFinishForExternalAction(commitLiveEdits = true)
        syncTabHighlight()
        renderBottomOptionsStrip()
    }

    private fun syncTabHighlight() {
        val activeColor = Color.parseColor("#418DC3")
        val inactiveColor = Color.parseColor("#BFBFBF")
        val items = listOf(
            Triple(StudioTab.THEMES, binding.ivTabThemes, binding.tvTabThemes),
            Triple(StudioTab.TEXT, binding.ivTabText, binding.tvTabText),
            Triple(StudioTab.SHAPES, binding.ivTabShapes, binding.tvTabShapes),
            Triple(StudioTab.BG, binding.ivTabBg, binding.tvTabBg),
            Triple(StudioTab.EFFECTS, binding.ivTabEffects, binding.tvTabEffects)
        )
        for ((tab, iv, tv) in items) {
            val selected = tab == activeTab
            val tint = if (selected) activeColor else inactiveColor
            iv.setColorFilter(tint)
            tv.setTextColor(tint)
        }
    }

    private fun renderBottomOptionsStrip() {
        val strip = binding.optionsStrip
        strip.removeAllViews()
        val sel = getSelectedLayer()
        val iconColor = Color.parseColor("#777777")

        val selectableBgRes = TypedValue().apply {
            theme.resolveAttribute(android.R.attr.selectableItemBackground, this, true)
        }.resourceId

        fun addStripAction(
            iconRes: Int,
            label: String,
            toolKey: String? = null,
            selected: Boolean = false,
            action: () -> Unit
        ) {
            val isSelected = selected || (toolKey != null && optionExpandController.activeToolId == toolKey)
            val btn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                minimumWidth = dp(64)
                setPadding(dp(10), dp(4), dp(10), dp(4))
                if (selectableBgRes != 0) {
                    setBackgroundResource(selectableBgRes)
                }
                setOnClickListener {
                    if (toolKey != null && toolKey == optionExpandController.activeToolId) {
                        return@setOnClickListener
                    }
                    if (optionExpandController.isOpen() && toolKey == null) {
                        optionExpandController.discardOrFinishForExternalAction(commitLiveEdits = true)
                    }
                    action()
                    renderBottomOptionsStrip()
                }
            }
            val iconHolder = LinearLayout(this).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                if (isSelected) {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(Color.parseColor("#D8DCE2"))
                    }
                }
            }
            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageResource(iconRes)
                setColorFilter(if (isSelected) Color.parseColor("#444B54") else iconColor)
            }
            iconHolder.addView(iv)
            val tv = TextView(this).apply {
                text = label
                textSize = 10.5f
                isSingleLine = true
                maxLines = 1
                ellipsize = null
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, dp(2), 0, 0)
                setTextColor(if (isSelected) Color.parseColor("#444B54") else iconColor)
            }
            btn.addView(iconHolder)
            btn.addView(tv)
            strip.addView(btn)
        }

        fun addStripDivider() {
            strip.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(1), dp(46)).apply {
                    setMargins(dp(2), dp(8), dp(2), dp(8))
                }
                setBackgroundColor(Color.parseColor("#D4D4D4"))
            })
        }

        when (activeTab) {
            StudioTab.THEMES -> {
                addStripAction(R.drawable.ic_folder, "my projects") {
                    Dialogs.showProjectsDialog(this) { loaded ->
                        history.push(project)
                        project = loaded
                        selectedLayerId = loaded.layers.firstOrNull()?.id
                        optionExpandController.closePanel(immediate = true)
                        syncUi()
                    }
                }
                addStripDivider()

                val projDir = File(filesDir, "projects")
                val savedProjects = projDir.listFiles { f -> f.extension == "plab" }
                    ?.sortedByDescending { it.lastModified() }
                    ?.take(12)
                    ?: emptyList()

                for (plabFile in savedProjects) {
                    val btn = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.MATCH_PARENT
                        )
                        minimumWidth = dp(64)
                        setPadding(dp(6), dp(4), dp(6), dp(4))
                        if (selectableBgRes != 0) setBackgroundResource(selectableBgRes)
                        setOnClickListener {
                            runCatching {
                                val loaded = ProjectState.fromJson(JSONObject(plabFile.readText()))
                                optionExpandController.discardOrFinishForExternalAction(commitLiveEdits = false)
                                history.push(project)
                                project = loaded
                                selectedLayerId = loaded.layers.firstOrNull()?.id
                                syncUi()
                            }.onFailure {
                                Toast.makeText(this@MainActivity, "Failed to open project", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    val ivThumb = ImageView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(56), dp(40))
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(Color.parseColor("#E2E8F0"))
                            cornerRadius = dp(4).toFloat()
                            setStroke(dp(1), Color.parseColor("#CBD5E1"))
                        }
                        clipToOutline = true
                    }
                    val tvName = TextView(this).apply {
                        text = plabFile.nameWithoutExtension
                        textSize = 10f
                        isSingleLine = true
                        maxLines = 1
                        maxWidth = dp(64)
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        gravity = Gravity.CENTER
                        setPadding(0, dp(2), 0, 0)
                        setTextColor(iconColor)
                    }
                    autosaveExecutor.execute {
                        val thumb = Dialogs.loadProjectThumbnail(this@MainActivity, plabFile)
                        val parsedName = runCatching {
                            JSONObject(plabFile.readText()).optString("name").ifBlank { plabFile.nameWithoutExtension }
                        }.getOrDefault(plabFile.nameWithoutExtension)
                        ivThumb.post {
                            if (!isDestroyed && !isFinishing) {
                                if (thumb != null) ivThumb.setImageBitmap(thumb)
                                if (parsedName != plabFile.nameWithoutExtension) tvName.text = parsedName
                            }
                        }
                    }
                    btn.addView(ivThumb)
                    btn.addView(tvName)
                    strip.addView(btn)
                }

                if (savedProjects.isNotEmpty()) {
                    addStripDivider()
                }

                val assetPresets = AssetRepository.loadPresets(this)
                for (preset in assetPresets) {
                    val btn = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.MATCH_PARENT
                        )
                        minimumWidth = dp(64)
                        setPadding(dp(6), dp(4), dp(6), dp(4))
                        if (selectableBgRes != 0) setBackgroundResource(selectableBgRes)
                        setOnClickListener {
                            mutateProject(pushToHistory = true) { p ->
                                p.copy(
                                    width = preset.project.width,
                                    height = preset.project.height,
                                    background = preset.project.background,
                                    effects = preset.project.effects,
                                    layers = preset.project.layers
                                )
                            }
                            selectedLayerId = project.layers.firstOrNull()?.id
                            syncUi()
                        }
                    }
                    val thumb = preset.thumbUri?.let { PixelLabRenderer.loadBitmap(this, it) }
                    if (thumb != null) {
                        btn.addView(ImageView(this).apply {
                            layoutParams = LinearLayout.LayoutParams(dp(56), dp(40))
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            setImageBitmap(thumb)
                        })
                    }
                    btn.addView(TextView(this).apply {
                        text = preset.name
                        textSize = 10f
                        isSingleLine = true
                        maxLines = 1
                        maxWidth = dp(64)
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        gravity = Gravity.CENTER
                        setPadding(0, dp(2), 0, 0)
                        setTextColor(iconColor)
                    })
                    strip.addView(btn)
                }
            }

            StudioTab.TEXT -> {
                if (sel is TextLayer) {
                    addStripAction(R.drawable.ic_styles, "styles", toolKey = "text_styles") { toggleStylesPopup() }
                    addStripDivider()
                    addStripAction(R.drawable.ic_edit, "edit") { hideStylesPopup(); openTextEditorFor(sel) }
                    addStripAction(R.drawable.ic_delete, "delete") { hideStylesPopup(); deleteSelectedLayer() }
                    addStripAction(R.drawable.ic_copy, "copy") { hideStylesPopup(); duplicateSelectedLayer() }
                    addStripAction(R.drawable.ic_to_front, "to front") { hideStylesPopup(); bringSelectedToFront() }
                    addStripAction(R.drawable.ic_to_back, "to back") { hideStylesPopup(); sendSelectedToBack() }
                    addStripAction(R.drawable.ic_position, "position", toolKey = "position") { hideStylesPopup(); optionExpandController.openTool("position", "Position") }
                    addStripAction(R.drawable.ic_rel_position, "relative position", toolKey = "rel_position") { hideStylesPopup(); optionExpandController.openTool("rel_position", "Relative Position") }
                    addStripAction(R.drawable.ic_size, "size", toolKey = "text_size") { hideStylesPopup(); optionExpandController.openTool("text_size", "Text Size") }
                    addStripAction(R.drawable.ic_padding, "padding", toolKey = "text_padding") { hideStylesPopup(); optionExpandController.openTool("text_padding", "Padding") }
                    addStripAction(R.drawable.ic_color_fill, "color", toolKey = "text_color") { hideStylesPopup(); optionExpandController.openTool("text_color", "Text Color") }
                    addStripAction(R.drawable.ic_eyedropper, "eyedropper", toolKey = "eyedropper") { hideStylesPopup(); optionExpandController.openTool("eyedropper", "Eyedropper") }
                    addStripAction(R.drawable.ic_texture, "texture", toolKey = "text_texture") { hideStylesPopup(); optionExpandController.openTool("text_texture", "Texture") }
                    addStripAction(R.drawable.ic_opacity, "opacity", toolKey = "opacity") { hideStylesPopup(); optionExpandController.openTool("opacity", "Opacity") }
                    addStripAction(R.drawable.ic_rotate, "rotate", toolKey = "rotate") { hideStylesPopup(); optionExpandController.openTool("rotate", "Rotate") }
                    addStripAction(R.drawable.ic_mask, "mask", toolKey = "layer_mask") { hideStylesPopup(); optionExpandController.openTool("layer_mask", "Mask") }
                    addStripAction(R.drawable.ic_font, "font") {
                        hideStylesPopup()
                        Dialogs.showFontPickerDialog(this, sel.fontName, sel.text) { chosen ->
                            mutateProject(pushToHistory = true) { p ->
                                p.copy(layers = p.layers.map { l ->
                                    if (l is TextLayer && l.id == sel.id) {
                                        val updated = l.copy(fontName = chosen)
                                        val (nw, nh) = PixelLabRenderer.measureTextBounds(updated, this)
                                        updated.copy(width = nw, height = nh)
                                    } else l
                                })
                            }
                        }
                    }
                    addStripAction(R.drawable.ic_bold, "style", toolKey = "text_style") { hideStylesPopup(); optionExpandController.openTool("text_style", "Font Style") }
                    addStripAction(R.drawable.ic_curve, "curve", toolKey = "text_curve") { hideStylesPopup(); optionExpandController.openTool("text_curve", "Curve") }
                    addStripAction(R.drawable.ic_text_bg, "background", toolKey = "text_bg") { hideStylesPopup(); optionExpandController.openTool("text_bg", "Text Background") }
                    addStripAction(R.drawable.ic_align, "align", toolKey = "text_align") { hideStylesPopup(); optionExpandController.openTool("text_align", "Align") }
                    addStripAction(R.drawable.ic_spacing, "spacing", toolKey = "text_spacing") { hideStylesPopup(); optionExpandController.openTool("text_spacing", "Spacing") }
                    addStripAction(R.drawable.ic_stroke, "stroke", toolKey = "text_stroke") { hideStylesPopup(); optionExpandController.openTool("text_stroke", "Stroke") }
                    addStripAction(R.drawable.ic_shadow, "shadow", toolKey = "text_shadow") { hideStylesPopup(); optionExpandController.openTool("text_shadow", "Shadow") }
                    addStripAction(R.drawable.ic_inner_shadow, "inner shadow", toolKey = "text_inner_shadow") { hideStylesPopup(); optionExpandController.openTool("text_inner_shadow", "Inner Shadow") }
                    addStripAction(R.drawable.ic_emboss, "emboss", toolKey = "text_emboss") { hideStylesPopup(); optionExpandController.openTool("text_emboss", "Emboss") }
                    addStripAction(R.drawable.ic_perspective, "perspective", toolKey = "layer_perspective") { hideStylesPopup(); optionExpandController.openTool("layer_perspective", "Perspective") }
                    addStripAction(R.drawable.ic_3d_rotate, "3d rotate", toolKey = "3d_rotate") { hideStylesPopup(); optionExpandController.openTool("3d_rotate", "3D Rotate") }
                    addStripAction(R.drawable.ic_3d, "3d text", toolKey = "text_3d") { hideStylesPopup(); optionExpandController.openTool("text_3d", "3D Text") }
                    addStripAction(R.drawable.ic_reflection, "reflection", toolKey = "text_reflection") { hideStylesPopup(); optionExpandController.openTool("text_reflection", "Reflection") }
                } else {
                    addStripAction(R.drawable.ic_text, "+ text") { addNewText("New Text") }
                    addStripAction(R.drawable.ic_quotes, "quotes") { quotesLauncher.launch(Intent(this, QuotesActivity::class.java)) }
                }
            }

            StudioTab.SHAPES -> {
                if (sel is ImageLayer) {
                    addStripAction(R.drawable.ic_reselect, "reselect") {
                        reselectingImageLayerId = sel.id
                        pickLayerImageLauncher.launch("image/*")
                    }
                    addStripAction(R.drawable.ic_delete, "delete") { deleteSelectedLayer() }
                    addStripAction(R.drawable.ic_crop_tool, "crop", toolKey = "img_crop") { optionExpandController.openTool("img_crop", "Crop & Flip") }
                    addStripAction(R.drawable.ic_radius, "radius", toolKey = "layer_radius") { optionExpandController.openTool("layer_radius", "Corner Radius") }
                    addStripAction(R.drawable.ic_copy, "copy") { duplicateSelectedLayer() }
                    addStripAction(R.drawable.ic_to_front, "to front") { bringSelectedToFront() }
                    addStripAction(R.drawable.ic_to_back, "to back") { sendSelectedToBack() }
                    addStripAction(R.drawable.ic_position, "position", toolKey = "position") { optionExpandController.openTool("position", "Position") }
                    addStripAction(R.drawable.ic_rel_position, "relative position", toolKey = "rel_position") { optionExpandController.openTool("rel_position", "Relative Position") }
                    addStripAction(R.drawable.ic_rel_size, "relative size", toolKey = "rel_size") { optionExpandController.openTool("rel_size", "Relative Size") }
                    addStripAction(R.drawable.ic_color_fill, "color", toolKey = "shape_color") { optionExpandController.openTool("shape_color", "Color") }
                    addStripAction(R.drawable.ic_opacity, "opacity", toolKey = "opacity") { optionExpandController.openTool("opacity", "Opacity") }
                    addStripAction(R.drawable.ic_rotate, "rotate", toolKey = "rotate") { optionExpandController.openTool("rotate", "Rotate") }
                    addStripAction(R.drawable.ic_filters, "filters", toolKey = "img_filters") { optionExpandController.openTool("img_filters", "Color Filters") }
                    addStripAction(R.drawable.ic_eyedropper, "erase color", toolKey = "img_remove_color") { optionExpandController.openTool("img_remove_color", "Erase Color") }
                    addStripAction(R.drawable.ic_mask, "mask", toolKey = "layer_mask") { optionExpandController.openTool("layer_mask", "Mask") }
                    addStripAction(R.drawable.ic_perspective, "perspective", toolKey = "layer_perspective") { optionExpandController.openTool("layer_perspective", "Perspective") }
                    addStripAction(R.drawable.ic_3d_rotate, "3d rotate", toolKey = "3d_rotate") { optionExpandController.openTool("3d_rotate", "3D Rotate") }
                    addStripAction(R.drawable.ic_stroke, "stroke", toolKey = "shape_stroke") { optionExpandController.openTool("shape_stroke", "Stroke") }
                    addStripAction(R.drawable.ic_shadow, "shadow", toolKey = "shape_shadow") { optionExpandController.openTool("shape_shadow", "Shadow") }
                    addStripAction(R.drawable.ic_inner_shadow, "inner shadow", toolKey = "shape_inner_shadow") { optionExpandController.openTool("shape_inner_shadow", "Inner Shadow") }
                    addStripAction(R.drawable.ic_eraser, "eraser", toolKey = "img_eraser") { optionExpandController.openTool("img_eraser", "Bitmap Eraser") }
                } else if (sel != null && sel !is TextLayer) {
                    if (sel is ShapeLayer) {
                        addStripAction(R.drawable.ic_edit, "edit", toolKey = "pick_shape") { optionExpandController.openTool("pick_shape", "Shapes") }
                        if (sel.shapeLabel == BoundedShapeLabel.RECT) {
                            addStripAction(R.drawable.ic_radius, "radius", toolKey = "layer_radius") { optionExpandController.openTool("layer_radius", "Corner Radius") }
                        }
                    } else if (sel is BezierLayer) {
                        addStripAction(R.drawable.ic_bezier, "edit bezier", toolKey = "bezier_edit") { optionExpandController.openTool("bezier_edit", "Bezier") }
                    } else if (sel is ArrowLayer) {
                        addStripAction(R.drawable.ic_arrow_tool, "edit arrow", toolKey = "arrow_edit") { optionExpandController.openTool("arrow_edit", "Arrow Style") }
                    }
                    addStripAction(R.drawable.ic_copy, "copy") { duplicateSelectedLayer() }
                    addStripAction(R.drawable.ic_to_front, "to front") { bringSelectedToFront() }
                    addStripAction(R.drawable.ic_to_back, "to back") { sendSelectedToBack() }
                    addStripAction(R.drawable.ic_delete, "delete") { deleteSelectedLayer() }
                    addStripAction(R.drawable.ic_position, "position", toolKey = "position") { optionExpandController.openTool("position", "Position") }
                    addStripAction(R.drawable.ic_rel_position, "relative position", toolKey = "rel_position") { optionExpandController.openTool("rel_position", "Relative Position") }
                    addStripAction(R.drawable.ic_rel_size, "relative size", toolKey = "rel_size") { optionExpandController.openTool("rel_size", "Relative Size") }
                    addStripAction(R.drawable.ic_color_fill, "color", toolKey = "shape_color") { optionExpandController.openTool("shape_color", "Color") }
                    if (sel is ShapeLayer) {
                        addStripAction(R.drawable.ic_texture, "texture", toolKey = "shape_texture") { optionExpandController.openTool("shape_texture", "Texture") }
                    }
                    addStripAction(R.drawable.ic_eyedropper, "eyedropper", toolKey = "eyedropper") { optionExpandController.openTool("eyedropper", "Eyedropper") }
                    addStripAction(R.drawable.ic_opacity, "opacity", toolKey = "opacity") { optionExpandController.openTool("opacity", "Opacity") }
                    addStripAction(R.drawable.ic_rotate, "rotate", toolKey = "rotate") { optionExpandController.openTool("rotate", "Rotate") }
                    if (sel is ShapeLayer) {
                        addStripAction(R.drawable.ic_mask, "mask", toolKey = "layer_mask") { optionExpandController.openTool("layer_mask", "Mask") }
                        addStripAction(R.drawable.ic_perspective, "perspective", toolKey = "layer_perspective") { optionExpandController.openTool("layer_perspective", "Perspective") }
                    }
                    addStripAction(R.drawable.ic_3d_rotate, "3d rotate", toolKey = "3d_rotate") { optionExpandController.openTool("3d_rotate", "3D Rotate") }
                    if (sel !is DrawingLayer && sel !is BezierLayer) {
                        addStripAction(R.drawable.ic_stroke, "stroke", toolKey = "shape_stroke") { optionExpandController.openTool("shape_stroke", "Stroke") }
                        addStripAction(R.drawable.ic_shadow, "shadow", toolKey = "shape_shadow") { optionExpandController.openTool("shape_shadow", "Shadow") }
                    }
                    if (sel is ShapeLayer) {
                        addStripAction(R.drawable.ic_inner_shadow, "inner shadow", toolKey = "shape_inner_shadow") { optionExpandController.openTool("shape_inner_shadow", "Inner Shadow") }
                        addStripAction(R.drawable.ic_3d, "3d shape", toolKey = "shape_3d") { optionExpandController.openTool("shape_3d", "3D Shape") }
                    }
                } else {
                    addStripAction(R.drawable.ic_import_image, "import") {
                        reselectingImageLayerId = null
                        pickMultipleLayerImagesLauncher.launch("image/*")
                    }
                    addStripAction(R.drawable.ic_draw, "draw", toolKey = "draw") { optionExpandController.openTool("draw", "Draw") }
                    addStripAction(R.drawable.ic_shapes, "shapes", toolKey = "pick_shape") { optionExpandController.openTool("pick_shape", "Shapes") }
                    addStripAction(R.drawable.ic_bezier, "bezier") { startNewBezier() }
                    addStripAction(R.drawable.ic_arrow_tool, "arrow") {
                        addLayer(
                            ArrowLayer(
                                id = "arrow_${System.currentTimeMillis()}",
                                name = "Arrow",
                                x = project.width / 2f,
                                y = project.height / 2f
                            )
                        )
                    }
                }
            }

            StudioTab.BG -> {
                addStripAction(R.drawable.ic_color_fill, "color", toolKey = "bg_color") { optionExpandController.openTool("bg_color", "Background Color") }
                addStripAction(R.drawable.ic_eyedropper, "eyedropper", toolKey = "eyedropper") { optionExpandController.openTool("eyedropper", "Eyedropper") }
                addStripAction(R.drawable.ic_transparent, "transparent") {
                    mutateProject(pushToHistory = true) { p ->
                        p.copy(
                            background = p.background.copy(type = BackgroundType.TRANSPARENT),
                            effects = p.effects.copy(vignetteEnabled = false)
                        )
                    }
                }
                addStripAction(R.drawable.ic_radius, "radius", toolKey = "bg_radius") { optionExpandController.openTool("bg_radius", "Corner Radius") }
                addStripAction(R.drawable.ic_image_size, "image size") {
                    Dialogs.showImageSizeDialog(this, project.width, project.height) { nw, nh ->
                        mutateProject(pushToHistory = true) { p -> p.copy(width = nw, height = nh) }
                    }
                }
                addStripAction(R.drawable.ic_rotate_bg, "rotate bg") {
                    mutateProject(pushToHistory = true) { p ->
                        val oldW = p.width.toFloat()
                        val oldH = p.height.toFloat()
                        val nw = p.height
                        val nh = p.width
                        val dx = (nw - oldW) / 2f
                        val dy = (nh - oldH) / 2f
                        p.copy(
                            width = nw,
                            height = nh,
                            layers = p.layers.map { l -> l.copyBase(x = l.x + dx, y = l.y + dy) }
                        )
                    }
                }
                addStripAction(R.drawable.ic_bg_presets, "preset bg", toolKey = "bg_presets") { optionExpandController.openTool("bg_presets", "Background Presets") }
                addStripAction(R.drawable.ic_crop_tool, "crop", toolKey = "bg_crop") { optionExpandController.openTool("bg_crop", "Background Image") }
                addStripAction(R.drawable.ic_image, "from gallery") { pickBgImageLauncher.launch("image/*") }
                addStripAction(R.drawable.ic_camera, "from camera") { launchCamera(forBackground = true) }
            }

            StudioTab.EFFECTS -> {
                addStripAction(R.drawable.ic_vignette, "vignette", toolKey = "fx_vignette") { optionExpandController.openTool("fx_vignette", "Vignette") }
                addStripAction(R.drawable.ic_noise, "noise", toolKey = "fx_noise") { optionExpandController.openTool("fx_noise", "Noise") }
                addStripAction(R.drawable.ic_stripes, "stripes", toolKey = "fx_stripes") { optionExpandController.openTool("fx_stripes", "Stripes") }
                addStripAction(R.drawable.ic_brightness, "brightness", toolKey = "fx_brightness") { optionExpandController.openTool("fx_brightness", "Brightness") }
                addStripAction(R.drawable.ic_contrast, "contrast", toolKey = "fx_contrast") { optionExpandController.openTool("fx_contrast", "Contrast") }
                addStripAction(R.drawable.ic_hue, "hue", toolKey = "fx_hue") { optionExpandController.openTool("fx_hue", "Hue") }
                addStripAction(R.drawable.ic_saturation, "saturation", toolKey = "fx_saturation") { optionExpandController.openTool("fx_saturation", "Saturation") }
            }
        }
    }

    private fun startNewBezier() {
        val prevProject = project
        val prevSelectedId = selectedLayerId
        val bz = BezierLayer(
            id = "bezier_${System.currentTimeMillis()}",
            name = "Bezier Curve",
            x = project.width / 2f,
            y = project.height / 2f,
            width = project.width * 0.7f,
            height = project.height * 0.7f
        )
        selectedLayerId = bz.id
        applyProjectLive(project.copy(layers = project.layers + bz))
        syncUi()
        optionExpandController.openTool(
            toolId = "bezier_edit",
            title = "Bezier (Tap to add points)",
            snapshotOverride = prevProject,
            selectedIdOverride = prevSelectedId
        )
    }

    private fun openTextEditorFor(layer: TextLayer) {
        Dialogs.showTextEditorDialog(this, layer.text) { newText ->
            mutateProject(pushToHistory = true) { p ->
                val updated = layer.copy(text = newText, name = newText.take(18))
                val (nw, nh) = PixelLabRenderer.measureTextBounds(updated, this)
                p.copy(layers = p.layers.map { if (it.id == layer.id) updated.copy(width = nw, height = nh) else it })
            }
        }
    }

    private fun addNewText(initial: String) {
        val tl = TextLayer(
            id = "txt_${System.currentTimeMillis()}",
            name = initial.take(18),
            x = project.width / 2f,
            y = project.height / 2f,
            text = initial
        )
        val (nw, nh) = PixelLabRenderer.measureTextBounds(tl, this)
        addLayer(tl.copy(width = nw, height = nh))
    }

    private fun addLayer(layer: CanvasLayer) {
        mutateProject(pushToHistory = true) { p ->
            p.copy(layers = p.layers + layer)
        }
        selectLayer(layer.id)
    }

    private fun selectLayer(id: String?) {
        selectedLayerId = id
        val sel = getSelectedLayer()
        if (sel != null) {
            if (sel is TextLayer && activeTab == StudioTab.SHAPES) {
                activeTab = StudioTab.TEXT
            } else if (sel !is TextLayer && activeTab == StudioTab.TEXT) {
                activeTab = StudioTab.SHAPES
            }
        }
        syncUi()
    }

    private fun getSelectedLayer(): CanvasLayer? {
        return project.layers.firstOrNull { it.id == selectedLayerId }
    }

    private fun mutateProject(pushToHistory: Boolean, transform: (ProjectState) -> ProjectState) {
        if (pushToHistory) {
            history.push(project)
        }
        project = transform(project)
        syncUi()
        saveAutoSave()
    }

    private fun applyProjectLive(updated: ProjectState) {
        project = updated
        binding.canvasStage.project = project
        saveAutoSave()
    }

    private fun syncHandToolUi() {
        val active = binding.canvasStage.zoomModeEnabled
        if (active) {
            binding.btnHandTool.setBackgroundResource(R.drawable.bg_hand_tool_active)
            binding.btnHandTool.alpha = 1f
        } else {
            val outVal = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outVal, true)
            binding.btnHandTool.setBackgroundResource(outVal.resourceId)
            binding.btnHandTool.alpha = 0.85f
        }
    }

    private fun syncHistoryButtons() {
        val canUndo = history.canUndo()
        val canRedo = history.canRedo()
        binding.btnUndo.isEnabled = canUndo
        binding.btnUndo.alpha = if (canUndo) 1f else 0.4f
        binding.btnRedo.isEnabled = canRedo
        binding.btnRedo.alpha = if (canRedo) 1f else 0.4f
    }

    private fun syncUi() {
        binding.canvasStage.project = project
        binding.canvasStage.selectedLayerId = selectedLayerId
        syncHistoryButtons()

        syncTabHighlight()
        if (!optionExpandController.isOpen()) {
            renderBottomOptionsStrip()
        }
        layersDrawerController.refresh()
    }

    private fun resolveUriDisplayName(uri: Uri): String? {
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        cursor.getString(idx)?.substringBeforeLast('.')?.trim()?.takeIf { it.isNotEmpty() }
                    } else null
                } else null
            }
        }.getOrNull()
    }

    private fun copyUriToLocalCache(uri: Uri, prefix: String): String? {
        return runCatching {
            val seq = importCounter.incrementAndGet()
            val file = File(filesDir, "media/${prefix}_${System.currentTimeMillis()}_${seq}.png").apply {
                parentFile?.mkdirs()
            }
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            file.absolutePath
        }.getOrNull()
    }

    private fun readBitmapDimensions(path: String): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        return (opts.outWidth.takeIf { it > 0 } ?: 480) to (opts.outHeight.takeIf { it > 0 } ?: 480)
    }

    private fun normalizeCapturedCameraImage(file: File): String {
        val path = file.absolutePath
        runCatching {
            val exif = android.media.ExifInterface(path)
            val orientation = exif.getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL
            )
            val rot = when (orientation) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (rot != 0f) {
                val src = BitmapFactory.decodeFile(path) ?: return path
                val m = android.graphics.Matrix().apply { postRotate(rot) }
                val rotated = android.graphics.Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
                FileOutputStream(file).use { out ->
                    rotated.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)
                }
                if (rotated != src) rotated.recycle()
                src.recycle()
            }
        }
        return path
    }

    private fun saveAutoSave(immediate: Boolean = false) {
        mainHandler.removeCallbacks(autosaveRunnable)
        if (immediate) {
            val snapshotJson = runCatching { project.toJson().toString() }.getOrNull() ?: return
            runCatching {
                File(filesDir, "autosave.plab").writeText(snapshotJson)
            }
        } else {
            mainHandler.postDelayed(autosaveRunnable, 400L)
        }
    }

    override fun onPause() {
        super.onPause()
        saveAutoSave(immediate = true)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(autosaveRunnable)
        autosaveExecutor.shutdownNow()
        if (::layersDrawerController.isInitialized) {
            layersDrawerController.destroy()
        }
        super.onDestroy()
    }

    private fun loadAutoSaveIfAvailable(): Boolean {
        return runCatching {
            val f = File(filesDir, "autosave.plab")
            if (f.exists()) {
                val restored = ProjectState.fromJson(JSONObject(f.readText()))
                project = restored
                selectedLayerId = restored.layers.lastOrNull()?.id
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }
}
