package com.example.whisper

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class ModelSetup(private val context: Context) {

    companion object {
        private const val TAG = "ModelSetup"

        private const val WHISPER_BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

        private val VOSK_MODELS = mapOf(
            "en" to Pair("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip", "vosk-model-small-en-us-0.15"),
            "cn" to Pair("https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip", "vosk-model-small-cn-0.22"),
            "uk" to Pair("https://alphacephei.com/vosk/models/vosk-model-small-uk-v3-nano.zip", "vosk-model-small-uk-v3-nano"),
            "ru" to Pair("https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip", "vosk-model-small-ru-0.22")
        )
    }

    init {
        createDirectories()
    }

    private fun createDirectories() {
        val whisperModelsDir = File(context.filesDir, "whisper/models")
        val voskModelsDir = File(context.filesDir, "vosk/models")

        whisperModelsDir.mkdirs()
        voskModelsDir.mkdirs()

        Log.d(TAG, "Created directories: whisper=${whisperModelsDir.exists()}, vosk=${voskModelsDir.exists()}")
    }

    fun isWhisperModelExists(modelName: String): Boolean {
        val modelFile = File(context.filesDir, "whisper/models/ggml-$modelName.bin")
        return modelFile.exists()
    }

    fun isVoskModelExists(language: String): Boolean {
        val modelFolder = File(context.filesDir, "vosk/models/$language")
        return modelFolder.exists() && File(modelFolder, "README").exists()
    }

    fun downloadWhisperModel(modelName: String): Boolean {
        val modelFile = File(context.filesDir, "whisper/models/ggml-$modelName.bin")

        if (modelFile.exists()) {
            Log.d(TAG, "Whisper model $modelName already exists")
            return true
        }

        val url = URL("$WHISPER_BASE_URL/ggml-$modelName.bin?download=true")
        Log.d(TAG, "Downloading Whisper model from: $url")

        return try {
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000

            connection.inputStream.use { input ->
                modelFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            Log.d(TAG, "Successfully downloaded Whisper model $modelName")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to download Whisper model $modelName: ${e.message}")
            if (modelFile.exists()) {
                modelFile.delete()
            }
            false
        }
    }

    fun downloadVoskModel(language: String): Boolean {
        if (isVoskModelExists(language)) {
            Log.d(TAG, "Vosk model for language $language already exists")
            return true
        }

        val modelInfo = VOSK_MODELS[language] ?: run {
            Log.e(TAG, "Unsupported language: $language")
            return false
        }

        val url = URL(modelInfo.first)
        val tempZipFile = File(context.filesDir, "vosk/models/temp_${language}.zip")

        Log.d(TAG, "Downloading Vosk model from: $url")

        return try {
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 60000

            connection.inputStream.use { input ->
                tempZipFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            unzipModel(tempZipFile, modelInfo.second, language)
            tempZipFile.delete()

            Log.d(TAG, "Successfully downloaded and extracted Vosk model for $language")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to download/extract Vosk model for $language: ${e.message}")
            if (tempZipFile.exists()) {
                tempZipFile.delete()
            }
            false
        }
    }

    private fun unzipModel(zipFile: File, folderName: String, language: String) {
        val outputDir = File(context.filesDir, "vosk/models")
        val targetDir = File(outputDir, language)

        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }

        outputDir.mkdirs()

        Log.d(TAG, "Extracting model to: ${targetDir.absolutePath}")

        try {
            ZipInputStream(zipFile.inputStream()).use { zis ->
                var entry = zis.nextEntry
                val buffer = ByteArray(1024)

                while (entry != null) {
                    val entryName = entry.name

                    val newPath = if (entryName.startsWith("$folderName/")) {
                        entryName.substring(folderName.length + 1)
                    } else {
                        continue
                    }

                    val newFile = File(targetDir, newPath)

                    if (entry.isDirectory) {
                        newFile.mkdirs()
                    } else {
                        newFile.parentFile?.mkdirs()

                        newFile.outputStream().use { output ->
                            var len: Int
                            while (zis.read(buffer).also { len = it } > 0) {
                                output.write(buffer, 0, len)
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            Log.d(TAG, "Extraction complete. Target directory exists: ${targetDir.exists()}")
        } catch (e: IOException) {
            Log.e(TAG, "Error extracting zip file: ${e.message}")
            throw e
        }
    }
}