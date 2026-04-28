package com.example.livetranslator

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.livetranslator.ui.theme.LiveTranslatorTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class MainActivity : ComponentActivity() {
    private val viewModel = TranslationViewModel()
    private var offlineRecognizer: OfflineSpeechRecognizer? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            viewModel.permissionGranted()
        } else {
            viewModel.permissionDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkPermissions()
        initOfflineRecognizer()

        setContent {
            LiveTranslatorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TranslatorScreen(
                        viewModel = viewModel,
                        onStartListening = { speaker ->
                            startListeningForSpeaker(speaker)
                        },
                        onSwapLanguages = {
                            viewModel.swapLanguages()
                        },
                        onSpeakTranslation = { message ->
                            speakTranslation(message)
                        }
                    )
                }
            }
        }
    }

    private fun checkPermissions() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                viewModel.permissionGranted()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun initOfflineRecognizer() {
        offlineRecognizer = OfflineSpeechRecognizer(
            context = this,
            onResult = { text, isFinal ->
                Log.d(TAG, "Speech result: $text, isFinal: $isFinal")
                if (isFinal) {
                    viewModel.onSpeechRecognized(text)
                    translateText(text)
                }
            },
            onError = { error ->
                Log.e(TAG, "Speech error: $error")
                runOnUiThread {
                    viewModel.onError(error)
                }
            },
            onListeningStart = {
                Log.d(TAG, "Listening started")
                runOnUiThread {
                    viewModel.listeningStarted()
                }
            },
            onProgress = { progress ->
                runOnUiThread {
                    viewModel.updateProgress(progress)
                }
            }
        )
        offlineRecognizer?.initialize()
    }

    private fun startListeningForSpeaker(speaker: Speaker) {
        if (!viewModel.isPermissionGranted.value) {
            checkPermissions()
            return
        }

        val language = if (speaker == Speaker.A) viewModel.sourceLanguage.value else viewModel.targetLanguage.value
        offlineRecognizer?.startListening(language.code)
        viewModel.setCurrentListeningSpeaker(speaker)
    }

    private fun translateText(text: String) {
        viewModel.currentListeningSpeaker.value?.let { speaker ->
            val sourceLang = if (speaker == Speaker.A) viewModel.sourceLanguage.value else viewModel.targetLanguage.value
            val targetLang = if (speaker == Speaker.A) viewModel.targetLanguage.value else viewModel.sourceLanguage.value

            LibreTranslateApi(
                onTranslationComplete = { translatedText ->
                    runOnUiThread {
                        viewModel.translateCurrentText(translatedText)
                    }
                },
                onError = { error ->
                    runOnUiThread {
                        viewModel.onError("翻译失败: $error")
                    }
                }
            ).translate(text, sourceLang.code, targetLang.code)
        }
    }

    private fun speakTranslation(message: ConversationMessage) {
        val language = if (message.speaker == Speaker.A) viewModel.targetLanguage.value else viewModel.sourceLanguage.value
        // 使用 Android 内置 TTS（离线可用）
        val intent = android.content.Intent(android.speech.tts.TextToSpeech.Engine.ACTION_CHECK_TTS_DATA)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            viewModel.onError("请安装 Google TTS 或其他 TTS 应用")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        offlineRecognizer?.shutdown()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

class TranslationViewModel {
    val isPermissionGranted = MutableStateFlow(false)
    val sourceLanguage = MutableStateFlow(Languages.getByCode("zh"))
    val targetLanguage = MutableStateFlow(Languages.getByCode("en"))
    val messages = MutableStateFlow<List<ConversationMessage>>(emptyList())
    val isListening = MutableStateFlow(false)
    val currentListeningSpeaker = MutableStateFlow<Speaker?>(null)
    val errorMessage = MutableStateFlow<String?>(null)
    val progressMessage = MutableStateFlow<String?>(null)

    private var nextId = 0L

    fun permissionGranted() {
        isPermissionGranted.value = true
        errorMessage.value = null
    }

    fun permissionDenied() {
        isPermissionGranted.value = false
        errorMessage.value = "🔒 麦克风权限被拒绝\n\n请在设置中授予麦克风权限才能使用语音输入"
    }

    fun swapLanguages() {
        val oldSource = sourceLanguage.value
        val oldTarget = targetLanguage.value
        sourceLanguage.value = oldTarget
        targetLanguage.value = oldSource
    }

    fun setSourceLanguage(language: Language) {
        sourceLanguage.value = language
    }

    fun setTargetLanguage(language: Language) {
        targetLanguage.value = language
    }

    fun setCurrentListeningSpeaker(speaker: Speaker) {
        currentListeningSpeaker.value = speaker
        isListening.value = true
        errorMessage.value = null
    }

    fun listeningStarted() {
        isListening.value = true
        errorMessage.value = null
        progressMessage.value = null
    }

    fun onSpeechRecognized(text: String) {
        isListening.value = false
        currentListeningSpeaker.value?.let { speaker ->
            val message = ConversationMessage(
                id = nextId++,
                speaker = speaker,
                originalText = text,
                translatedText = "翻译中..."
            )
            messages.update { it + message }
        }
        errorMessage.value = null
        progressMessage.value = null
    }

    fun translateCurrentText(translatedText: String) {
        currentListeningSpeaker.value?.let { speaker ->
            messages.update { currentMessages ->
                currentMessages.map { msg ->
                    if (msg.id == currentMessages.lastOrNull()?.id) {
                        msg.copy(translatedText = translatedText)
                    } else {
                        msg
                    }
                }
            }
            errorMessage.value = null
        }
    }

    fun onError(error: String) {
        isListening.value = false
        errorMessage.value = error
        progressMessage.value = null
    }

    fun updateProgress(message: String) {
        progressMessage.value = message
        errorMessage.value = null
    }

    fun clearConversation() {
        messages.value = emptyList()
        nextId = 0
        errorMessage.value = null
        progressMessage.value = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslatorScreen(
    viewModel: TranslationViewModel,
    onStartListening: (Speaker) -> Unit,
    onSwapLanguages: () -> Unit,
    onSpeakTranslation: (ConversationMessage) -> Unit
) {
    val messages by viewModel.messages.collectAsState()
    val sourceLanguage by viewModel.sourceLanguage.collectAsState()
    val targetLanguage by viewModel.targetLanguage.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val currentListeningSpeaker by viewModel.currentListeningSpeaker.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val progressMessage by viewModel.progressMessage.collectAsState()
    val isPermissionGranted by viewModel.isPermissionGranted.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🗣️ 实时对话翻译 (离线版)") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Language Selection Bar
            LanguageSelector(
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                onSourceChange = { viewModel.setSourceLanguage(it) },
                onTargetChange = { viewModel.setTargetLanguage(it) },
                onSwap = onSwapLanguages
            )

            // Conversation History
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                reverseLayout = false
            ) {
                if (messages.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "🎤",
                                    fontSize = 48.sp
                                )
                                Text(
                                    "点击下方按钮开始对话",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    "说话人 A: ${sourceLanguage.name}\n说话人 B: ${targetLanguage.name}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    fontSize = 12.sp
                                )
                                Text(
                                    "💡 首次使用会自动下载语音模型（约50MB）",
                                    color = MaterialTheme.colorScheme.primary,
                                    textAlign = TextAlign.Center,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                } else {
                    items(messages) { message ->
                        MessageBubble(
                            message = message,
                            sourceLang = if (message.speaker == Speaker.A) sourceLanguage else targetLanguage,
                            targetLang = if (message.speaker == Speaker.A) targetLanguage else sourceLanguage,
                            onSpeak = onSpeakTranslation
                        )
                    }
                }
            }

            // Progress message
            progressMessage?.let { progress ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = progress,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            // Status
            if (isListening) {
                Row(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        "🔴 正在听 ${currentListeningSpeaker?.name ?: ""} 说话...",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Error message
            errorMessage?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                        fontSize = 14.sp
                    )
                }
            }

            // Microphone Buttons for both speakers
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = { onStartListening(Speaker.A) },
                    modifier = Modifier
                        .weight(1f)
                        .height(72.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isListening && currentListeningSpeaker == Speaker.A) 
                            Color(0xFF1565C0) else Color(0xFFE3F2FD)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isListening
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            "🎤",
                            fontSize = 24.sp
                        )
                        Text(
                            "A (${sourceLanguage.code})",
                            color = if (isListening && currentListeningSpeaker == Speaker.A) 
                                Color.White else Color.Black,
                            fontSize = 12.sp
                        )
                    }
                }

                Button(
                    onClick = { onStartListening(Speaker.B) },
                    modifier = Modifier
                        .weight(1f)
                        .height(72.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isListening && currentListeningSpeaker == Speaker.B) 
                            Color(0xFF7B1FA2) else Color(0xFFF3E5F5)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isListening
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            "🎤",
                            fontSize = 24.sp
                        )
                        Text(
                            "B (${targetLanguage.code})",
                            color = if (isListening && currentListeningSpeaker == Speaker.B) 
                                Color.White else Color.Black,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // Clear Button
            OutlinedButton(
                onClick = { viewModel.clearConversation() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isListening
            ) {
                Text("🗑️ 清空对话")
            }

            // Permission hint
            if (!isPermissionGranted) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "⚠️ 需要麦克风权限才能使用语音输入\n请点击上方按钮授予权限",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageSelector(
    sourceLanguage: Language,
    targetLanguage: Language,
    onSourceChange: (Language) -> Unit,
    onTargetChange: (Language) -> Unit,
    onSwap: () -> Unit
) {
    var sourceExpanded by remember { mutableStateOf(false) }
    var targetExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surface,
                RoundedCornerShape(12.dp)
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ExposedDropdownMenuBox(
            expanded = sourceExpanded,
            onExpandedChange = { sourceExpanded = it },
            modifier = Modifier.weight(1f)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    "源: ${sourceLanguage.name}",
                    modifier = Modifier.padding(12.dp),
                    fontSize = 14.sp
                )
            }
            ExposedDropdownMenu(
                expanded = sourceExpanded,
                onDismissRequest = { sourceExpanded = false }
            ) {
                Languages.languages.forEach { language ->
                    DropdownMenuItem(
                        text = { Text(language.name) },
                        onClick = {
                            onSourceChange(language)
                            sourceExpanded = false
                        }
                    )
                }
            }
        }

        IconButton(
            onClick = onSwap,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                .clip(CircleShape)
        ) {
            Icon(
                Icons.Outlined.SwapHoriz,
                contentDescription = "交换语言",
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        ExposedDropdownMenuBox(
            expanded = targetExpanded,
            onExpandedChange = { targetExpanded = it },
            modifier = Modifier.weight(1f)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    "目标: ${targetLanguage.name}",
                    modifier = Modifier.padding(12.dp),
                    fontSize = 14.sp
                )
            }
            ExposedDropdownMenu(
                expanded = targetExpanded,
                onDismissRequest = { targetExpanded = false }
            ) {
                Languages.languages.forEach { language ->
                    DropdownMenuItem(
                        text = { Text(language.name) },
                        onClick = {
                            onTargetChange(language)
                            targetExpanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun MessageBubble(
    message: ConversationMessage,
    sourceLang: Language,
    targetLang: Language,
    onSpeak: (ConversationMessage) -> Unit
) {
    val backgroundColor = if (message.speaker == Speaker.A) {
        Color(0xFFE3F2FD)
    } else {
        Color(0xFFF3E5F5)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "说话人 ${message.speaker.name} (${sourceLang.name})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                IconButton(
                    onClick = { onSpeak(message) },
                    modifier = Modifier.clip(CircleShape)
                ) {
                    Text("🔊", fontSize = 16.sp)
                }
            }

            // Original text
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Text(
                    text = message.originalText,
                    modifier = Modifier.padding(8.dp),
                    fontSize = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Translated text
            Text(
                "↓ ${targetLang.name}",
                fontSize = 12.sp,
                color = Color.Gray,
                fontWeight = FontWeight.Bold
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFAFAFA))
            ) {
                Text(
                    text = message.translatedText,
                    modifier = Modifier.padding(8.dp),
                    fontSize = 16.sp,
                    color = Color.Black
                )
            }
        }
    }
}
