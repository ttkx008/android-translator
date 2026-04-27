# 实时对话翻译应用 - Live Translator

安卓原生实时对话翻译应用，支持语音输入和语音输出，适合面对面两个人不同语言交流使用。

## 功能特性

- 🎤 **语音输入**：使用 Android 内置语音识别 API
- 🔊 **语音输出**：使用 Android 内置文字转语音（TTS）
- 🌍 **多语言支持**：中文、英文、日语、韩语、西班牙语、法语、德语、俄语
- 🔄 **实时对话**：两个人轮流说话，自动翻译
- 🌐 **真实翻译 API**：集成了 [LibreTranslate](https://libretranslate.com/) 免费开源翻译 API
- 🎨 **现代化 UI**：使用 Jetpack Compose + Material Design 3
- 💱 **语言交换**：一键交换源语言和目标语言

## 技术栈

- **语言**：Kotlin
- **UI 框架**：Jetpack Compose
- **设计系统**：Material Design 3
- **语音识别**：Android Speech Recognition API（系统内置，无需额外 SDK）
- **文字转语音**：Android TextToSpeech（系统内置）

## 项目结构

```
app/
├── src/main/
│   ├── java/com/example/livetranslator/
│   │   ├── MainActivity.kt           # 主界面
│   │   ├── ConversationMessage.kt    # 消息数据类
│   │   ├── Languages.kt              # 语言定义
│   │   ├── TranslationManager.kt    # 语音识别/翻译/TTS 管理
│   │   └── ui/theme/                # 主题相关
│   ├── res/
│   │   ├── values/                  # 颜色、字符串、主题
│   │   └── AndroidManifest.xml      # 应用配置
├── build.gradle.kts                 # 应用构建配置
└── ...
```

## 构建说明

1. 需要 Android Studio Hedgehog | 2022.1.1+
2. 需要 Android SDK API 34
3. 使用 Gradle 8.2+ 构建
4. 项目依赖已全部配置完成，sync 后即可构建

## 使用说明

## 翻译 API

本应用已集成 **LibreTranslate** 免费开源翻译 API，使用公共实例 `https://translate.argosopentech.com/`，开箱即用，**不需要 API Key**！

LibreTranslate 是开源免费的翻译服务，支持多种语言，对个人非商业使用免费。

如果你想要自托管或者使用其他 API，可以在 `LibreTranslateApi.kt` 中修改 API URL，或者换成 DeepL/Google Translate/百度翻译等其他服务。

### 权限

- **RECORD_AUDIO**：需要麦克风权限进行语音输入
- **INTERNET**：如果使用在线翻译 API 需要联网权限（已配置）

## 使用方法

1. 打开应用，授予麦克风权限
2. 选择你和对方的语言（默认：你说中文，对方说英文）
3. 你说话按 A 按钮，对方说话按 B 按钮
4. 说完之后会自动识别并翻译，点击 🔊 可以播放语音
5. 点击 **交换语言** 按钮可以一键交换双方语言

## 截图说明

（构建后可添加截图）

## 许可证

Apache 2.0