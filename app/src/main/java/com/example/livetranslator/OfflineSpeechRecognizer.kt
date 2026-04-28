package com.example.livetranslator

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class OfflineSpeechRecognizer(
    private val context: Context,
    private val onResult: (String, Boolean) -> Unit,
    private val onError: (String) -> Unit,
    private val onListeningStart: () -> Unit,
    private val onProgress: (DownloadProgress) -> Unit = {}
) {
    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var recognizer: Recognizer? = null
    private var isInitialized = false
    private var isListening = false
    private var currentLanguage = "zh"
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    
    private fun runOnMain(block: () -> Unit) {
        mainHandler.post(block)
    }

    data class DownloadProgress(
        val state: State,
        val languageCode: String = "",
        val progress: Int = 0,           // 0-100
        val downloadedMB: Float = 0f,
        val totalMB: Float = 0f,
        val speedKBps: Float = 0f,
        val etaSeconds: Int = 0,
        val error: String = ""
    ) {
        enum class State {
            IDLE, CHECKING, DOWNLOADING, EXTRACTING, LOADING, READY, ERROR
        }
    }

    companion object {
        private const val TAG = "OfflineSpeechRecognizer"
        
        // Vosk 模型信息
        data class ModelInfo(
            val url: String,
            val name: String,
            val displayName: String,
            val sizeMB: Float
        )
        
        val MODELS = mapOf(
            "zh" to ModelInfo(
                "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip",
                "vosk-model-small-cn-0.22",
                "中文",
                42f
            ),
            "en" to ModelInfo(
                "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
                "vosk-model-small-en-us-0.15",
                "English",
                40f
            ),
            "ja" to ModelInfo(
                "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip",
                "vosk-model-small-ja-0.22",
                "日本語",
                45f
            ),
            "ko" to ModelInfo(
                "https://alphacephei.com/vosk/models/vosk-model-small-ko-0.22.zip",
                "vosk-model-small-ko-0.22",
                "한국어",
                42f
            ),
            "fr" to ModelInfo(
                "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip",
                "vosk-model-small-fr-0.22",
                "Français",
                41f
            ),
            "de" to ModelInfo(
                "https://alphacephei.com/vosk/models/vosk-model-small-de-0.22.zip",
                "vosk-model-small-de-0.22",
                "Deutsch",
                41f
            ),
            "es" to ModelInfo(
                "https://alphacephei.com/vosk/models/vosk-model-small-es-0.22.zip",
                "vosk-model-small-es-0.22",
                "Español",
                41f
            ),
            "ru" to ModelInfo(
                "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip",
                "vosk-model-small-ru-0.22",
                "Русский",
                42f
            )
        )
    }

    private fun checkNetwork(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun initialize() {
        Log.d(TAG, "Initializing offline speech recognizer...")
        isInitialized = true
    }

    private fun getModelDir(languageCode: String): File {
        val modelInfo = MODELS[languageCode] ?: MODELS["zh"]!!
        return File(context.filesDir, "models/${modelInfo.name}")
    }

    private fun isModelDownloaded(languageCode: String): Boolean {
        val modelDir = getModelDir(languageCode)
        return modelDir.exists() && modelDir.listFiles()?.isNotEmpty() == true
    }

    fun getDownloadedModels(): List<String> {
        return MODELS.keys.filter { isModelDownloaded(it) }
    }

    fun downloadModel(languageCode: String) {
        if (!checkNetwork()) {
            runOnMain {
                runOnMain { onProgress(DownloadProgress(
                    state = DownloadProgress.State.ERROR,
                    languageCode = languageCode,
                    error = "❌ 无网络连接\n\n请检查：\n1. WiFi 或移动数据是否开启\n2. 是否处于飞行模式"
                )) }
            }
            return
        }

        val modelInfo = MODELS[languageCode] ?: return
        val modelDir = File(context.filesDir, "models")
        val targetDir = File(modelDir, modelInfo.name)

        runOnMain {
            onProgress(DownloadProgress(
                state = DownloadProgress.State.CHECKING,
                languageCode = languageCode
            ))
        }

        Thread {
            try {
                runOnMain { onProgress(DownloadProgress(
                    state = DownloadProgress.State.DOWNLOADING,
                    languageCode = languageCode,
                    totalMB = modelInfo.sizeMB
                )) }
                
                Log.d(TAG, "Downloading model from: ${modelInfo.url}")
                
                // 创建目录
                modelDir.mkdirs()
                
                // 打开连接
                val connection = URL(modelInfo.url).openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.connect()
                
                val contentLength = connection.contentLength.toLong()
                val inputStream = connection.inputStream
                
                // 下载到临时文件
                val tempFile = File(modelDir, "${modelInfo.name}.zip.tmp")
                val outputStream = FileOutputStream(tempFile)
                
                val buffer = ByteArray(8192)
                var totalRead = 0L
                var lastProgressUpdate = System.currentTimeMillis()
                var lastBytesRead = 0L
                var currentSpeed = 0f
                
                inputStream.use { input ->
                    outputStream.use { output ->
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            
                            val now = System.currentTimeMillis()
                            if (now - lastProgressUpdate > 500) { // 每 500ms 更新一次
                                val timeDiff = (now - lastProgressUpdate) / 1000f
                                val bytesDiff = totalRead - lastBytesRead
                                currentSpeed = (bytesDiff / timeDiff / 1024).toFloat()
                                
                                val progress = if (contentLength > 0) {
                                    (totalRead * 100 / contentLength).toInt()
                                } else {
                                    0
                                }
                                
                                val downloadedMB = totalRead / 1024f / 1024f
                                val eta = if (currentSpeed > 0 && contentLength > 0) {
                                    ((contentLength - totalRead) / (currentSpeed * 1024)).toInt()
                                } else {
                                    0
                                }
                                
                                runOnMain { onProgress(DownloadProgress(
                                    state = DownloadProgress.State.DOWNLOADING,
                                    languageCode = languageCode,
                                    progress = progress,
                                    downloadedMB = downloadedMB,
                                    totalMB = modelInfo.sizeMB,
                                    speedKBps = currentSpeed,
                                    etaSeconds = eta
                                )) }
                                
                                lastProgressUpdate = now
                                lastBytesRead = totalRead
                            }
                        }
                    }
                }
                
                outputStream.close()
                inputStream.close()
                
                // 解压
                runOnMain { onProgress(DownloadProgress(
                    state = DownloadProgress.State.EXTRACTING,
                    languageCode = languageCode,
                    progress = 100
                )) }
                
                targetDir.mkdirs()
                val zipInputStream = ZipInputStream(tempFile.inputStream())
                var entry = zipInputStream.nextEntry
                while (entry != null) {
                    val filePath = File(targetDir, entry.name)
                    if (entry.isDirectory) {
                        filePath.mkdirs()
                    } else {
                        filePath.parentFile?.mkdirs()
                        FileOutputStream(filePath).use { output ->
                            zipInputStream.copyTo(output)
                        }
                    }
                    zipInputStream.closeEntry()
                    entry = zipInputStream.nextEntry
                }
                zipInputStream.close()
                
                // 删除临时文件
                tempFile.delete()
                
                Log.d(TAG, "Model downloaded and extracted to: ${targetDir.absolutePath}")
                
                runOnMain { onProgress(DownloadProgress(
                    state = DownloadProgress.State.READY,
                    languageCode = languageCode,
                    progress = 100,
                    downloadedMB = modelInfo.sizeMB,
                    totalMB = modelInfo.sizeMB
                )) }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download model", e)
                targetDir.deleteRecursively()
                
                val errorMsg = when {
                    e.message?.contains("timeout", true) == true ->
                        "⏱️ 下载超时\n\n网络连接不稳定，请重试"
                    e.message?.contains("connect", true) == true ->
                        "🔌 连接失败\n\n无法连接到下载服务器\n请检查网络设置"
                    e.message?.contains("ENOSPC", true) == true ->
                        "💾 存储空间不足\n\n请清理手机存储后重试"
                    else ->
                        "❌ 下载失败: ${e.message}"
                }
                
                runOnMain { onProgress(DownloadProgress(
                    state = DownloadProgress.State.ERROR,
                    languageCode = languageCode,
                    error = errorMsg
                )) }
            }
        }.start()
    }

    private fun loadModel(languageCode: String, callback: (Boolean, String?) -> Unit) {
        val modelDir = getModelDir(languageCode)
        
        if (!isModelDownloaded(languageCode)) {
            callback(false, "模型未下载")
            return
        }

        runOnMain {
            onProgress(DownloadProgress(
                state = DownloadProgress.State.LOADING,
                languageCode = languageCode
            ))
        }

        Thread {
            try {
                // 关闭旧模型
                speechService?.stop()
                speechService?.shutdown()
                recognizer?.close()
                model?.close()
                
                // 加载新模型
                model = Model(modelDir.absolutePath)
                recognizer = Recognizer(model, 16000.0f)
                speechService = SpeechService(recognizer, 16000.0f)
                
                runOnMain { onProgress(DownloadProgress(
                    state = DownloadProgress.State.READY,
                    languageCode = languageCode
                )) }
                
                callback(true, null)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load model", e)
                callback(false, "加载模型失败: ${e.message}")
            }
        }.start()
    }

    fun startListening(languageCode: String) {
        if (!isInitialized) {
            onError("⏳ 语音识别正在初始化...")
            return
        }

        if (isListening) {
            stopListening()
            return
        }

        currentLanguage = languageCode

        // 检查并加载模型
        if (!isModelDownloaded(languageCode)) {
            runOnMain {
                onProgress(DownloadProgress(
                    state = DownloadProgress.State.CHECKING,
                    languageCode = languageCode
                ))
            }
            // 自动开始下载
            downloadModel(languageCode)
            return
        }

        loadModel(languageCode) { success, error ->
            if (success) {
                try {
                    speechService?.startListening(object : RecognitionListener {
                        override fun onPartialResult(hypothesis: String?) {}

                        override fun onResult(hypothesis: String?) {
                            hypothesis?.let {
                                try {
                                    val json = org.json.JSONObject(it)
                                    val text = json.optString("text", "")
                                    if (text.isNotBlank()) {
                                        onResult(text, true)
                                    }
                                } catch (e: Exception) {
                                    if (it.isNotBlank()) {
                                        onResult(it, true)
                                    }
                                }
                            }
                            isListening = false
                        }

                        override fun onFinalResult(hypothesis: String?) {
                            hypothesis?.let {
                                try {
                                    val json = org.json.JSONObject(it)
                                    val text = json.optString("text", "")
                                    if (text.isNotBlank()) {
                                        onResult(text, true)
                                    }
                                } catch (e: Exception) {
                                    if (it.isNotBlank()) {
                                        onResult(it, true)
                                    }
                                }
                            }
                            isListening = false
                        }

                        override fun onError(exception: Exception?) {
                            Log.e(TAG, "Recognition error", exception)
                            onError("🎤 语音识别错误: ${exception?.message ?: "未知错误"}")
                            isListening = false
                        }

                        override fun onTimeout() {
                            onError("⏱️ 语音识别超时，请重试")
                            isListening = false
                        }
                    })
                    
                    isListening = true
                    onListeningStart()
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start listening", e)
                    onError("启动语音识别失败: ${e.message}")
                }
            } else {
                onError("❌ ${error ?: "无法加载语音模型"}")
            }
        }
    }

    fun stopListening() {
        speechService?.stop()
        isListening = false
    }

    fun shutdown() {
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
        recognizer?.close()
        recognizer = null
        model?.close()
        model = null
        isInitialized = false
    }

    fun isListening(): Boolean = isListening
    fun isReady(): Boolean = isInitialized
}
