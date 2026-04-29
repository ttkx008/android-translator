package com.example.livetranslator

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.livetranslator.ui.theme.LiveTranslatorTheme
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private val viewModel = TranslationViewModel()
    private var offlineRecognizer: OfflineSpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (audioGranted) {
            viewModel.onPermissionGranted()
        } else {
            viewModel.onError("🔒 需要麦克风权限才能使用语音输入\n\n请在设置中授予")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this, this)
        checkPermissions()
        initOfflineRecognizer()

        setContent {
            LiveTranslatorTheme {
                TranslatorApp(
                    viewModel = viewModel,
                    onStartRecording = {
                        val langCode = viewModel.sourceLanguage.value.code
                        offlineRecognizer?.startListening(langCode)
                    },
                    onStopRecording = {
                        offlineRecognizer?.stopListening()
                    }
                )
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            isTtsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                         result != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (permissions.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        } else {
            viewModel.onPermissionGranted()
        }
    }

    private fun initOfflineRecognizer() {
        offlineRecognizer = OfflineSpeechRecognizer(
            context = this,
            onResult = { text, isFinal ->
                if (isFinal) {
                    viewModel.onSpeechRecognized(text)
                    translateText(text)
                }
            },
            onError = { error ->
                runOnUiThread { viewModel.onError(error) }
            },
            onListeningStart = {
                runOnUiThread { viewModel.onListeningStarted() }
            },
            onProgress = { progress ->
                runOnUiThread { viewModel.updateDownloadProgress(progress) }
            }
        )
        offlineRecognizer?.initialize()

        viewModel.setDownloadModelFunc { languageCode ->
            offlineRecognizer?.downloadModel(languageCode)
        }

        runOnUiThread {
            viewModel.updateDownloadedModels(offlineRecognizer?.getDownloadedModels() ?: emptyList())
        }
    }

    private fun translateText(text: String) {
        val sourceLang = viewModel.sourceLanguage.value
        val targetLang = viewModel.targetLanguage.value

        viewModel.onTranslating()

        // 使用 ML Kit 离线翻译
        val sourceCode = mapToMlKitLanguage(sourceLang.code)
        val targetCode = mapToMlKitLanguage(targetLang.code)

        if (sourceCode == null || targetCode == null) {
            viewModel.onError("不支持的语言: ${sourceLang.name} → ${targetLang.name}")
            return
        }

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceCode)
            .setTargetLanguage(targetCode)
            .build()
        val translator = Translation.getClient(options)

        // 先下载模型（如果还没下载）
        translator.downloadModelIfNeeded()
            .addOnSuccessListener {
                translator.translate(text)
                    .addOnSuccessListener { translatedText ->
                        viewModel.onTranslationComplete(translatedText)
                        // TTS 朗读翻译结果
                        speakOut(translatedText, targetLang.code)
                    }
                    .addOnFailureListener { e ->
                        viewModel.onError("翻译失败: ${e.message}")
                    }
            }
            .addOnFailureListener { e ->
                viewModel.onError("翻译模型下载失败: ${e.message}\n\n请检查网络连接")
            }
    }

    private fun speakOut(text: String, langCode: String) {
        if (!isTtsReady) return
        val locale = when (langCode) {
            "zh" -> Locale.CHINESE
            "en" -> Locale.US
            "ja" -> Locale.JAPANESE
            "ko" -> Locale.KOREAN
            "fr" -> Locale.FRENCH
            "de" -> Locale.GERMAN
            "es" -> Locale("es")
            "ru" -> Locale("ru")
            else -> Locale.US
        }
        tts?.language = locale
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "translation_" + System.currentTimeMillis())
    }

    private fun mapToMlKitLanguage(code: String): String? {
        return when (code) {
            "zh" -> TranslateLanguage.CHINESE
            "en" -> TranslateLanguage.ENGLISH
            "ja" -> TranslateLanguage.JAPANESE
            "ko" -> TranslateLanguage.KOREAN
            "fr" -> TranslateLanguage.FRENCH
            "de" -> TranslateLanguage.GERMAN
            "es" -> TranslateLanguage.SPANISH
            "ru" -> TranslateLanguage.RUSSIAN
            else -> null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        offlineRecognizer?.shutdown()
        tts?.stop()
        tts?.shutdown()
    }
}

// ==================== ViewModel ====================

class TranslationViewModel {
    val isPermissionGranted = MutableStateFlow(false)
    val sourceLanguage = MutableStateFlow(Languages.getByCode("zh"))
    val targetLanguage = MutableStateFlow(Languages.getByCode("en"))
    val isRecording = MutableStateFlow(false)
    val isTranslating = MutableStateFlow(false)
    val sourceText = MutableStateFlow("")
    val translatedText = MutableStateFlow("")
    val errorMessage = MutableStateFlow<String?>(null)
    val downloadProgress = MutableStateFlow<OfflineSpeechRecognizer.DownloadProgress?>(null)
    val downloadedModels = MutableStateFlow<List<String>>(emptyList())
    val showLanguagePicker = MutableStateFlow(false)
    val pickingFor = MutableStateFlow("source") // "source" or "target"

    private var downloadModelFunc: ((String) -> Unit)? = null

    fun onPermissionGranted() {
        isPermissionGranted.value = true
        errorMessage.value = null
    }

    fun startRecording() {
        if (!isPermissionGranted.value) {
            onError("请先授予麦克风权限")
            return
        }
        isRecording.value = true
        sourceText.value = ""
        translatedText.value = ""
        errorMessage.value = null
    }

    fun stopRecording(model: OfflineSpeechRecognizer?) {
        isRecording.value = false
        model?.stopListening()
    }

    fun onStopRecording() {
        isRecording.value = false
    }

    fun onListeningStarted() {
        isRecording.value = true
    }

    fun onSpeechRecognized(text: String) {
        sourceText.value = text
        isRecording.value = false
    }

    fun onTranslating() {
        isTranslating.value = true
        errorMessage.value = null
    }

    fun onTranslationComplete(translated: String) {
        translatedText.value = translated
        isTranslating.value = false
    }

    fun onError(error: String) {
        isRecording.value = false
        isTranslating.value = false
        errorMessage.value = error
    }

    fun swapLanguages() {
        val oldSource = sourceLanguage.value
        val oldTarget = targetLanguage.value
        sourceLanguage.value = oldTarget
        targetLanguage.value = oldSource
        // 交换文本
        val oldSrcText = sourceText.value
        val oldTransText = translatedText.value
        sourceText.value = oldTransText
        translatedText.value = oldSrcText
    }

    fun openLanguagePicker(forWhich: String) {
        pickingFor.value = forWhich
        showLanguagePicker.value = true
    }

    fun selectLanguage(language: Language) {
        if (pickingFor.value == "source") {
            sourceLanguage.value = language
        } else {
            targetLanguage.value = language
        }
        showLanguagePicker.value = false
    }

    fun setDownloadModelFunc(func: (String) -> Unit) {
        downloadModelFunc = func
    }

    fun downloadModel(languageCode: String) {
        downloadModelFunc?.invoke(languageCode)
    }

    fun updateDownloadProgress(progress: OfflineSpeechRecognizer.DownloadProgress) {
        downloadProgress.value = progress
    }

    fun updateDownloadedModels(models: List<String>) {
        downloadedModels.value = models
    }

    fun dismissError() {
        errorMessage.value = null
    }
}

// ==================== UI ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslatorApp(
    viewModel: TranslationViewModel,
    onStartRecording: () -> Unit = {},
    onStopRecording: () -> Unit = {}
) {
    val isRecording by viewModel.isRecording.collectAsState()
    val isTranslating by viewModel.isTranslating.collectAsState()
    val sourceLanguage by viewModel.sourceLanguage.collectAsState()
    val targetLanguage by viewModel.targetLanguage.collectAsState()
    val sourceText by viewModel.sourceText.collectAsState()
    val translatedText by viewModel.translatedText.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val showLanguagePicker by viewModel.showLanguagePicker.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("💬 离线翻译", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 语言选择栏
            LanguageBar(
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                onSwap = { viewModel.swapLanguages() },
                onSourceClick = { viewModel.openLanguagePicker("source") },
                onTargetClick = { viewModel.openLanguagePicker("target") }
            )

            // 翻译内容区
            TranslationContent(
                sourceText = sourceText,
                translatedText = translatedText,
                isTranslating = isTranslating,
                sourceLang = sourceLanguage,
                targetLang = targetLanguage
            )

            // 错误提示
            errorMessage?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(error, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                        IconButton(onClick = { viewModel.dismissError() }) {
                            Icon(Icons.Default.Close, "关闭")
                        }
                    }
                }
            }

            // 下载进度
            downloadProgress?.let { progress ->
                DownloadProgressBar(progress)
            }

                    // 录音按钮
            RecordButton(
                isRecording = isRecording,
                onClick = {
                    if (isRecording) {
                        onStopRecording()
                        viewModel.onStopRecording()
                    } else {
                        viewModel.startRecording()
                        onStartRecording()
                    }
                }
            )
        }
    }

    // 语言选择弹窗
    if (showLanguagePicker) {
        LanguagePickerDialog(
            languages = Languages.languages,
            onSelect = { viewModel.selectLanguage(it) },
            onDismiss = { viewModel.showLanguagePicker.value = false }
        )
    }
}

@Composable
fun LanguageBar(
    sourceLanguage: Language,
    targetLanguage: Language,
    onSwap: () -> Unit,
    onSourceClick: () -> Unit,
    onTargetClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // 源语言
        LanguageChip(
            language = sourceLanguage,
            onClick = onSourceClick,
            modifier = Modifier.weight(1f)
        )

        // 交换按钮
        IconButton(
            onClick = onSwap,
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
        ) {
            Icon(Icons.Default.SwapHoriz, "交换语言")
        }

        // 目标语言
        LanguageChip(
            language = targetLanguage,
            onClick = onTargetClick,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun LanguageChip(language: Language, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Text(
            language.name,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp
        )
    }
}

@Composable
fun TranslationContent(
    sourceText: String,
    translatedText: String,
    isTranslating: Boolean,
    sourceLang: Language,
    targetLang: Language
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 源文本
        TextCard(
            label = "🎤 ${sourceLang.name}",
            text = sourceText.ifEmpty { "点击下方按钮开始说话..." },
            isPlaceholder = sourceText.isEmpty(),
            containerColor = Color(0xFFE3F2FD)
        )

        // 分隔线
        if (isTranslating) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("翻译中...", color = Color.Gray)
            }
        } else if (translatedText.isNotEmpty()) {
            Icon(
                Icons.Default.ArrowDownward,
                contentDescription = null,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                tint = Color.Gray
            )
        }

        // 翻译文本
        TextCard(
            label = "📖 ${targetLang.name}",
            text = translatedText.ifEmpty { "翻译结果将显示在这里..." },
            isPlaceholder = translatedText.isEmpty(),
            containerColor = Color(0xFFF3E5F5)
        )
    }
}

@Composable
fun TextCard(label: String, text: String, isPlaceholder: Boolean, containerColor: Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                label,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text,
                fontSize = 18.sp,
                color = if (isPlaceholder) Color.Gray else Color.Black,
                lineHeight = 26.sp
            )
        }
    }
}

@Composable
fun RecordButton(isRecording: Boolean, onClick: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRecording) 1.2f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val buttonColor by animateColorAsState(
        targetValue = if (isRecording) Color(0xFFFF5252) else MaterialTheme.colorScheme.primary,
        label = "color"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(bottom = 24.dp)
    ) {
        FloatingActionButton(
            onClick = onClick,
            modifier = Modifier
                .size(80.dp)
                .scale(scale),
            containerColor = buttonColor,
            shape = CircleShape
        ) {
            Icon(
                if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = if (isRecording) "停止" else "开始说话",
                modifier = Modifier.size(36.dp),
                tint = Color.White
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            if (isRecording) "🔴 点击停止并翻译" else "🎤 点击开始说话",
            fontSize = 14.sp,
            color = if (isRecording) Color.Red else Color.Gray,
            fontWeight = if (isRecording) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun DownloadProgressBar(progress: OfflineSpeechRecognizer.DownloadProgress) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            val stateText = when (progress.state) {
                OfflineSpeechRecognizer.DownloadProgress.State.CHECKING -> "🔍 检查模型..."
                OfflineSpeechRecognizer.DownloadProgress.State.DOWNLOADING -> {
                    if (progress.speedKBps > 0) {
                        "⬇️ 下载中 ${progress.progress}% (${String.format("%.1f", progress.speedKBps)}KB/s)"
                    } else {
                        "⬇️ 下载中 ${progress.progress}%"
                    }
                }
                OfflineSpeechRecognizer.DownloadProgress.State.EXTRACTING -> "📦 解压模型..."
                OfflineSpeechRecognizer.DownloadProgress.State.LOADING -> "⏳ 加载模型..."
                OfflineSpeechRecognizer.DownloadProgress.State.READY -> "✅ 模型就绪"
                OfflineSpeechRecognizer.DownloadProgress.State.ERROR -> "❌ ${progress.error}"
                else -> "准备中..."
            }
            Text(stateText, fontSize = 14.sp)
            if (progress.state == OfflineSpeechRecognizer.DownloadProgress.State.DOWNLOADING && progress.totalMB > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                @Suppress("DEPRECATION")
                LinearProgressIndicator(
                    progress = progress.progress / 100f,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${String.format("%.1f", progress.downloadedMB)}MB / ${String.format("%.0f", progress.totalMB)}MB",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
fun LanguagePickerDialog(
    languages: List<Language>,
    onSelect: (Language) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择语言") },
        text = {
            Column {
                languages.forEach { language ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(language) }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(language.name, fontSize = 18.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
