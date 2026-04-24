package com.brahmadeo.piper.tts.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object AssetManager {
    private const val PIPER_DIR = "piper"
    private const val ESPEAK_DIR = "espeak-ng-data"

    fun isV1Ready(context: Context): Boolean {
        val root = File(context.filesDir, PIPER_DIR)
        // Check if at least one model exists and espeak data
        val models = root.listFiles { _, name -> name.endsWith(".onnx") }
        return !models.isNullOrEmpty() && File(root, ESPEAK_DIR).isDirectory
    }

    fun isV2Ready(context: Context): Boolean = false

    suspend fun downloadV1(context: Context, onProgress: (String, Float) -> Unit) {
        withContext(Dispatchers.IO) {
            onProgress("Preparing bundled Piper models...", 0.1f)
            ensureBundledEnglishAssets(context)
            onProgress("Ready", 1.0f)
        }
    }

    suspend fun downloadV2(context: Context, onProgress: (String, Float) -> Unit) {
        withContext(Dispatchers.IO) {
            onProgress("Multilingual models are not bundled in this build.", 0f)
            throw IOException("Multilingual models are not bundled in this build.")
        }
    }

    fun deleteVersion(context: Context, version: String) {
        if (version == "v1") {
            File(context.filesDir, PIPER_DIR).deleteRecursively()
        }
    }

    fun getModelPath(context: Context, voiceFile: String): String {
        return File(context.filesDir, "$PIPER_DIR/$voiceFile").absolutePath
    }

    private fun ensureBundledEnglishAssets(context: Context) {
        val targetRoot = File(context.filesDir, PIPER_DIR)
        targetRoot.mkdirs()
        
        // Copy all models from onnx assets
        val assets = context.assets.list("onnx") ?: emptyArray()
        for (asset in assets) {
            copyAssetIfMissing(context, "onnx/$asset", File(targetRoot, asset))
        }
        
        copyAssetDir(context, ESPEAK_DIR, File(targetRoot, ESPEAK_DIR))
    }

    private fun copyAssetIfMissing(context: Context, assetPath: String, targetFile: File) {
        if (targetFile.exists() && targetFile.length() > 0L) return
        targetFile.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun copyAssetDir(context: Context, assetPath: String, targetDir: File) {
        val children = context.assets.list(assetPath) ?: return
        if (children.isEmpty()) {
            copyAssetIfMissing(context, assetPath, targetDir)
            return
        }

        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        for (child in children) {
            val childAssetPath = "$assetPath/$child"
            val childTarget = File(targetDir, child)
            val nested = context.assets.list(childAssetPath) ?: emptyArray()
            if (nested.isEmpty()) {
                copyAssetIfMissing(context, childAssetPath, childTarget)
            } else {
                copyAssetDir(context, childAssetPath, childTarget)
            }
        }
    }
}
