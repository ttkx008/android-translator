package com.example.livetranslator

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.livetranslator.ui.theme.LiveTranslatorTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class MainActivity : ComponentActivity() {
    private val viewModel = TranslationViewModel()
    private var translationManager: TranslationManager? = null

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
        initTranslationManager()

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
                            translationManager?.speak(
                                message.translatedText,
                                if (message.speaker == Speaker.A) viewModel.targetLanguage.value else viewModel.sourceLanguage.value
                            )
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

    private fun initTranslationManager() {
        translationManager = TranslationManager(
            context = this,
            onResult = { text, isFinal ->
                if (isFinal) {
                    viewModel.onSpeechRecognized(text)
                    // Translate (for demo, we just swap languages - in production, call translation API)
                    translateText(text)
                }
            },
            onError = { error ->
                viewModel.onError(error)
            },
            onListeningStart = {
                viewModel.listeningStarted()
            }
        )
    }

    private fun startListeningForSpeaker(speaker: Speaker) {
        if (!viewModel.isPermissionGranted.value) {
            checkPermissions()
            return
        }

        val language = if (speaker == Speaker.A) viewModel.sourceLanguage.value else viewModel.targetLanguage.value
        translationManager?.startListening(language)
        viewModel.setCurrentListeningSpeaker(speaker)
    }

    private fun translateText(text: String) {
        currentListeningSpeaker.value?.let { speaker ->
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

    override fun onDestroy() {
        super.onDestroy()
        translationManager?.shutdown()
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

    private var nextId = 0L

    fun permissionGranted() {
        isPermissionGranted.value = true
        errorMessage.value = null
    }

    fun permissionDenied() {
        isPermissionGranted.value = false
        errorMessage.value = "麦克风权限被拒绝，无法使用语音输入"
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
    }

    fun listeningStarted() {
        isListening.value = true
    }

    fun onSpeechRecognized(text: String) {
        isListening.value = false
        currentListeningSpeaker.value?.let { speaker ->
            // Add the original text
            val message = ConversationMessage(
                id = nextId++,
                speaker = speaker,
                originalText = text,
                translatedText = "Translating..."
            )
            messages.update { it + message }
        }
        errorMessage.value = null
    }

    fun translateCurrentText(translatedText: String) {
        currentListeningSpeaker.value?.let { speaker ->
            // The last message is the one we just added with "Translating..."
            // Update it with the real translated text
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

    private fun getMockTranslation(text: String, targetLang: Language): String {
        // For this demo, just return placeholder text
        return "[Translated to ${targetLang.name}]: $text"
    }

    fun onError(error: String) {
        isListening.value = false
        errorMessage.value = error
    }

    // For demo only - in production replace with real translation API
    private fun getMockTranslation(text: String, targetLang: Language): String {
        // In real app: call API here
        // For this demo, just return placeholder text that indicates where translation would be
        return "[Translated to ${targetLang.name}]: $text"
    }

    fun clearConversation() {
        messages.value = emptyList()
        nextId = 0
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
    val isPermissionGranted by viewModel.isPermissionGranted.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("实时对话翻译") }
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
                            Text(
                                "点击下方按钮开始对话\n\n说话人 A: ${sourceLanguage.name}\n说话人 B: ${targetLanguage.name}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
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

            // Status
            if (isListening) {
                Text(
                    "🔴 正在听... (${currentListeningSpeaker?.name ?: ""})",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }

            errorMessage?.let {
                Text(
                    "⚠️ $it",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }

            // Microphone Buttons for both speakers
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = { onStartListening(Speaker.A) },
                    modifier = Modifier.weight(1f)
                        .height(64.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE3F2FD)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally)
                    Text(
                        "🎤\nA (${sourceLanguage.code})",
                        color = Color.Black,
                        fontSize = 14.sp
                    )
                }

                Button(
                    onClick = { onStartListening(Speaker.B) },
                    modifier = Modifier.weight(1f)
                        .height(64.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF3E5F5)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally)
                    Text(
                        "🎤\nB (${targetLanguage.code})",
                        color = Color.Black,
                        fontSize = 14.sp
                    )
                }
            }

            // Clear Button
            OutlinedButton(
                onClick = { viewModel.clearConversation() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("清空对话")
            }

            if (!isPermissionGranted) {
                Text(
                    "⚠️ 需要麦克风权限才能使用语音输入",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
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
                    "源语言: ${sourceLanguage.name}",
                    modifier = Modifier.padding(12.dp)
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
                contentDescription = "Swap languages",
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
                    "目标语言: ${targetLanguage.name}",
                    modifier = Modifier.padding(12.dp)
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
                    modifier = Modifier
                        .clip(CircleShape)
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