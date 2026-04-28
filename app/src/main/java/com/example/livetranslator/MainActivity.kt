package com.example.livetranslator

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
                            // TTS 功能
                        },
                        onManageModels = {
                            viewModel.showModelManager.value = true
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
                    viewModel.updateDownloadProgress(progress)
                }
            }
        )
        offlineRecognizer?.initialize()
        
        // 更新已下载的模型列表
        runOnUiThread {
            viewModel.updateDownloadedModels(offlineRecognizer?.getDownloadedModels() ?: emptyList())
        }
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
    val downloadProgress = MutableStateFlow<OfflineSpeechRecognizer.DownloadProgress?>(null)
    val downloadedModels = MutableStateFlow<List<String>>(emptyList())
    val showModelManager = MutableStateFlow(false)

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
        downloadProgress.value = null
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
        downloadProgress.value = null
    }

    fun updateDownloadProgress(progress: OfflineSpeechRecognizer.DownloadProgress) {
        downloadProgress.value = progress
    }
    
    fun onModelDownloadComplete() {
        // 由 MainActivity 调用更新
    }

    fun updateDownloadedModels(models: List<String>) {
        downloadedModels.value = models
    }

    fun clearConversation() {
        messages.value = emptyList()
        nextId = 0
        errorMessage.value = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslatorScreen(
    viewModel: TranslationViewModel,
    onStartListening: (Speaker) -> Unit,
    onSwapLanguages: () -> Unit,
    onSpeakTranslation: (ConversationMessage) -> Unit,
    onManageModels: () -> Unit
) {
    val messages by viewModel.messages.collectAsState()
    val sourceLanguage by viewModel.sourceLanguage.collectAsState()
    val targetLanguage by viewModel.targetLanguage.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val currentListeningSpeaker by viewModel.currentListeningSpeaker.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val isPermissionGranted by viewModel.isPermissionGranted.collectAsState()
    val showModelManager by viewModel.showModelManager.collectAsState()
    val downloadedModels by viewModel.downloadedModels.collectAsState()

    if (showModelManager) {
        ModelManagerDialog(
            downloadedModels = downloadedModels,
            onDismiss = { viewModel.showModelManager.value = false },
            onDownload = { languageCode ->
                offlineRecognizer?.downloadModel(languageCode)
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🗣️ 实时对话翻译 (离线版)") },
                actions = {
                    IconButton(onClick = onManageModels) {
                        Icon(
                            Icons.Outlined.Download,
                            contentDescription = "管理模型"
                        )
                    }
                }
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

            // Download Progress
            downloadProgress?.let { progress ->
                DownloadProgressCard(progress)
            }

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
                                Text("🎤", fontSize = 48.sp)
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
                                if (downloadedModels.isEmpty()) {
                                    Text(
                                        "💡 首次使用需要下载语音模型",
                                        color = MaterialTheme.colorScheme.primary,
                                        textAlign = TextAlign.Center,
                                        fontSize = 11.sp
                                    )
                                    Text(
                                        "点击右上角 ⬇️ 管理模型",
                                        color = MaterialTheme.colorScheme.primary,
                                        textAlign = TextAlign.Center,
                                        fontSize = 11.sp
                                    )
                                }
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

            // Microphone Buttons
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
                    enabled = !isListening && downloadProgress?.state != OfflineSpeechRecognizer.DownloadProgress.State.DOWNLOADING
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("🎤", fontSize = 24.sp)
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
                    enabled = !isListening && downloadProgress?.state != OfflineSpeechRecognizer.DownloadProgress.State.DOWNLOADING
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("🎤", fontSize = 24.sp)
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

@Composable
fun DownloadProgressCard(progress: OfflineSpeechRecognizer.DownloadProgress) {
    val progressFloat = progress.progress / 100f
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (progress.state) {
                OfflineSpeechRecognizer.DownloadProgress.State.ERROR -> 
                    MaterialTheme.colorScheme.errorContainer
                OfflineSpeechRecognizer.DownloadProgress.State.READY -> 
                    Color(0xFFE8F5E9)
                else -> 
                    MaterialTheme.colorScheme.secondaryContainer
            }
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (progress.state) {
                OfflineSpeechRecognizer.DownloadProgress.State.CHECKING -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("🔍 检查网络连接...")
                    }
                }
                
                OfflineSpeechRecognizer.DownloadProgress.State.DOWNLOADING -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "📥 下载语音模型",
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "${progress.progress}%",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    
                    LinearProgressIndicator(
                        progress = { progressFloat },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "${progress.downloadedMB.toFixed(1)} / ${progress.totalMB.toFixed(1)} MB",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                        if (progress.speedKBps > 0) {
                            Text(
                                "${progress.speedKBps.toFixed(0)} KB/s",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                        if (progress.etaSeconds > 0) {
                            Text(
                                "剩余 ${formatTime(progress.etaSeconds)}",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                    }
                }
                
                OfflineSpeechRecognizer.DownloadProgress.State.EXTRACTING -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("📦 解压模型文件...")
                    }
                }
                
                OfflineSpeechRecognizer.DownloadProgress.State.LOADING -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("🔄 加载语音模型...")
                    }
                }
                
                OfflineSpeechRecognizer.DownloadProgress.State.READY -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            "✅ 模型就绪！可以开始使用",
                            color = Color(0xFF4CAF50),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                
                OfflineSpeechRecognizer.DownloadProgress.State.ERROR -> {
                    Text(
                        text = progress.error,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                
                else -> {}
            }
        }
    }
}

@Composable
fun ModelManagerDialog(
    downloadedModels: List<String>,
    onDismiss: () -> Unit,
    onDownload: (String) -> Unit
) {
    val selectedModels = remember { mutableStateListOf<String>() }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("📥 管理语音模型") },
        text = {
            Column {
                Text(
                    "选择需要下载的语言模型",
                    fontSize = 14.sp,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "💡 每个模型约 40-50MB，建议只下载需要的语言",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                OfflineSpeechRecognizer.MODELS.forEach { (code, info) ->
                    val isDownloaded = downloadedModels.contains(code)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = selectedModels.contains(code) || isDownloaded,
                            onCheckedChange = { checked ->
                                if (!isDownloaded) {
                                    if (checked) {
                                        selectedModels.add(code)
                                    } else {
                                        selectedModels.remove(code)
                                    }
                                }
                            },
                            enabled = !isDownloaded
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${info.displayName} (${code})",
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "约 ${info.sizeMB.toInt()} MB",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                        if (isDownloaded) {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = "已下载",
                                tint = Color(0xFF4CAF50)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    selectedModels.forEach { code ->
                        onDownload(code)
                    }
                    selectedModels.clear()
                    onDismiss()
                },
                enabled = selectedModels.isNotEmpty()
            ) {
                Text("下载选中 (${selectedModels.size})")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
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
            }

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

// 辅助函数
fun Float.toFixed(decimals: Int): String {
    return String.format("%.${decimals}f", this)
}

fun formatTime(seconds: Int): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return if (mins > 0) "${mins}分${secs}秒" else "${secs}秒"
}
