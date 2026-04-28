package com.example.livetranslator

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class TranslationManager(
    private val context: Context,
    private val onResult: (String, Boolean) -> Unit,
    private val onError: (String) -> Unit,
    private val onListeningStart: () -> Unit
) {
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isListening = false
    private var currentLocale: Locale = Locale.CHINA

    init {
        initSpeechRecognizer()
        initTextToSpeech()
    }

    private fun initSpeechRecognizer() {
        // 检查语音识别是否可用
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("❌ 设备不支持语音识别\n\n可能原因：\n1. 没有安装 Google App\n2. 没有 Google Play Services\n3. 设备不支持语音识别\n\n解决方案：\n• 安装 Google App (搜索 'Google' 在应用商店)\n• 或使用支持 Google 服务的设备")
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "onReadyForSpeech")
                onListeningStart()
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "onBeginningOfSpeech")
            }

            override fun onRmsChanged(rmsdB: Float) {
                // 可以用来显示音量指示器
                Log.d(TAG, "RMS changed: $rmsdB")
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "onEndOfSpeech")
            }

            override fun onError(error: Int) {
                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_NETWORK -> 
                        "🌐 网络错误\n\n需要网络连接才能使用语音识别\n请检查：\n1. WiFi 或移动数据是否开启\n2. 网络连接是否正常"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> 
                        "⏰ 网络超时\n\n网络连接太慢或无响应"
                    SpeechRecognizer.ERROR_NO_MATCH -> 
                        "🔇 没有识别到语音\n\n请：\n1. 说话声音再大一些\n2. 靠近麦克风\n3. 确保周围环境安静"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 
                        "⏳ 识别器忙碌\n\n请稍后再试"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 
                        "⏰ 语音超时\n\n没有检测到说话声\n请按住按钮后立即开始说话"
                    SpeechRecognizer.ERROR_AUDIO -> 
                        "🎤 音频错误\n\n麦克风可能被其他应用占用"
                    SpeechRecognizer.ERROR_CLIENT -> 
                        "📱 客户端错误\n\n请重启应用后重试"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> 
                        "🔒 权限不足\n\n请在设置中授予麦克风权限"
                    SpeechRecognizer.ERROR_SERVER -> 
                        "🖥️ 服务器错误\n\n服务暂时不可用，请稍后重试"
                    else -> "❓ 未知错误 (代码: $error)\n\n请重启应用重试"
                }
                Log.e(TAG, "Speech recognition error: $errorMsg (code: $error)")
                onError(errorMsg)
                isListening = false
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0]
                    Log.d(TAG, "Recognized: $text")
                    onResult(text, true)
                } else {
                    onError("🔇 没有识别到语音，请重试")
                }
                isListening = false
            }

            override fun onPartialResults(partialResults: Bundle?) {
                // 部分结果 - 可以用来显示实时识别
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    Log.d(TAG, "Partial: ${matches[0]}")
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun initTextToSpeech() {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech?.setLanguage(currentLocale)
                Log.d(TAG, "TTS initialized: $result")
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "TTS language not supported: $currentLocale")
                }
            } else {
                Log.e(TAG, "TTS initialization failed: $status")
            }
        }
    }

    fun startListening(language: Language) {
        if (speechRecognizer == null) {
            onError("语音识别未初始化，请重启应用")
            return
        }

        if (isListening) {
            stopListening()
            return
        }

        currentLocale = when (language.code) {
            "zh" -> Locale.CHINA
            "en" -> Locale.ENGLISH
            "ja" -> Locale.JAPAN
            "ko" -> Locale.KOREA
            "es" -> Locale("es", "ES")
            "fr" -> Locale.FRANCE
            "de" -> Locale.GERMANY
            "ru" -> Locale("ru", "RU")
            else -> Locale.ENGLISH
        }

        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLocale)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, currentLocale)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, currentLocale)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // 尝试使用离线识别（如果可用）
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        }

        try {
            speechRecognizer?.startListening(recognizerIntent)
            isListening = true
            Log.d(TAG, "Started listening for language: ${language.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening", e)
            onError("启动语音识别失败: ${e.message}")
        }
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        isListening = false
    }

    fun speak(text: String, language: Language) {
        val locale = when (language.code) {
            "zh" -> Locale.CHINA
            "en" -> Locale.ENGLISH
            "ja" -> Locale.JAPAN
            "ko" -> Locale.KOREA
            "es" -> Locale("es", "ES")
            "fr" -> Locale.FRANCE
            "de" -> Locale.GERMANY
            "ru" -> Locale("ru", "RU")
            else -> Locale.ENGLISH
        }

        textToSpeech?.let { tts ->
            val result = tts.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "TTS language not supported: $locale")
                onError("TTS 不支持 ${language.name} 语言")
                return
            }
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        } ?: run {
            onError("语音合成未初始化")
        }
    }

    fun shutdown() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
    }

    fun isListening(): Boolean = isListening

    companion object {
        private const val TAG = "TranslationManager"
    }
}
