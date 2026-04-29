package com.example.livetranslator

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
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
        val progress: Int = 0,
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

        data class ModelInfo(
            val name: String,
            val displayName: String,
            val sizeMB: Float,
            val urls: List<String>  // 多源下载，按优先级排列
        )

        // HuggingFace 国内镜像 + 官方源作为备用
        private const val HF_MIRROR = "https://hf-mirror.com"
        private const val HF_ORIGINAL = "https://huggingface.co"
        private const val ALPHACEPHEI = "https://alphacephei.com/vosk/models"

        val MODELS = mapOf(
            "zh" to ModelInfo(
                "vosk-model-small-cn-0.22",
                "中文",
                42f,
                listOf(
                    "$HF_MIRROR/alphacephei/vosk-model-small-cn-0.22/resolve/main/vosk-model-small-cn-0.22.zip",
                    "$HF_ORIGINAL/alphacephei/vosk-model-small-cn-0.22/resolve/main/vosk-model-small-cn-0.22.zip",
                    "$ALPHACEPHEI/vosk-model-small-cn-0.22.zip"
                )
            ),
            "en" to ModelInfo(
                "vosk-model-small-en-us-0.15",
                "English",
                40f,
                listOf(
                    "$HF_MIRROR/alphacephei/vosk-model-small-en-us-0.15/resolve/main/vosk-model-small-en-us-0.15.zip",
                    "$HF_ORIGINAL/alphacephei/vosk-model-small-en-us-0.15/resolve/main/vosk-model-small-en-us-0.15.zip",
                    "$ALPHACEPHEI/vosk-model-small-en-us-0.15.zip"
                )
            ),
            "ja" to ModelInfo(
                "vosk-model-small-ja-0.22",
                "日本語",
                45f,
                listOf(
                    "$HF_MIRROR/alphacephei/vosk-model-small-ja-0.22/resolve/main/vosk-model-small-ja-0.22.zip",
                    "$HF_ORIGINAL/alphacephei/vosk-model-small-ja-0.22/resolve/main/vosk-model-small-ja-0.22.zip",
                    "$ALPHACEPHEI/vosk-model-small-ja-0.22.zip"
                )
            ),
            "ko" to ModelInfo(
                "vosk-model-small-ko-0.22",
                "한국어",
                42f,
                listOf(
                    "$HF_MIRROR/alphacephei/vosk-model-small-ko-0.22/resolve/main/vosk-model-small-ko-0.22.zip",
                    "$HF_ORIGINAL/alphacephei/vosk-model-small-ko-0.22/resolve/main/vosk-model-small-ko-0.22.zip",
                    "$ALPHACEPHEI/vosk-model-small-ko-0.22.zip"
                )
            ),
            "fr" to ModelInfo(
                "vosk-model-small-fr-0.22",
                "Français",
                41f,
                listOf(
                    "$HF_MIRROR/alphacephei/vosk-model-small-fr-0.22/resolve/main/vosk-model-small-fr-0.22.zip",
                    "$HF_ORIGINAL/alphacephei/vosk-model-small-fr-0.22/resolve/main/vosk-model-small-fr-0.22.zip",
                    "$ALPHACEPHEI/vosk-model-small-fr-0.22.zip"
                )
            ),
            "de" to ModelInfo(
                "vosk-model-small-de-0.22",
                "Deutsch",
                41f,
                listOf(
                    "$HF_MIRROR/alphacephei/vosk-model-small-de-0.22/resolve/main/vosk-model-small-de-0.22.zip",
                    "$HF_ORIGINAL/alphacephei/vosk-model-small-de-0.22/resolve/main/vosk-model-small-de-0.22.zip",
                    "$ALPHACEPHEI/vosk-model-small-de-0.22.zip"
                )
            ),
            "es" to ModelInfo(
                "vosk-model-small-es-0.22",
                "Español",
                41f,
                listOf(
                    "$HF_MIRROR/alphacephei/vosk-model-small-es-0.22/resolve/main/vosk-model-small-es-0.22.zip",
                    "$HF_ORIGINAL/alphacephei/vosk-model-small-es-0.22/resolve/main/vosk-model-small-es-0.22.zip",
                    "$ALPHACEPHEI/vosk-model-small-es-0.22.zip"
                )
            ),
            "ru" to ModelInfo(
                "vosk-model-small-ru-0.22",
                "Русский",
                42f,
                listOf(
                    "$HF_MIRROR/alphacephei/vosk-model-small-ru-0.22/resolve/main/vosk-model-small-ru-0.22.zip",
                    "$HF_ORIGINAL/alphacephei/vosk-model-small-ru-0.22/resolve/main/vosk-model-small-ru-0.22.zip",
                    "$ALPHACEPHEI/vosk-model-small-ru-0.22.zip"
                )
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
                onProgress(DownloadProgress(
                    state = DownloadProgress.State.ERROR,
                    languageCode = languageCode,
                    error = "❌ 无网络连接\n\n请检查：\n1. WiFi 或移动数据是否开启\n2. 是否处于飞行模式"
                ))
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
            var success = false
            var lastError: String? = null

            // 按优先级尝试多个下载源
            for ((index, url) in modelInfo.urls.withIndex()) {
                Log.d(TAG, "Trying source ${index + 1}/${modelInfo.urls.size}: $url")
                try {
                    downloadFromSource(url, modelInfo, modelDir, targetDir, languageCode)
                    success = true
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Source ${index + 1} failed: ${e.message}")
                    lastError = e.message
                    // 清理失败的临时文件
                    File(modelDir, "${modelInfo.name}.zip.tmp").delete()
                    continue
                }
            }

            if (!success) {
                targetDir.deleteRecursively()
                val errorMsg = when {
                    lastError?.contains("timeout", true) == true ->
                        "⏱️ 下载超时\n\n所有下载源均超时，请检查网络后重试"
                    lastError?.contains("connect", true) == true ->
                        "🔌 连接失败\n\n无法连接到下载服务器\n请检查网络设置"
                    lastError?.contains("ENOSPC", true) == true ->
                        "💾 存储空间不足\n\n请清理手机存储后重试"
                    else ->
                        "❌ 下载失败: $lastError"
                }
                runOnMain {
                    onProgress(DownloadProgress(
                        state = DownloadProgress.State.ERROR,
                        languageCode = languageCode,
                        error = errorMsg
                    ))
                }
            }
        }.start()
    }

    /**
     * 从单个源下载模型，支持断点续传
     */
    private fun downloadFromSource(
        url: String,
        modelInfo: OfflineSpeechRecognizer.Companion.ModelInfo,
        modelDir: File,
        targetDir: File,
        languageCode: String
    ) {
        modelDir.mkdirs()

        val tempFile = File(modelDir, "${modelInfo.name}.zip.tmp")
        var downloadedBytes = 0L

        // 检查已有临时文件（断点续传）
        if (tempFile.exists()) {
            downloadedBytes = tempFile.length()
            if (downloadedBytes > 0) {
                Log.d(TAG, "Resuming download from ${downloadedBytes / 1024}KB")
                runOnMain {
                    onProgress(DownloadProgress(
                        state = DownloadProgress.State.DOWNLOADING,
                        languageCode = languageCode,
                        downloadedMB = downloadedBytes / 1024f / 1024f,
                        totalMB = modelInfo.sizeMB
                    ))
                }
            }
        }

        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 30000

        // 断点续传：设置 Range 头
        if (downloadedBytes > 0) {
            connection.setRequestProperty("Range", "bytes=${downloadedBytes}-")
        }
        connection.connect()

        val responseCode = connection.responseCode

        // 检查服务器是否支持断点续传
        val supportsResume = responseCode == 206
        val contentLength = connection.contentLength.toLong()

        // 服务器不支持断点续传，重新下载
        if (downloadedBytes > 0 && !supportsResume) {
            Log.d(TAG, "Server does not support resume, restarting download")
            downloadedBytes = 0
            tempFile.delete()
        }

        // 服务器返回非 200/206，抛出异常（触发换源）
        if (responseCode != 200 && responseCode != 206) {
            connection.disconnect()
            throw Exception("HTTP $responseCode")
        }

        val totalFileSize = if (supportsResume) {
            downloadedBytes + contentLength
        } else {
            contentLength
        }

        runOnMain {
            onProgress(DownloadProgress(
                state = DownloadProgress.State.DOWNLOADING,
                languageCode = languageCode,
                downloadedMB = downloadedBytes / 1024f / 1024f,
                totalMB = modelInfo.sizeMB
            ))
        }

        val inputStream = connection.inputStream
        val outputStream = FileOutputStream(tempFile, downloadedBytes > 0) // append mode for resume

        val buffer = ByteArray(8192)
        var totalRead = downloadedBytes
        var sessionRead = 0L
        val sessionStart = System.currentTimeMillis()
        var lastProgressUpdate = sessionStart
        var lastBytesRead = downloadedBytes

        try {
            inputStream.use { input ->
                outputStream.use { output ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        sessionRead += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastProgressUpdate > 500) {
                            val timeDiff = (now - lastProgressUpdate) / 1000f
                            val bytesDiff = totalRead - lastBytesRead
                            val currentSpeed = (bytesDiff / timeDiff / 1024).toFloat()

                            val progress = if (totalFileSize > 0) {
                                (totalRead * 100 / totalFileSize).toInt()
                            } else 0

                            val downloadedMB = totalRead / 1024f / 1024f
                            val eta = if (currentSpeed > 0 && totalFileSize > 0) {
                                ((totalFileSize - totalRead) / (currentSpeed * 1024)).toInt()
                            } else 0

                            runOnMain {
                                onProgress(DownloadProgress(
                                    state = DownloadProgress.State.DOWNLOADING,
                                    languageCode = languageCode,
                                    progress = progress,
                                    downloadedMB = downloadedMB,
                                    totalMB = modelInfo.sizeMB,
                                    speedKBps = currentSpeed,
                                    etaSeconds = eta
                                ))
                            }

                            lastProgressUpdate = now
                            lastBytesRead = totalRead
                        }
                    }
                }
            }
        } finally {
            outputStream.close()
            inputStream.close()
            connection.disconnect()
        }

        Log.d(TAG, "Download complete: ${totalRead / 1024}KB total")

        // 下载完成，开始解压
        runOnMain {
            onProgress(DownloadProgress(
                state = DownloadProgress.State.EXTRACTING,
                languageCode = languageCode,
                progress = 100
            ))
        }

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

        Log.d(TAG, "Model extracted to: ${targetDir.absolutePath}")

        runOnMain {
            onProgress(DownloadProgress(
                state = DownloadProgress.State.READY,
                languageCode = languageCode,
                progress = 100,
                downloadedMB = modelInfo.sizeMB,
                totalMB = modelInfo.sizeMB
            ))
        }
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
                speechService?.stop()
                speechService?.shutdown()
                recognizer?.close()
                model?.close()

                model = Model(modelDir.absolutePath)
                recognizer = Recognizer(model, 16000.0f)
                speechService = SpeechService(recognizer, 16000.0f)

                runOnMain {
                    onProgress(DownloadProgress(
                        state = DownloadProgress.State.READY,
                        languageCode = languageCode
                    ))
                }

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

        if (!isModelDownloaded(languageCode)) {
            runOnMain {
                onProgress(DownloadProgress(
                    state = DownloadProgress.State.CHECKING,
                    languageCode = languageCode
                ))
            }
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
