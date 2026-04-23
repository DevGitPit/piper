package com.brahmadeo.piper.tts

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import com.brahmadeo.piper.tts.service.IPlaybackListener
import com.brahmadeo.piper.tts.service.IPlaybackService
import com.brahmadeo.piper.tts.service.PlaybackService
import com.brahmadeo.piper.tts.ui.MainScreen
import com.brahmadeo.piper.tts.ui.theme.SupertonicTheme
import com.brahmadeo.piper.tts.utils.HistoryManager
import com.brahmadeo.piper.tts.utils.LexiconManager
import com.brahmadeo.piper.tts.utils.QueueManager
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

import androidx.activity.viewModels
import com.brahmadeo.piper.tts.viewmodel.MainViewModel
import com.brahmadeo.piper.tts.ui.DownloadScreen
import com.brahmadeo.piper.tts.utils.AssetManager
import com.brahmadeo.piper.tts.utils.EbookParser
import com.brahmadeo.piper.tts.utils.EbookManager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var ebookParser: EbookParser

    // Data
    private val languages = mapOf(
        "English" to "en"
    )

    private var currentModelVersion = "v1"

    // Service
    private var playbackService: IPlaybackService? = null
    private var isBound = false

    private val playbackListener = object : IPlaybackListener.Stub() {
        override fun onStateChanged(isPlaying: Boolean, hasContent: Boolean, isSynthesizing: Boolean) {
            runOnUiThread {
                viewModel.miniPlayerIsPlaying.value = isPlaying
                viewModel.isSynthesizing.value = isSynthesizing
                if (hasContent || isSynthesizing) {
                    viewModel.showMiniPlayer.value = true
                    val lastText = getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).getString("last_text", "")
                    if (!lastText.isNullOrEmpty()) {
                        viewModel.miniPlayerTitle.value = lastText
                    }
                } else {
                    viewModel.showMiniPlayer.value = false
                }
            }
        }
        override fun onProgress(current: Int, total: Int) { }
        override fun onPlaybackStopped() {
            runOnUiThread {
                viewModel.showMiniPlayer.value = false
                viewModel.miniPlayerIsPlaying.value = false
            }
        }
        override fun onExportComplete(success: Boolean, path: String) { }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            playbackService = IPlaybackService.Stub.asInterface(service)
            isBound = true
            try {
                playbackService?.setListener(playbackListener)
            } catch (e: Exception) { e.printStackTrace() }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            playbackService = null
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val ebookLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val localPath = EbookManager.importBook(this, it)
            if (localPath != null) {
                val intent = Intent(this, EbookOutlineActivity::class.java).apply {
                    putExtra(EbookOutlineActivity.EXTRA_URI, localPath)
                }
                ebookOutlineLauncher.launch(intent)
            } else {
                Toast.makeText(this, "Failed to import book", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val ebookOutlineLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d("MainActivity", "ebookOutlineLauncher result: ${result.resultCode}")
        if (result.resultCode == RESULT_OK) {
            val text = result.data?.getStringExtra(EbookOutlineActivity.EXTRA_TEXT)
            Log.d("MainActivity", "Received text length: ${text?.length ?: 0}")
            if (!text.isNullOrEmpty()) {
                // Reset state before loading new ebook text
                viewModel.inputText.value = ""
                val stopIntent = Intent(this, PlaybackService::class.java).apply { action = "STOP_PLAYBACK" }
                startService(stopIntent)
                
                viewModel.inputText.value = text
                Toast.makeText(this, "Chapter loaded", Toast.LENGTH_SHORT).show()
            } else {
                Log.e("MainActivity", "Received empty or null text from ebook activity")
            }
        }
    }

    private val historyLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val selectedText = result.data?.getStringExtra("selected_text")
            if (!selectedText.isNullOrEmpty()) {
                viewModel.inputText.value = selectedText
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(this)

        loadPreferences()
        checkNotificationPermission()

        ebookParser = EbookParser(this)
        LexiconManager.load(this)
        QueueManager.initialize(this)

        // Initial setup based on saved language
        viewModel.currentLang.value = "en"
        saveStringPref("selected_lang", "en")
        currentModelVersion = "v1"
        prepareBundledEnglishModel()

        handleIntent(intent)
        checkResumeState()

        setContent {
            SupertonicTheme {
                run {
                    if (viewModel.showQueueDialog.value) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { viewModel.showQueueDialog.value = false },
                            title = { Text(getString(R.string.playback_active_title)) },
                            text = { Text(getString(R.string.playback_active_message)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    addToQueue(viewModel.queueDialogText)
                                    viewModel.showQueueDialog.value = false
                                }) { Text(getString(R.string.add_to_queue)) }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    playNow(viewModel.queueDialogText)
                                    viewModel.showQueueDialog.value = false
                                }) { Text(getString(R.string.play_now)) }
                            }
                        )
                    }

                    // Get localized placeholder
                    val placeholder = getLocalizedResource(this, viewModel.currentLang.value, R.string.default_input_text)

                    MainScreen(
                        inputText = viewModel.inputText.value,
                        onInputTextChange = { viewModel.inputText.value = it },
                        placeholderText = placeholder,
                        isInitializing = viewModel.isInitializing.value,
                        isSynthesizing = viewModel.isSynthesizing.value,
                        onSynthesizeClick = {
                            val textToPlay = viewModel.inputText.value.ifEmpty { placeholder }
                            generateAndPlay(textToPlay)
                        },

                        languages = languages,
                        currentLangCode = viewModel.currentLang.value,
                        onLangChange = {
                            viewModel.currentLang.value = "en"
                            saveStringPref("selected_lang", "en")
                            currentModelVersion = "v1"
                        },

                        voices = viewModel.voiceFiles,
                        selectedVoiceFile = viewModel.selectedVoiceFile.value,
                        onVoiceChange = {
                            if (viewModel.selectedVoiceFile.value != it) {
                                viewModel.selectedVoiceFile.value = it
                                saveStringPref("selected_voice", it)
                                val resetIntent = Intent(this, PlaybackService::class.java).apply { action = "RESET_ENGINE" }
                                startService(resetIntent)
                            }
                        },

                        isMixingEnabled = viewModel.isMixingEnabled.value,
                        onMixingEnabledChange = { 
                            viewModel.isMixingEnabled.value = it
                            getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).edit().putBoolean("is_mixing_enabled", it).apply()
                        },
                        selectedVoiceFile2 = viewModel.selectedVoiceFile2.value,
                        onVoice2Change = {
                            viewModel.selectedVoiceFile2.value = it
                            saveStringPref("selected_voice_2", it)
                        },
                        mixAlpha = viewModel.mixAlpha.value,
                        onMixAlphaChange = { 
                            viewModel.mixAlpha.value = it
                            getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).edit().putFloat("mix_alpha", it).apply()
                        },

                        speed = viewModel.currentSpeed.value,
                        onSpeedChange = { viewModel.currentSpeed.value = it },
                        steps = viewModel.currentSteps.value,
                        onStepsChange = {
                            viewModel.currentSteps.value = it
                            getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).edit().putInt("diffusion_steps", it).apply()
                        },

                        onResetClick = {
                            viewModel.inputText.value = ""
                            val stopIntent = Intent(this, PlaybackService::class.java).apply { action = "STOP_PLAYBACK" }
                            startService(stopIntent)
                        },
                        onSavedAudioClick = { startActivity(Intent(this, SavedAudioActivity::class.java)) },
                        onHistoryClick = { historyLauncher.launch(Intent(this, HistoryActivity::class.java)) },
                        onQueueClick = { startActivity(Intent(this, QueueActivity::class.java)) },
                        onLexiconClick = { startActivity(Intent(this, LexiconActivity::class.java)) },
                        onDeleteV2Click = { },
                        onOpenEbookClick = { 
                            try {
                                if (EbookManager.getRecentBooks(this).isEmpty()) {
                                    ebookLauncher.launch(arrayOf("application/epub+zip", "application/pdf"))
                                } else {
                                    val intent = Intent(this, EbookLibraryActivity::class.java)
                                    ebookOutlineLauncher.launch(intent)
                                }
                            } catch (e: Exception) {
                                Log.e("MainActivity", "Failed to open ebook library", e)
                                ebookLauncher.launch(arrayOf("application/epub+zip", "application/pdf"))
                            }
                        },
                        isV2Ready = false,

                        showMiniPlayer = viewModel.showMiniPlayer.value,
                        miniPlayerTitle = viewModel.miniPlayerTitle.value,
                        miniPlayerIsPlaying = viewModel.miniPlayerIsPlaying.value,
                        onMiniPlayerClick = {
                            val intent = Intent(this, PlaybackActivity::class.java)
                            intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            startActivity(intent)
                        },
                        onMiniPlayerPlayPauseClick = {
                             if (playbackService?.isServiceActive == true) {
                                try {
                                    if (viewModel.miniPlayerIsPlaying.value) playbackService?.pause() else playbackService?.play()
                                } catch (e: Exception) { e.printStackTrace() }
                            }
                        }
                    )
                }
            }
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
        viewModel.currentLang.value = "en"
        viewModel.selectedVoiceFile.value = prefs.getString("selected_voice", MainViewModel.DEFAULT_VOICE) ?: MainViewModel.DEFAULT_VOICE
        viewModel.selectedVoiceFile2.value = prefs.getString("selected_voice_2", MainViewModel.DEFAULT_VOICE_2) ?: MainViewModel.DEFAULT_VOICE_2
        viewModel.isMixingEnabled.value = prefs.getBoolean("is_mixing_enabled", false)
        viewModel.mixAlpha.value = prefs.getFloat("mix_alpha", 0.5f)
        viewModel.currentSpeed.value = prefs.getFloat("speed", MainViewModel.DEFAULT_SPEED) ?: MainViewModel.DEFAULT_SPEED
        viewModel.currentSteps.value = prefs.getInt("diffusion_steps", MainViewModel.DEFAULT_STEPS)
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun getLocalizedResource(context: Context, lang: String, resId: Int): String {
        val locale = java.util.Locale.forLanguageTag(lang)
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(locale)
        val localizedContext = context.createConfigurationContext(config)
        return localizedContext.resources.getString(resId)
    }

    private fun saveStringPref(key: String, value: String) {
        getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).edit()
            .putString(key, value)
            .apply()
    }

    private fun startDownload(version: String) {
        if (version != "v1") {
            Toast.makeText(this, "Only bundled English Piper is enabled in this build.", Toast.LENGTH_SHORT).show()
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AssetManager.downloadV1(this@MainActivity) { _, _ -> }
                withContext(Dispatchers.Main) {
                    initializeEngine(version)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e("MainActivity", "Download failed", e)
                    Toast.makeText(this@MainActivity, e.message ?: "Asset setup failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun prepareBundledEnglishModel() {
        if (!AssetManager.isV1Ready(this)) {
            startDownload("v1")
        } else {
            viewModel.isInitializing.value = false
        }
    }

    private fun initializeEngine(version: String) {
        currentModelVersion = version
        viewModel.isInitializing.value = true
        
        CoroutineScope(Dispatchers.IO).launch {
            withContext(Dispatchers.Main) {
                setupVoicesMap(version)
            }
            
            // Force release of any existing engine to ensure we load the new model path
            SupertonicTTS.release()
            
            val modelPath = AssetManager.getModelPath(this@MainActivity, version)
            val libPath = applicationInfo.nativeLibraryDir + "/libonnxruntime.so"

            if (SupertonicTTS.initialize(modelPath, libPath)) {
                withContext(Dispatchers.Main) {
                    viewModel.isInitializing.value = false
                }
            } else {
                withContext(Dispatchers.Main) {
                    viewModel.isInitializing.value = false
                    Toast.makeText(this@MainActivity, "Engine failed to initialize", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun switchModel(version: String) {
        if (version != "v1") {
            Toast.makeText(this, "Only bundled English Piper is enabled in this build.", Toast.LENGTH_SHORT).show()
            return
        }
        if (currentModelVersion == version && !viewModel.isInitializing.value) return
        prepareBundledEnglishModel()
    }

    private fun setupVoicesMap(version: String) {
        val voiceResources = mapOf(
            "M1.json" to R.string.voice_m1,
            "M2.json" to R.string.voice_m2,
            "M3.json" to R.string.voice_m3,
            "M4.json" to R.string.voice_m4,
            "M5.json" to R.string.voice_m5,
            "F1.json" to R.string.voice_f1,
            "F2.json" to R.string.voice_f2,
            "F3.json" to R.string.voice_f3,
            "F4.json" to R.string.voice_f4,
            "F5.json" to R.string.voice_f5
        )

        voiceResources.forEach { (filename, resId) ->
            viewModel.voiceFiles[getString(resId)] = filename
        }

        // Check dynamic dir for default listing
        val voiceDir = File(filesDir, "$version/voice_styles")
        if (voiceDir.exists()) {
            val files = voiceDir.listFiles { _, name -> name.endsWith(".json") }
            files?.forEach { file ->
                if (!voiceResources.containsKey(file.name)) {
                    val friendlyName = file.name.removeSuffix(".json")
                    viewModel.voiceFiles[friendlyName] = file.name
                }
            }
        }
    }

    private fun generateAndPlay(text: String) {
        val isReady = if (currentModelVersion == "v1") AssetManager.isV1Ready(this) else AssetManager.isV2Ready(this)
        if (!isReady) {
            startDownload(currentModelVersion)
            return
        }

        if (viewModel.isInitializing.value) return

        val v1Name = viewModel.voiceFiles.entries.find { it.value == viewModel.selectedVoiceFile.value }?.key ?: "Voice 1"
        val v2Name = viewModel.voiceFiles.entries.find { it.value == viewModel.selectedVoiceFile2.value }?.key ?: "Voice 2"
        val voiceName = if (viewModel.isMixingEnabled.value) "Mixed: $v1Name + $v2Name" else v1Name

        HistoryManager.saveItem(this, text, voiceName)

        try {
            if (playbackService?.isServiceActive == true) {
                viewModel.queueDialogText = text
                viewModel.showQueueDialog.value = true
            } else {
                launchPlaybackActivity(text)
            }
        } catch (e: Exception) {
            launchPlaybackActivity(text)
        }
    }

    private fun addToQueue(text: String) {
        val isReady = if (currentModelVersion == "v1") AssetManager.isV1Ready(this) else AssetManager.isV2Ready(this)
        if (!isReady) {
            startDownload(currentModelVersion)
            return
        }

        if (viewModel.isInitializing.value) return

        try {
            playbackService?.addToQueue(
                text,
                viewModel.currentLang.value,
                viewModel.currentSpeed.value,
                0
            )
            Toast.makeText(this, getString(R.string.added_to_queue), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun playNow(text: String) {
        val isReady = if (currentModelVersion == "v1") AssetManager.isV1Ready(this) else AssetManager.isV2Ready(this)
        if (!isReady) {
            startDownload(currentModelVersion)
            return
        }

        if (viewModel.isInitializing.value) return

        launchPlaybackActivity(text)
    }

    private fun launchPlaybackActivity(text: String) {
        val intent = Intent(this, PlaybackActivity::class.java).apply {
            putExtra(PlaybackActivity.EXTRA_TEXT, text)
            putExtra(PlaybackActivity.EXTRA_SPEED, viewModel.currentSpeed.value)
            putExtra(PlaybackActivity.EXTRA_LANG, viewModel.currentLang.value)
        }
        startActivity(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrEmpty()) {
                viewModel.inputText.value = sharedText
            }
        } else {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.data?.getQueryParameter("text")
            if (!text.isNullOrEmpty()) {
                viewModel.inputText.value = text
            }
        }
    }

    private fun checkResumeState() {
        if (viewModel.isDownloading.value) return

        val prefs = getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
        val lastText = prefs.getString("last_text", null)
        val isPlaying = prefs.getBoolean("is_playing", false)

        if (!lastText.isNullOrEmpty() && isPlaying) {
             AlertDialog.Builder(this)
                .setTitle(getString(R.string.resume_title))
                .setMessage(getString(R.string.resume_message))
                .setPositiveButton(getString(R.string.yes)) { _, _ ->
                    val intent = Intent(this, PlaybackActivity::class.java)
                    intent.putExtra("is_resume", true)
                    startActivity(intent)
                }
                .setNegativeButton(getString(R.string.no)) { _, _ ->
                    getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).edit()
                        .putBoolean("is_playing", false)
                        .apply()
                    val stopIntent = Intent(this, PlaybackService::class.java)
                    stopIntent.action = "STOP_PLAYBACK"
                    startService(stopIntent)
                }
                .show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}
