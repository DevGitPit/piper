package com.brahmadeo.piper.tts.viewmodel

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel

class MainViewModel : ViewModel() {
    // UI State
    var inputText = mutableStateOf("")
    var isInitializing = mutableStateOf(true)
    var isSynthesizing = mutableStateOf(false)

    // Settings State
    var currentLang = mutableStateOf(DEFAULT_LANG)
    var selectedVoiceFile = mutableStateOf(DEFAULT_VOICE)
    var currentSpeed = mutableFloatStateOf(1.0f)
    var currentVolume = mutableFloatStateOf(1.0f)
    var currentThreads = mutableIntStateOf(4)

    // Mini Player State
    var showMiniPlayer = mutableStateOf(false)
    var miniPlayerTitle = mutableStateOf("Now Playing")
    var miniPlayerIsPlaying = mutableStateOf(false)

    // Asset Download State
    var isDownloading = mutableStateOf(false)
    var downloadingVersion = mutableStateOf("v1")
    var downloadProgress = mutableFloatStateOf(0f)
    var downloadStatus = mutableStateOf("Checking assets...")
    var downloadError = mutableStateOf<String?>(null)

    // Dialog State
    var showQueueDialog = mutableStateOf(false)
    var queueDialogText = ""
    var showV2ConfirmDialog = mutableStateOf(false)
    var showV2DeleteDialog = mutableStateOf(false)
    var pendingLangCode = ""

    // Data
    val voiceFiles = mutableStateMapOf<String, String>()

    companion object {
        const val DEFAULT_VOICE = "en_US-lessac-high.onnx"
        const val DEFAULT_LANG = "en"
        const val DEFAULT_SPEED = 1.0f
        const val DEFAULT_THREADS = 4
    }
}