package com.brahmadeo.piper.tts.service

import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import android.content.Context
import com.brahmadeo.piper.tts.PiperTTS
import com.brahmadeo.piper.tts.utils.AssetManager
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale

class PiperTextToSpeechService : TextToSpeechService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var initJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.i("PiperTTS", "Service created")
        com.brahmadeo.piper.tts.utils.LexiconManager.load(this)
        
        initJob = serviceScope.launch(Dispatchers.IO) {
            AssetManager.downloadV1(this@PiperTextToSpeechService) { _, _ -> }
            val prefs = getSharedPreferences("PiperPrefs", android.content.Context.MODE_PRIVATE)
            val voiceFile = prefs.getString("selected_voice", "en_US-lessac-high.onnx") ?: "en_US-lessac-high.onnx"

            val modelPath = AssetManager.getModelPath(this@PiperTextToSpeechService, voiceFile)
            val libPath = applicationInfo.nativeLibraryDir + "/libonnxruntime.so"
            val threads = prefs.getInt("inference_threads", 4)

            PiperTTS.initialize(modelPath, libPath, ortThreads = threads)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val language = lang?.lowercase(Locale.ROOT) ?: return TextToSpeech.LANG_NOT_SUPPORTED
        val prefs = getSharedPreferences("PiperPrefs", android.content.Context.MODE_PRIVATE)
        val selectedLang = prefs.getString("selected_lang", "en") ?: "en"

        val allowedPrefixes = when(selectedLang) {
            "ko" -> listOf("ko", "kor")
            "es" -> listOf("es", "spa")
            "pt" -> listOf("pt", "por")
            "fr" -> listOf("fr", "fra", "fre")
            else -> listOf("en", "eng")
        }

        val isSupported = allowedPrefixes.any { language.startsWith(it) }
        if (!isSupported) return TextToSpeech.LANG_NOT_SUPPORTED

        return if (country != null && country.isNotEmpty()) {
            TextToSpeech.LANG_COUNTRY_AVAILABLE
        } else {
            TextToSpeech.LANG_AVAILABLE
        }
    }

    override fun onGetLanguage(): Array<String> {
        val prefs = getSharedPreferences("PiperPrefs", android.content.Context.MODE_PRIVATE)
        val selectedLang = prefs.getString("selected_lang", "en") ?: "en"
        
        return when(selectedLang) {
            "ko" -> arrayOf("kor", "KOR", "")
            "es" -> arrayOf("spa", "ESP", "")
            "pt" -> arrayOf("por", "PRT", "")
            "fr" -> arrayOf("fra", "FRA", "")
            else -> arrayOf("eng", "USA", "")
        }
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        return onIsLanguageAvailable(lang, country, variant)
    }

    override fun onLoadVoice(voiceName: String?): Int {
        if (voiceName == null) return TextToSpeech.ERROR
        if (voiceName.contains("-piper-")) {
            return TextToSpeech.SUCCESS
        }
        return TextToSpeech.ERROR
    }

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String {
        val prefs = getSharedPreferences("PiperPrefs", android.content.Context.MODE_PRIVATE)
        val selected = prefs.getString("selected_voice", "en_US-lessac-high.onnx") ?: "en_US-lessac-high.onnx"
        val voiceName = selected.removeSuffix(".onnx")
        
        val language = lang?.lowercase(Locale.ROOT) ?: "en"
        val prefix = when {
            language.startsWith("ko") || language.startsWith("kor") -> "ko"
            language.startsWith("es") || language.startsWith("spa") -> "es"
            language.startsWith("pt") || language.startsWith("por") -> "pt"
            language.startsWith("fr") || language.startsWith("fra") || language.startsWith("fre") -> "fr"
            else -> "en"
        }
        return "$prefix-piper-$voiceName"
    }

    override fun onGetVoices(): List<Voice> {
        val prefs = getSharedPreferences("PiperPrefs", android.content.Context.MODE_PRIVATE)
        val selectedLang = prefs.getString("selected_lang", "en") ?: "en"

        val voicesList = mutableListOf<Voice>()
        val locale = when(selectedLang) {
            "ko" -> Locale.KOREA
            "es" -> Locale.forLanguageTag("es-ES")
            "pt" -> Locale.forLanguageTag("pt-PT")
            "fr" -> Locale.FRANCE
            else -> Locale.US
        }

        // Generic labels for system list
        val voiceNames = listOf("Default", "Alternative")
        val langPrefix = locale.language

        voiceNames.forEach { name ->
            voicesList.add(Voice("$langPrefix-piper-$name", locale, Voice.QUALITY_VERY_HIGH, Voice.LATENCY_NORMAL, false, setOf()))
        }

        return voicesList
    }

    override fun onStop() {
        PiperTTS.setCancelled(true)
    }

    private fun normalizeLanguage(lang: String?): String {
        if (lang == null) return "en"
        val l = lang.lowercase(Locale.ROOT)
        return when {
            l.startsWith("en") -> "en"
            l.startsWith("ko") -> "ko"
            l.startsWith("es") -> "es"
            l.startsWith("pt") -> "pt"
            l.startsWith("fr") -> "fr"
            else -> "en"
        }
    }

    private val textNormalizer = com.brahmadeo.piper.tts.utils.TextNormalizer()

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        PiperTTS.setCancelled(false)
        runBlocking {
            withTimeoutOrNull(5000) {
                initJob?.join()
            }
        }
        val rawText = request.charSequenceText?.toString() ?: return
        val effectiveSpeed = (request.speechRate / 100.0f).coerceIn(0.5f, 2.5f)
        
        val prefs = getSharedPreferences("PiperPrefs", android.content.Context.MODE_PRIVATE)
        val voiceFile = prefs.getString("selected_voice", "en_US-lessac-high.onnx") ?: "en_US-lessac-high.onnx"
        val volume = prefs.getFloat("volume", 1.0f)
        val threads = prefs.getInt("inference_threads", 4)

        if (PiperTTS.getSoC() == -1) {
             val modelPath = AssetManager.getModelPath(this, voiceFile)
             val libPath = applicationInfo.nativeLibraryDir + "/libonnxruntime.so"
             PiperTTS.initialize(modelPath, libPath, ortThreads = threads)
        }

        callback.start(PiperTTS.getAudioSampleRate(), android.media.AudioFormat.ENCODING_PCM_16BIT, 1)
        
        try {
            val sentences = textNormalizer.splitIntoSentences(rawText)
            val requestLang = normalizeLanguage(request.language)
            var success = true
            for (sentence in sentences) {
                if (PiperTTS.isCancelled()) { success = false; break }

                val sentenceLang = requestLang
                val normalizedText = textNormalizer.normalize(sentence, sentenceLang)
                val audioData = PiperTTS.generateAudio(normalizedText, sentenceLang, effectiveSpeed, volume, 0.0f, null)
                
                if (audioData != null && audioData.isNotEmpty()) {
                    var offset = 0
                    while (offset < audioData.size) {
                        val length = Math.min(4096, audioData.size - offset)
                        callback.audioAvailable(audioData, offset, length)
                        offset += length
                    }
                }
            }
            if (success) callback.done() else callback.error()
        } catch (e: Exception) {
            callback.error()
        }
    }
}
