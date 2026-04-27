package com.example.livetranslator

data class ConversationMessage(
    val id: Long,
    val speaker: Speaker,
    val originalText: String,
    val translatedText: String,
    val timestamp: Long = System.currentTimeMillis()
)

enum class Speaker {
    A, B
}