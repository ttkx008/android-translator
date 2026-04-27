package com.example.livetranslator

data class Language(
    val code: String,
    val name: String,
    val speechCode: String = code
)

object Languages {
    val languages = listOf(
        Language("zh", "中文", "zh-CN"),
        Language("en", "English", "en-US"),
        Language("ja", "日本語", "ja-JP"),
        Language("ko", "한국어", "ko-KR"),
        Language("es", "Español", "es-ES"),
        Language("fr", "Français", "fr-FR"),
        Language("de", "Deutsch", "de-DE"),
        Language("ru", "Русский", "ru-RU")
    )

    fun getByCode(code: String): Language = languages.find { it.code == code } ?: languages[0]
}