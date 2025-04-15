package com.example.whisper

import android.content.Context
import android.util.Log
import com.jaredrummler.ktsh.Shell
import java.io.File

class TranscribeService(private val context: Context) {

    companion object {
        private const val TAG = "TranscribeService"
    }

    fun transcribeWithWhisper(audioPath: String, modelName: String): String {
        Log.d(TAG, "Starting Whisper transcription with model: $modelName")

        val modelPath = "${context.filesDir.absolutePath}/whisper/models/ggml-$modelName.bin"
        if (!File(modelPath).exists()) {
            throw IllegalStateException("Model file does not exist at path: $modelPath")
        }

        if (!File(audioPath).exists()) {
            throw IllegalStateException("Audio file does not exist at path: $audioPath")
        }

        val command = "${context.applicationInfo.nativeLibraryDir}/libwhisper.so " +
                "--model $modelPath " +
                "--file $audioPath " +
                "--language auto " +
                "--no-timestamps " +
                "--no-prints"

        Log.d(TAG, "Executing Whisper command: $command")

        val shell = Shell.SH
        val result = shell.run(command)

        if (!result.isSuccess) {
            throw RuntimeException("Whisper transcription failed: ${result.stderr()}")
        }

        return result.output()
    }

    fun transcribeWithVosk(audioPath: String, languageCode: String): String {
        Log.d(TAG, "Starting Vosk transcription with language: $languageCode")

        val modelPath = "${context.filesDir.absolutePath}/vosk/models/$languageCode"
        if (!File(modelPath).exists()) {
            throw IllegalStateException("Vosk model directory does not exist at path: $modelPath")
        }

        if (!File(audioPath).exists()) {
            throw IllegalStateException("Audio file does not exist at path: $audioPath")
        }

        val command = "${context.applicationInfo.nativeLibraryDir}/libvosk.so $modelPath $audioPath"
        Log.d(TAG, "Executing Vosk command: $command")

        val shell = Shell.SH
        val result = shell.run(command)

        if (!result.isSuccess) {
            throw RuntimeException("Vosk transcription failed: ${result.stderr()}")
        }

        return result.output()
    }
}
