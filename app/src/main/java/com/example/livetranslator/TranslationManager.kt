package com.example.livetranslator

import android.content.Context
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
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {
                    Log.d(TAG, "onReadyForSpeech")
                }

                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "onBeginningOfSpeech")
                    onListeningStart()
                }

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    Log.d(TAG, "onEndOfSpeech")
                    isListening = false
                }

                override fun onError(error: Int) {
                    val errorMsg = when (error) {
                        SpeechRecognizer.ERROR_NETWORK -> "Network error"
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech matched"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                        else -> "Error: $error"
                    }
                    Log.e(TAG, "Speech recognition error: $errorMsg")
                    onError(errorMsg)
                    isListening = false
                }

                override fun onResults(results: android.os.Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val text = matches[0]
                        Log.d(TAG, "Recognized: $text")
                        onResult(text, true)
                    } else {
                        onError("No speech recognized")
                    }
                    isListening = false
                }

                override fun onPartialResults(partialResults: android.os.Bundle?) {}

                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })
        } else {
            onError("Speech recognition not available on this device")
        }
    }

    private fun initTextToSpeech() {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech?.setLanguage(currentLocale)
                Log.d(TAG, "TTS initialized: $result")
            } else {
                Log.e(TAG, "TTS initialization failed: $status")
            }
        }
    }

    fun startListening(language: Language) {
        if (isListening) {
            stopListening()
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

        val intent = RecognizerIntent.getVoiceDetailsIntent(context.packageName)
        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLocale)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }

        speechRecognizer?.startListening(recognizerIntent)
        isListening = true
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

        textToSpeech?.setLanguage(locale)
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    fun shutdown() {
        speechRecognizer?.destroy()
        textToSpeech?.shutdown()
    }

    fun isListening(): Boolean = isListening

    companion object {
        private const val TAG = "TranslationManager"
    }
}