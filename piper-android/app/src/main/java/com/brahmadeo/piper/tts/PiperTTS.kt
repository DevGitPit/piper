package com.brahmadeo.piper.tts

import android.util.Log

object PiperTTS {
    private var nativePtr: Long = 0

    init {
        val libs = listOf(
            "c++_shared",
            "android-execinfo",
            "iconv",
            "lzma",
            "zstd",
            "lz4",
            "gpg-error",
            "gcrypt",
            "ogg",
            "FLAC",
            "opus",
            "mp3lame",
            "vorbis",
            "vorbisenc",
            "sndfile",
            "dbus-1",
            "pulsecommon-17.0",
            "pulse",
            "pulse-simple",
            "pcaudio",
            "espeak-ng",
            "onnxruntime",
            "piper_tts"
        )
        for (lib in libs) {
            try {
                System.loadLibrary(lib)
                Log.d("PiperTTS", "Loaded library: $lib")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("PiperTTS", "Failed to load library: $lib - ${e.message}")
            }
        }
    }

    private external fun init(modelPath: String, libPath: String, ortThreads: Int, xnnThreads: Int): Long
    private external fun synthesize(ptr: Long, text: String, lang: String, speed: Float, volume: Float, bufferSeconds: Float): ByteArray
    private external fun getSocClass(ptr: Long): Int
    private external fun getSampleRate(ptr: Long): Int
    private external fun close(ptr: Long)
    private external fun reset(ptr: Long)

    @Synchronized
    fun initialize(modelPath: String, libPath: String, ortThreads: Int = 4, xnnThreads: Int = 1): Boolean {
        Log.d("PiperTTS", "initialize() called with modelPath: $modelPath")
        if (nativePtr != 0L) {
            try {
                if (getSocClass(nativePtr) != -1) {
                    Log.i("PiperTTS", "Engine already initialized and healthy")
                    return true
                }
            } catch (e: Exception) {
                Log.w("PiperTTS", "Health check failed: ${e.message}")
            }
            Log.w("PiperTTS", "Engine pointer exists but is unhealthy. Re-initializing...")
            release()
        }
        
        try {
            nativePtr = init(modelPath, libPath, ortThreads, xnnThreads)
        } catch (e: UnsatisfiedLinkError) {
            Log.e("PiperTTS", "Native method init() not found: ${e.message}")
            return false
        } catch (e: Exception) {
            Log.e("PiperTTS", "Error calling init(): ${e.message}")
            return false
        }
        val success = nativePtr != 0L
        if (success) {
            Log.i("PiperTTS", "Engine initialized successfully (ORT: $ortThreads, XNN: $xnnThreads): $nativePtr")
        } else {
            Log.e("PiperTTS", "Engine initialization FAILED")
        }
        return success
    }

    private var listeners = java.util.concurrent.CopyOnWriteArrayList<ProgressListener>()
    
    @Volatile
    private var currentSessionId: Long = 0
    
    private var currentTaskListener: ProgressListener? = null

    interface ProgressListener {
        fun onProgress(sessionId: Long, current: Int, total: Int)
        fun onAudioChunk(sessionId: Long, data: ByteArray)
    }

    fun addProgressListener(listener: ProgressListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeProgressListener(listener: ProgressListener) {
        listeners.remove(listener)
    }

    // Called from JNI
    fun notifyProgress(current: Int, total: Int) {
        val sid = currentSessionId
        if (currentTaskListener != null) {
            currentTaskListener?.onProgress(sid, current, total)
        } else {
            for (l in listeners) l.onProgress(sid, current, total)
        }
    }

    // Called from JNI
    fun notifyAudioChunk(data: ByteArray) {
        val sid = currentSessionId
        if (currentTaskListener != null) {
            currentTaskListener?.onAudioChunk(sid, data)
        } else {
            for (l in listeners) l.onAudioChunk(sid, data)
        }
    }

    @Volatile
    private var isCancelled = false

    fun setCancelled(cancelled: Boolean) {
        isCancelled = cancelled
    }

    // Called from JNI
    fun isCancelled(): Boolean {
        return isCancelled
    }

    @Synchronized
    fun generateAudio(text: String, lang: String, speed: Float = 1.0f, volume: Float = 1.0f, bufferDuration: Float = 0.0f, listener: ProgressListener? = null): ByteArray? {
        if (nativePtr == 0L) {
            Log.e("PiperTTS", "Engine not initialized")
            return null
        }
        
        currentSessionId++
        currentTaskListener = listener
        
        try {
            val data = synthesize(nativePtr, text, lang, speed, volume, bufferDuration)
            return if (data.isNotEmpty()) data else null
        } catch (e: Exception) {
            Log.e("PiperTTS", "Native synthesis exception: ${e.message}")
            return null
        } finally {
            currentTaskListener = null
        }
    }

    @Synchronized
    fun getSoC(): Int {
        if (nativePtr == 0L) return -1
        return getSocClass(nativePtr)
    }

    @Synchronized
    fun getAudioSampleRate(): Int {
        if (nativePtr == 0L) return 22050
        return getSampleRate(nativePtr)
    }

    @Synchronized
    fun release() {
        if (nativePtr != 0L) {
            Log.i("PiperTTS", "Releasing engine: $nativePtr")
            close(nativePtr)
            nativePtr = 0
        }
    }

    @Synchronized
    fun reset() {
        if (nativePtr != 0L) {
            reset(nativePtr)
        }
    }
}
