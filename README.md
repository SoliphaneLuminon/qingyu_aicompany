![Platform](https://img.shields.io/badge/platform-Android-green)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-blue)
![License](https://img.shields.io/badge/license-MIT-orange)
# 轻语 QingYu

一个完全本地运行的安卓 AI 陪伴应用。

---

## 30 秒速览

- **是什么**：完全本地运行的安卓 AI 陪伴应用，数据只留在手机里
- **技术栈**：Kotlin + Jetpack Compose + Material 3，原生 SQLite，`HttpURLConnection` + SSE
- **能干什么**：聊天 / 识图 / 语音 / 联网搜索 / 收藏 / 长期记忆 / 角色 / 桌宠 / JS 插件
- **数据在哪**：没有账号、没有中转服务器、没有埋点，请求从手机直连你填的接口
- **怎么装**：小白直接下 APK；开发者从源码构建（见下方"部署指南"）
- **要什么**：一个 OpenAI 兼容接口的 API Key（DeepSeek / OpenAI / Ollama 等都行）

- **包名**：`com.qingyu.app`（debug 变体为 `com.qingyu.app.debug`，可与正式版共存）
- **依赖极少**：除 Compose / AndroidX 基础库与 Gson 外没有第三方框架，没有注解处理器

---

## 一、设计取向

- **数据只留在本机**：没有账号、没有中转服务器、没有埋点，请求从手机直连你填的接口。
- **依赖尽量少**：不上 Room / Retrofit / DataStore，手写 SQLite 访问层 + `HttpURLConnection` + `SharedPreferences`。
- **能力边界是结构性的**：插件沙箱没有网络、没有文件、拿不到 `Context`，靠的是"根本没有这些入口"，而不是运行时检查。
- **失败要看得见**：任何降级（润色超时、识图回退、语音回退、整理判不出来）都要在界面上说出来，不静默吞掉。
- **参数要能自己看懂**：每个可调项旁边都有说明，讲清用法、调坏了会怎样、以及建议值——
  不指望用户去读文档或靠试错猜。

## 二、功能

| 模块 | 说明 | 亮点 |
| --- | --- | --- |
| **聊天** | OpenAI 兼容接口（DeepSeek / Ollama / LM Studio / 各类中转都行），SSE 流式 | 思考过程单独展示并折叠；一次输出按空行拆成多条短消息；可重试 / 重新生成 / 删除 / 复制 / 朗读 |
| **图片与识图** | 输入框选图发送，可多张 | 主模型多模态则图片直接随对话发出（最多 3 张）；否则用专用识图模型转成描述；同一张图只识别一次；EXIF 转正、长边 2048 存盘、发送前压到 1280 |
| **语音** | AI 合成（OpenAI 兼容 `/v1/audio/speech`）或系统 TTS 可切换 | 失败自动回退系统语音；按文本哈希缓存，重复朗读不再付费；支持**导入外部音色**——音色克隆服务给的「名称 / URI / 模型 / 参考文本」整段粘贴即可，合成时自动使用该音色自带的模型 |
| **联网搜索** | 博查 Bocha（中文）/ Tavily（英文研究向）可切换，每条消息单独开关 | 搜到的资料以"编号 + 标题 + 链接 + 摘要"注入系统提示词，并明确约束为外部内容；来源随消息落库 |
| **收藏空间** | 收藏任意消息 / 整段对话 / 随手记 | 带角色名、会话标题、标签；分类筛选 + 关键词搜索 + 单条或整包导出 |
| **记忆** | 五类长期记忆（基本资料 / 喜好 / 经历 / 关系 / 约定） | 带 1–10 重要度与启用开关；启用的以"你记得的事"注入系统提示词；可让模型自动整理 |
| **角色** | 角色卡含人格设定、情境、说话示例、开场白 | 可覆盖模型与温度；由用户自由创建 |
| **桌宠** | `WindowManager` 悬浮挂件，可拖动、点击进聊天、长按收起 | 位置记忆、开机自启；不用无障碍服务，只申请"显示在其他应用上层" |
| **模块** | 一个 zip = `manifest.json` + 入口 JS，调用宿主注入的 `qingyu.*` | 自写 JS 子集解释器；沙箱是结构性的（无网络 / 无文件 / 无反射 / 无 Context），单次运行 2 秒与指令数上限 |
| **数据** | 聊天记录导出 JSON 与 Markdown，写入 `下载/轻语` | 兼容裸数组旧备份，自动从文件名解析角色名并建角色 |
| **设置项说明** | 每个可调参数旁都有一个圆形「?」 | 点开分三段说明：**用法 / 调整影响 / 建议参数**。写清"调大了会怎样"和可直接照抄的推荐值，不用靠试错 |


## 三、部署指南

### 方式一：直接安装 APK（推荐小白）

**不需要 JDK、Android SDK、不用敲命令。** 三步：

1. **下载 APK**

   前往 [Releases 页面](https://github.com/SoliphaneLuminon/qingyu_aicompany/releases/latest)，
   下载最新的 `app-debug.apk`（约 12 MB）。

2. **安装到手机**

   - 用手机浏览器打开下载链接，或把 APK 传到手机后点击安装
   - 首次安装会提示"未知来源"，在弹窗里选择**允许来自此来源**即可
   - 安装完桌面会出现「轻语」图标

   > 如果手机提示"解析包失败"，多半是下载不完整，重新下载一次。

3. **配置并开始用**

   打开 App → 右下角 ＋ 创建角色 → 右上角 ⚙ 进设置 → 填接口地址、API Key、模型名 →
   设置页点"测一下"确认通 → 回聊天页开始聊天。

   **接口地址和 Key 都是你自己填的**，App 不含任何内置账号或密钥。

> **API Key 从哪来？** 任选一家 OpenAI 兼容服务商注册即可：
> DeepSeek（`https://api.deepseek.com/v1`）、硅基流动（`https://api.siliconflow.cn/v1`）、
> OpenAI（`https://api.openai.com/v1`），或本地跑 Ollama（`http://localhost:11434/v1`）。
> 大多数按量计费，聊天成本很低。

> ⚠️ **安全提醒**：APK 是从本项目 Releases 下载的，但**请自己申请 API Key 填进去**。
> 任何让你"用别人的 Key"的版本都不要装——那意味着你的聊天内容会经过别人的服务器。

### 方式二：从源码构建（开发者）

#### 前提

- **JDK 17+**（编译用；推荐 JDK 21 或 25）
- **Android SDK** — compileSdk 37、build-tools、platform-tools（`adb`）
- 一台 Android 手机（Android 8.0+）或模拟器
- **一个 OpenAI 兼容接口的 API key**（DeepSeek、OpenAI、硅基流动、Ollama、LM Studio 等都行）

#### 1. 获取代码

```bash
git clone https://github.com/SoliphaneLuminon/qingyu_aicompany.git qingyu
cd qingyu
```

#### 2. 构建

```powershell
# Windows PowerShell
$env:JAVA_HOME = '<你的 JDK 路径>'
$env:ANDROID_HOME = '<你的 Android SDK 路径>'

.\gradlew.bat :app:assembleDebug          # 调试包，不需要签名
.\gradlew.bat :app:testDebugUnitTest      # 验证：应输出 273 全部通过
```

| 产物 | 包名 | 说明 |
| --- | --- | --- |
| `app-debug.apk` | `com.qingyu.app.debug` | 默认构建目标，可直接安装（使用 Android 调试签名） |
| `app-release.apk` | `com.qingyu.app` | 需要自行生成签名（见下方"签名"） |

Gradle 9.5 / AGP 9.3 / Kotlin 2.2.10 / Compose BOM 2026.02.01。
AGP 9 已内置 Kotlin 支持，**不要**再应用 `org.jetbrains.kotlin.android`，否则会报扩展重名。

#### 3. 安装到手机

```bash
adb install app\build\outputs\apk\debug\app-debug.apk
```

debug 包与正式包（`com.qingyu.app`）包名不同，可以共存。

#### 4. 首次启动

1. **创建角色**：应用打开后会提示"还没有角色，先创建一个吧"。
   点击右下角 ＋，填角色名、头像 emoji、人格设定（系统提示词）、开场白。
   也可以用右上角的导入按钮，从旧聊天备份自动建角色。

2. **配置 API**：进入设置页（右上角 ⚙），填入：
   - **接口地址**：你用的 OpenAI 兼容端点，例如 `https://api.deepseek.com/v1`、
     `https://api.openai.com/v1`、`https://api.siliconflow.cn/v1`、
     `http://localhost:11434/v1`（Ollama）
   - **API Key**
   - **模型名**：例如 `deepseek-chat`、`gpt-4o`、`llama3.2`

   设置页有"测一下"按钮，可以单独验证对话、识图、语音、搜索是否通。

3. **开始聊天**：回到聊天页，选你创建的角色，发消息。

#### 5. 使用自定义音色（音色克隆）

轻语内置的是 OpenAI 那套预设音色。若想用**自己克隆的音色**，在设置页
「语音 → 音色来源选 AI 语音 → 自定义音色 → 导入音色」，把音色克隆服务给你的那一整段
信息直接粘进去即可（以 [SiliconFlow](https://docs.siliconflow.com/cn/api-reference/audio/upload-voice)
的 CosyVoice2 为例）：

```
音色名称: wansu
URI: speech:wansu:daojl2tsssvc739t29kg:dndkmulxmkworqocghrx
模型: FunAudioLLM/CosyVoice2-0.5B
参考文本: 我哪有你说的那么好啊
```

- **只有一条 URI 也能导入**，名称会自动从 URI 里取（`speech:wansu:…` → `wansu`）。
- 可同时保存多个音色，点一下即切换，右边的垃圾桶删除。
- **模型跟着音色走**：导入的音色会记住自己配套的模型，合成时自动使用它，
  而不是设置页里那个全局语音模型——音色是绑在模型上的，换模型多半就合成不出来了。
- 前提是「语音接口地址 / API Key」已填好，且该服务商支持音色克隆。

#### 6. 签名（正式发布才需要）

若要构建可上架的正式包：

```bash
keytool -genkey -v -keystore my-release.jks -alias mykey -keyalg RSA -keysize 2048 -validity 10000
```

然后在项目根目录新建 `keystore.properties`：

```properties
storeFile=my-release.jks
storePassword=<你的口令>
keyAlias=mykey
keyPassword=<你的口令>
```

```bash
.\gradlew.bat :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

`keystore.properties` 和 `*.jks` 已在 `.gitignore` 中，不会进入版本库。

## 四、测试覆盖

**20 个测试类 / 273 个用例**，全部是纯 JVM 测试（不需要模拟器）。
跑 `.\gradlew.bat :app:testDebugUnitTest` 可复现。

| 测试类 | 覆盖内容 |
| --- | --- |
| MemoryArbiterTest | 收录仲裁：判重 / 融合的解析与作废规则、人设永不被删 |
| MemoryDecayTest | 遗忘曲线：衰减系数、冷却、剪枝阈值、量化精度 |
| MemoryTripleValidatorTest | 提炼结果校验：类型宽容、越界夹紧、长度门槛、去重 |
| ChatImportParserTest | 导入解析：裸数组、字段名兼容、时间戳、JSON null、错误处理 |
| BurstAccumulatorTest | 流式拆条：每段只落库一次、逗号断句、空行边界 |
| ChatStreamParserTest | 报文解析：JSON null 不得变成文本、finish_reason、错误报文 |
| SearchResponseParserTest | 搜索解析：两家搜索的真实返回形状、错误码、请求体与地址 |
| ApiEndpointTest | 接口地址补全（对话 / 语音 / 识图共用同一份逻辑） |
| BioRhythmTest | 生物节律与时段感知 |
| ChatPayloadTest | 请求体：多模态消息形状、语音请求参数与限幅 |
| MemoryClusteringTest | 疑似重复聚类与整理分批 |
| SimpleJsEngineTest | 模块 JS 子集解释器（闭包、数组高阶方法、try/catch、死循环熔断） |
| MemoryRecallTest | 唤醒判定：预算上限、冷却、淡忘、确定性 |
| MemoryDedupTest | 收录去重的余弦兜底 |
| MemoryTextIndexTest | 倒排索引：分词、权重归一化 |
| PromptRefinerTest | 提示词润色与降级原因 |
| UtilityLlmRouterTest | 后台小模型的配置补全与可用性判定 |
| ImageScalingTest | 图片缩放：长边约束、宽高比、极端尺寸 |
| ImageTurnMatcherTest | 图片挂到哪一行（挂错了只会"答非所问"，不报错） |
| CustomVoiceParserTest | 外部音色信息解析：中英文标签、全角冒号、缺字段、非法输入 |

这几块是"输入不受我们控制"的地方——用户导出的文件、用户填的地址、用户装的插件脚本、
**上游模型返回的报文与分片方式**、以及**发送给外部接口的请求体**，
所以用纯函数 + 单元测试钉住，而不是靠手测。

### 四处"静默失效"的陷阱

这四类错误都不会抛异常、不会崩溃，只会让功能悄悄失灵，所以都必须有测试：

| 陷阱 | 症状 | 钉住它的测试 |
| --- | --- | --- |
| `optString` 把 JSON null 变成 `"null"` | 聊天里刷满 null | `ChatStreamParserTest` |
| 多模态 `content` 写成字符串而不是数组 | 模型完全看不见图 | `ChatPayloadTest` |
| 图片挂到错误的一行（系统提示词里出现同样文字时） | 模型答非所问 | `ImageTurnMatcherTest` |
| 搜索解析字段名对不上 | 联网开关开了却永远"没有来源" | `SearchResponseParserTest` |

### 拆条状态机为什么要单独抽出来

流式累积如果只往一个 StringBuilder 里追加、却不推进读位置，就会把已经落库的段落反复写一遍：

```kotlin
// 旧实现（有 bug）
pending.append(delta.text)
val segments = full.split(Regex("\n\\s*\n+"))
var index = 0
while (index < segments.size - 1) {   // ← 每次都从 0 开始，把已落库的段再写一遍
    persistBubble(segments[index])    //   而 pending 里那些段从来没被清掉
    index++
}
```

模型越守"拆成多条短消息"的约定、发得越多，重复就越多。

修复：把这段状态机抽成 [BurstAccumulator](app/src/main/java/com/qingyu/app/data/repo/BurstAccumulator.kt)，
用"每交出一段就把缓冲区切到只剩尾巴"的方式保证**每段只回调一次**。边界语义也一并定义了：

- 只有**空行**才是分段边界；边界本身被拆成两个 chunk（`"\n"` + `"\n"`）也能正确识别；
- 已经出现过边界后，尾巴后面的换行不再算内容，所以模型用单换行分条同样能识别成新消息；
- 非流式（一次收到完整回复）走 `finish()` 时才按空行拆条。

### JSON null 为什么会变成文本

聊天里刷出 `nullnullnull…` 的根因不在网络层，而在 `org.json` 的语义：

```java
// 思考型模型在思考阶段发的 chunk
{"choices":[{"delta":{"content":null,"reasoning_content":"他"}}]}
```

`JSONObject.optString("content")` 对 **JSON null 返回字符串 `"null"`**，不是空串。
于是每一段思考都额外追加一次字面量 `null`。修复做了两层：

1. **协议层（根治）**：[ChatStreamParser](app/src/main/java/com/qingyu/app/remote/ChatStreamParser.kt) 所有取字符串都走 `has(key) && !isNull(key)` 守卫，
   null、缺字段、以及被字面化的 `"null"` 一律视为"没有内容"；
2. **落库前兜底**：`PromptBuilder.stripNullArtifacts` 清掉**重复**的 null 噪音。
   判据刻意收得很紧——只认重复，因此 `nullable` / `annulment` 不会被误伤，
   正文里正常写着的 `这个字段是 null，要注意` 也原样保留。

`finish_reason` 处写了 `!= "null"` 的防御，但 `content` 与 `reasoning` 漏了。
这也说明协议解析必须集中到一处，所以解析整体抽成了 ChatStreamParser 纯函数层。

## 五、目录结构

```
app/src/main/java/com/qingyu/app/
├── AppContainer.kt              手写依赖容器（十来个依赖，不值得上 Hilt）
├── core/
│   ├── Result.kt                统一结果类型与时间工具
│   └── JsonExt.kt               org.json 的安全取值（textOrEmpty）
├── data/
│   ├── Models.kt                MessageRole / ChatMessage / Attachment / SearchHit / Persona / Favorite / MemoryItem
│   ├── Settings.kt              AppSettings + SettingsStore
│   ├── CustomVoice.kt           外部导入音色的模型与信息解析
│   ├── ApiConfig.kt             三类外部接口的地址补全与配置（对话 / 语音 / 搜索）
│   ├── db/                      DbHelper 与 Persona/Conversation/Message/Favorite/Memory/Attachment 六个 Store
│   ├── repo/                    ConversationRepository、ChatEngine、BurstAccumulator
│   └── archive/                 导入导出（含裸数组旧格式兼容）
├── remote/
│   ├── ChatClient.kt            只负责传输：连接、鉴权、读行、断开
│   ├── ChatStreamParser.kt      流式报文解析（纯函数，有测试）——JSON null 在这里被拦住
│   ├── ChatPayload.kt           请求体构造 + 多模态消息形状 + 识图提示词
│   ├── VisionClient.kt          识图：图 → 文字描述
│   ├── SearchAndSpeechClient.kt 联网搜索与语音合成的传输层
│   ├── SearchResponseParser.kt  两家搜索结果的解析与请求构造
│   └── PromptBuilder.kt         人格 / 记忆 / 历史 / 联网资料 → 请求；以及拆条约定
├── media/ImageStore.kt          图片落盘、EXIF 转正、缩放、data URL 编码
├── modules/                     ModuleManager、SimpleJsEngine、ModuleHost
├── pet/                         PetService、BootReceiver
├── tts/                         TextToSpeechEngine（系统语音）、SpeechPlayer（AI 合成 + 回退）
└── ui/
    ├── components/Common.kt     通用组件（头像、空状态、气泡外框、强调条…）
    ├── components/HelpDot.kt    圆形「?」入口与三段式说明弹层
    ├── settings/HelpTexts.kt    全部设置项的说明文案（用法 / 影响 / 建议值）
    └── …                        主题、聊天 / 收藏 / 记忆 / 设置 / 角色 / 模块 / 搜索 / 调试页
```

## 六、三个值得说明的设计决定

1. **不用 Room / Retrofit / DataStore**。表结构稳定、接口只有一种形态、设置项很少，
   手写 SQLite DAO + `HttpURLConnection` + `SharedPreferences` 反而更小更快，
   也避免了 KSP 与 Kotlin 版本互相卡脖子。代价是迁移要自己写——`DbHelper.onUpgrade` 里留了位置
   （v1→v2 就是这么加的图片附件与搜索来源）。

2. **模块的 JS 解释器是自己写的**。QuickJS 与 Rhino 都会带来数 MB 的原生库，
   而模块只需要"能跑一小段逻辑"。自己实现意味着能力边界是**结构性的**而不是靠检查：
   没有 eval、没有原型链、拿不到 Context，也就没有网络与文件。

3. **三类外部能力共用一个"接口配置 + 客户端 + 纯函数解析"的形状**。
   识图、语音、搜索本质上都是"另一个 OpenAI 兼容接口"，所以：
   地址补全（[ApiEndpoint](app/src/main/java/com/qingyu/app/data/ApiConfig.kt)）、
   安全取值（[JsonExt](app/src/main/java/com/qingyu/app/core/JsonExt.kt)）、
   错误摘要、Bearer 鉴权全部复用。
   换来的是：用户可以用 DeepSeek 聊天 + 硅基流动识图 + OpenAI 语音 + 博查搜索，
   而每一类都能在设置页独立测试。

## 七、开发文档

- [参数与版本管理](docs/参数与版本管理.md)：版本号、参数分层与新增参数规范。
- [模块地图与扩展指南](docs/模块地图.md)：分层职责、一次对话数据流、新增功能扩展点。

## 八、已知边界

  识图、语音合成、联网搜索都要联网，而**不同网关的实际行为差异很大**，我只能保证协议实现正确，
  无法保证你的服务商一定匹配。设置页给每项都配了"测试"按钮，请先点一遍再聊天。
- 主动消息（定时推送）目前只有设置项，后台调度尚未接入 `WorkManager`。
- 联网是"开关 + 一次性检索"，不是 tool calling：模型不能自己决定再搜一次。
  这样实现简单、行为可预期，但追问式检索（"再查查别的"）需要用户手动再发一条。
- 识图回退链只做一层：主模型不支持看图时用**一个**识图模型。没有做多模型投票或失败重试。
- 语音合成按整段文本一次请求，没有做流式播放（首字延迟取决于服务商）。
- 没有实现本地 HTTP 外部接入与无障碍桌宠陪伴——前者是给外部程序开洞，
  后者需要"读取屏幕内容"级别的权限，都与轻语"数据只留在本机"的取向冲突。

## 九、常见问题（FAQ）

**Q：APK 装不上，提示"解析包失败"？**
A：多半是下载不完整。重新下载；或确认手机是 Android 8.0 及以上。

**Q：装的时候提示"未知来源"？**
A：这是 Android 对非商店 APK 的正常拦截。在弹窗里点"允许来自此来源"即可。

**Q：聊天没反应 / 报错？**
A：进设置页点"测一下"。90% 是接口地址、Key 或模型名填错。注意接口地址要带 `/v1`。

**Q：会扣费吗？**
A：App 本身完全免费、无内购。但聊天走你自己的 API Key，费用由你的服务商按量收取（通常极低）。

**Q：聊天记录会上传吗？**
A：不会。App 没有账号、没有埋点、没有中转服务器，所有数据存在手机本地。
只有你发消息时，内容才会直接发给你自己配置的接口。

**Q：怎么卸载干净？**
A：直接卸载 App 即可，数据库和图片都在应用私有目录，卸载即清。
