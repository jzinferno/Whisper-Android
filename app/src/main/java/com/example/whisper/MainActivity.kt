package com.example.whisper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "WhisperApp"
        private const val PERMISSION_REQUEST_CODE = 123
        private const val MANAGE_EXTERNAL_STORAGE_REQUEST_CODE = 456
    }

    private lateinit var modelRadioGroup: RadioGroup
    private lateinit var downloadModelButton: Button
    private lateinit var selectedFileTextView: TextView
    private lateinit var selectFileButton: Button
    private lateinit var transcribeButton: Button
    private lateinit var resultTextView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var languageRadioGroup: RadioGroup
    private lateinit var downloadVoskModelButton: Button
    private lateinit var transcribeButtonVosk: Button

    private lateinit var modelSetup: ModelSetup
    private lateinit var transcribeService: TranscribeService

    private var selectedAudioPath: String? = null
    private var selectedModel: String = "tiny"
    private var isModelDownloaded = false
    private var selectedLanguage: String = "ru"
    private var isVoskModelDownloaded = false

    private val selectAudioFileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                selectedAudioPath = getRealPathFromURI(uri)
                selectedFileTextView.text = selectedAudioPath ?: getString(R.string.invalid_file)
                updateTranscribeButtonState()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeServices()
        bindViews()
        setupListeners()
        checkExistingModels()
    }

    private fun initializeServices() {
        modelSetup = ModelSetup(applicationContext)
        transcribeService = TranscribeService(applicationContext)
    }

    private fun bindViews() {
        modelRadioGroup = findViewById(R.id.modelRadioGroup)
        downloadModelButton = findViewById(R.id.downloadModelButton)
        selectedFileTextView = findViewById(R.id.selectedFileTextView)
        selectFileButton = findViewById(R.id.selectFileButton)
        transcribeButton = findViewById(R.id.transcribeButton)
        resultTextView = findViewById(R.id.resultTextView)
        progressBar = findViewById(R.id.progressBar)
        languageRadioGroup = findViewById(R.id.languageRadioGroup)
        downloadVoskModelButton = findViewById(R.id.downloadVoskModelButton)
        transcribeButtonVosk = findViewById(R.id.transcribeButtonVosk)
    }

    private fun setupListeners() {
        modelRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val newSelectedModel = when (checkedId) {
                R.id.modelTinyRadioButton -> "tiny"
                R.id.modelBaseRadioButton -> "base"
                R.id.modelSmallRadioButton -> "small"
                else -> "tiny"
            }
            selectedModel = newSelectedModel
            isModelDownloaded = modelSetup.isWhisperModelExists(selectedModel)
            updateTranscribeButtonState()
        }

        languageRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val newSelectedLanguage = when (checkedId) {
                R.id.languageRuRadioButton -> "ru"
                R.id.languageEnRadioButton -> "en"
                R.id.languageCnRadioButton -> "cn"
                R.id.languageUkRadioButton -> "uk"
                else -> "ru"
            }
            selectedLanguage = newSelectedLanguage
            isVoskModelDownloaded = modelSetup.isVoskModelExists(selectedLanguage)
            updateTranscribeButtonState()
        }

        downloadModelButton.setOnClickListener {
            checkAndRequestPermissions()
            downloadWhisperModel()
        }

        downloadVoskModelButton.setOnClickListener {
            checkAndRequestPermissions()
            downloadVoskModel()
        }

        selectFileButton.setOnClickListener {
            checkAndRequestPermissions()
            openFilePicker()
        }

        transcribeButton.setOnClickListener {
            transcribeWithWhisper()
        }

        transcribeButtonVosk.setOnClickListener {
            transcribeWithVosk()
        }
    }

    private fun checkExistingModels() {
        isModelDownloaded = modelSetup.isWhisperModelExists(selectedModel)
        isVoskModelDownloaded = modelSetup.isVoskModelExists(selectedLanguage)
        updateTranscribeButtonState()
    }

    private fun downloadWhisperModel() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                setDownloadingState(true)
                resultTextView.text = getString(R.string.downloading_model, selectedModel)

                val success = withContext(Dispatchers.IO) {
                    modelSetup.downloadWhisperModel(selectedModel)
                }

                handleDownloadResult(success, "Whisper", selectedModel)
                isModelDownloaded = success
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading Whisper model: ${e.message}")
                resultTextView.text = getString(R.string.error_downloading_model, e.message)
            } finally {
                setDownloadingState(false)
                updateTranscribeButtonState()
            }
        }
    }

    private fun downloadVoskModel() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                setDownloadingState(true)
                resultTextView.text = getString(R.string.downloading_vosk_model, selectedLanguage)

                val success = withContext(Dispatchers.IO) {
                    modelSetup.downloadVoskModel(selectedLanguage)
                }

                handleDownloadResult(success, "Vosk", selectedLanguage)
                isVoskModelDownloaded = success
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading Vosk model: ${e.message}")
                resultTextView.text = getString(R.string.error_downloading_vosk_model, e.message)
            } finally {
                setDownloadingState(false)
                updateTranscribeButtonState()
            }
        }
    }

    private fun handleDownloadResult(success: Boolean, modelType: String, modelName: String) {
        if (success) {
            Toast.makeText(
                this@MainActivity,
                "$modelType model for $modelName downloaded successfully",
                Toast.LENGTH_SHORT
            ).show()
            resultTextView.text = getString(R.string.model_downloaded_success, modelType, modelName)
        } else {
            resultTextView.text = getString(R.string.model_download_failed, modelType, modelName)
        }
    }

    private fun setDownloadingState(isDownloading: Boolean) {
        progressBar.visibility = if (isDownloading) View.VISIBLE else View.GONE
        downloadModelButton.isEnabled = !isDownloading
        downloadVoskModelButton.isEnabled = !isDownloading
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, "/storage/emulated/0".toUri())
            }
        }
        selectAudioFileLauncher.launch(intent)
    }

    private fun getRealPathFromURI(uri: Uri): String? {
        val mimeType = contentResolver.getType(uri)

        if (mimeType == null || !mimeType.startsWith("audio/")) {
            Toast.makeText(this, R.string.select_audio_file, Toast.LENGTH_SHORT).show()
            return null
        }

        return try {
            val inputStream = contentResolver.openInputStream(uri)

            val extension = when {
                mimeType.contains("wav") -> "wav"
                mimeType.contains("mp3") -> "mp3"
                mimeType.contains("aac") -> "aac"
                mimeType.contains("ogg") -> "ogg"
                mimeType.contains("flac") -> "flac"
                mimeType.contains("m4a") -> "m4a"
                else -> "audio"
            }
            
            val tempFile = File(cacheDir, "temp_audio.$extension")

            FileOutputStream(tempFile).use { fileOut ->
                inputStream?.copyTo(fileOut)
            }

            inputStream?.close()

            val displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "audio.$extension"
            Log.d(TAG, "Selected audio file: $displayName (path: ${tempFile.absolutePath})")
            
            tempFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error processing file: ${e.message}")
            null
        }
    }

    private fun updateTranscribeButtonState() {
        transcribeButton.isEnabled = isModelDownloaded && selectedAudioPath != null
        transcribeButtonVosk.isEnabled = isVoskModelDownloaded && selectedAudioPath != null
    }

    private fun transcribeWithWhisper() {
        if (selectedAudioPath == null || !isModelDownloaded) {
            Toast.makeText(this, R.string.select_audio_and_model, Toast.LENGTH_SHORT).show()
            return
        }

        performTranscription(TranscriptionType.WHISPER)
    }

    private fun transcribeWithVosk() {
        if (selectedAudioPath == null || !isVoskModelDownloaded) {
            Toast.makeText(this, R.string.select_audio_and_vosk_model, Toast.LENGTH_SHORT).show()
            return
        }

        performTranscription(TranscriptionType.VOSK)
    }

    private fun performTranscription(type: TranscriptionType) {
        val buttonToDisable = if (type == TranscriptionType.WHISPER) transcribeButton else transcribeButtonVosk

        CoroutineScope(Dispatchers.Main).launch {
            try {
                progressBar.visibility = View.VISIBLE
                buttonToDisable.isEnabled = false
                resultTextView.text = getString(
                    if (type == TranscriptionType.WHISPER)
                        R.string.transcribing
                    else
                        R.string.transcribing_vosk
                )

                val transcription = withContext(Dispatchers.IO) {
                    when (type) {
                        TranscriptionType.WHISPER ->
                            transcribeService.transcribeWithWhisper(selectedAudioPath!!, selectedModel)
                        TranscriptionType.VOSK ->
                            transcribeService.transcribeWithVosk(selectedAudioPath!!, selectedLanguage)
                    }
                }

                resultTextView.text = transcription.replace("\n", " ").replace("\\s+", " ").trim()
            } catch (e: Exception) {
                Log.e(TAG, "Error transcribing audio: ${e.message}")
                resultTextView.text = getString(R.string.error, e.message)
            } finally {
                progressBar.visibility = View.GONE
                buttonToDisable.isEnabled = true
            }
        }
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = "package:$packageName".toUri()
                }
                startActivityForResult(intent, MANAGE_EXTERNAL_STORAGE_REQUEST_CODE)
            }
        } else {
            val requiredPermissions = mutableListOf<String>()

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }

            if (requiredPermissions.isNotEmpty()) {
                ActivityCompat.requestPermissions(
                    this,
                    requiredPermissions.toTypedArray(),
                    PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, R.string.permissions_granted, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.permissions_denied, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == MANAGE_EXTERNAL_STORAGE_REQUEST_CODE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                Toast.makeText(this, R.string.all_files_access_granted, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.all_files_access_denied, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

enum class TranscriptionType {
    WHISPER, VOSK
}
