package com.example.livetranslator

import android.content.Context
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.ZipInputStream

class OfflineSpeechRecognizer(
    private val context: Context,
    private val onResult: (String, Boolean) -> Unit,
    private val onError: (String) -> Unit,
    private val onListeningStart: () -> Unit,
    private val onProgress: (String) -> Unit = {}
) {
    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var recognizer: Recognizer? = null
    private var isInitialized = false
    private var isListening = false
    private var currentLanguage = "zh"

    companion object {
        private const val TAG = "OfflineSpeechRecognizer"
        
        // Vosk 模型下载链接
        private val MODEL_URLS = mapOf(
            "zh" to "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip",
            "en" to "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
            "ja" to "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip",
            "ko" to "https://alphacephei.com/vosk/models/vosk-model-small-ko-0.22.zip",
            "fr" to "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip",
            "de" to "https://alphacephei.com/vosk/models/vosk-model-small-de-0.22.zip",
            "es" to "https://alphacephei.com/vosk/models/vosk-model-small-es-0.22.zip",
            "ru" to "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
        )
        
        private val MODEL_NAMES = mapOf(
            "zh" to "vosk-model-small-cn-0.22",
            "en" to "vosk-model-small-en-us-0.15",
            "ja" to "vosk-model-small-ja-0.22",
            "ko" to "vosk-model-small-ko-0.22",
            "fr" to "vosk-model-small-fr-0.22",
            "de" to "vosk-model-small-de-0.22",
            "es" to "vosk-model-small-es-0.22",
            "ru" to "vosk-model-small-ru-0.22"
        )
    }

    fun initialize() {
        Log.d(TAG, "Initializing offline speech recognizer...")
        // 模型会在首次使用时按需下载
        isInitialized = true
    }

    private fun getModelDir(languageCode: String): File {
        val modelName = MODEL_NAMES[languageCode] ?: "vosk-model-small-cn-0.22"
        return File(context.filesDir, "models/$modelName")
    }

    private fun isModelDownloaded(languageCode: String): Boolean {
        val modelDir = getModelDir(languageCode)
        return modelDir.exists() && modelDir.listFiles()?.isNotEmpty() == true
    }

    private fun downloadModel(languageCode: String, callback: (Boolean, String?) -> Unit) {
        val url = MODEL_URLS[languageCode] ?: MODEL_URLS["zh"]!!
        val modelName = MODEL_NAMES[languageCode] ?: "vosk-model-small-cn-0.22"
        val modelDir = File(context.filesDir, "models")
        val targetDir = File(modelDir, modelName)

        Thread {
            try {
                onProgress("📥 正在下载语音模型（约 50MB）...")
                Log.d(TAG, "Downloading model from: $url")
                
                // 创建目录
                modelDir.mkdirs()
                
                // 下载 zip 文件
                val connection = URL(url).openConnection()
                val inputStream = connection.getInputStream()
                val zipInputStream = ZipInputStream(inputStream)
                
                // 解压到目标目录
                targetDir.mkdirs()
                var entry = zipInputStream.nextEntry
                while (entry != null) {
                    val filePath = File(targetDir, entry.name)
                    
                    if (entry.isDirectory) {
                        filePath.mkdirs()
                    } else {
                        // 确保父目录存在
                        filePath.parentFile?.mkdirs()
                        
                        // 解压文件
                        FileOutputStream(filePath).use { outputStream ->
                            zipInputStream.copyTo(outputStream)
                        }
                    }
                    
                    zipInputStream.closeEntry()
                    entry = zipInputStream.nextEntry
                }
                
                zipInputStream.close()
                inputStream.close()
                
                Log.d(TAG, "Model downloaded and extracted to: ${targetDir.absolutePath}")
                callback(true, null)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download model", e)
                // 清理失败的下载
                targetDir.deleteRecursively()
                callback(false, "下载失败: ${e.message}")
            }
        }.start()
    }

    private fun loadModel(languageCode: String, callback: (Boolean, String?) -> Unit) {
        val modelDir = getModelDir(languageCode)
        
        if (!isModelDownloaded(languageCode)) {
            // 需要下载模型
            downloadModel(languageCode) { success, error ->
                if (success) {
                    loadModelFromDir(modelDir, callback)
                } else {
                    callback(false, error)
                }
            }
        } else {
            // 模型已存在，直接加载
            loadModelFromDir(modelDir, callback)
        }
    }

    private fun loadModelFromDir(modelDir: File, callback: (Boolean, String?) -> Unit) {
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
            
            callback(true, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            callback(false, "加载模型失败: ${e.message}")
        }
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
        onProgress("🔄 正在加载语音模型...")
        
        loadModel(languageCode) { success, error ->
            if (success) {
                // 开始监听
                try {
                    speechService?.startListening(object : RecognitionListener {
                        override fun onPartialResult(hypothesis: String?) {
                            // 部分结果（实时显示）
                        }

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
