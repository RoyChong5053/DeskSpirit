# DeskSpirit

桌面 AI 伙伴 · v0.1。一台旧手机放在桌上，**常驻监听**，说完一句就自动回你（还会出声）。

- **上半屏**：科幻感的 3D 玻璃 / 体积云圆球，随**音频频谱**波动（Jarvis 电影感）。
- **下半屏**：`你：…` 的识别结果 + AI 回复。流式阶段显示原文；开始播报后变成
  **KTV 字幕**——只显示真正念出来的文本，随播放进度逐字高亮、自动滚动。
- **全 API 化**：本地不跑任何大模型，LLM / ASR / TTS 都走接口。
- **四个服务槽位 + profile**：LLM 对话 / 工具 LLM / ASR / TTS 各自独立配置，可云端本地混搭
  （如 ASR 跑本地 ggml、TTS 跑云端）；每个槽位可存多组命名 profile 并切换当前使用的，
  支持 **测试** 与 **拉取模型列表**（`GET /v1/models`）。
- **交互三态**：Live（一句话自动回）、**口述**（长文，手动停 + 可编辑 + 可整理）、重放。
- **语音库**：TTS 生成后落盘，可重放 / 收藏 / 删除 / 自动清理，目录可被任意文件管理器访问。

> 不做 lip sync、不做人脸。音频拾取与轮次的交互逻辑参考 MNN TaoAvatar，
> 但**不引入任何 MNN 模型**。

## 一轮对话是怎么走的

```
常驻麦克风 ──► 本地 VAD 判端点（一句话说完）──► 关麦（轮次互斥）
                                                    │
                                                    ▼
                              ASR /v1/audio/transcriptions  → 文字
                                                    │
             LLM /v1/chat/completions（流式；TavernLab 模式只发当前这一句）
                                                    │
                              口播清洗（剥掉 *动作* / markdown / emoji）
                                                    │
                              TTS /v1/audio/speech  → PCM/MP3
                                                    │
                                       本地播放（球体跟着 AI 的声音跳）──► 开麦
```

轻触屏幕 = 暂停/继续监听。

## 本地部分（旧手机也扛得住）

| 模块 | 干什么 |
|---|---|
| `audio/SpeechInput.kt` | 麦克风采集 + 噪声地板 + 端点检测（32ms 帧，静音 0.9s 收尾，最长 15s） |
| `audio/Spectrum.kt` | Hann 窗 + radix-2 FFT + 自适应归一化（bass/mid/treble） |
| `audio/AudioPlayer.kt` | TTS 播放：`audio/pcm` 走 AudioTrack，其它走 MediaPlayer |
| `audio/Wav.kt` | PCM16 → WAV（喂给 ASR / 落盘语音库） |
| `render/SphereRenderer.kt` | 全屏 raymarching 着色器，无 3D 引擎依赖 |
| `config/AudioSettings.kt` | 收音 / 口述 / 语音库 / 整理 prompt 的本地参数 |
| `conversation/DictationController.kt` | 口述（2-pass）：长聆听 + 整段 ASR |
| `conversation/TextRefiner.kt` | 整理（3-pass）：调用工具 LLM |
| `voice/VoiceStore.kt` | 语音落盘 / 列表 / 删除 / 收藏 / 自动清理 |

## 云端部分

| 模块 | 接口 |
|---|---|
| `net/AsrClient.kt` | `POST /audio/transcriptions`（multipart 上传 wav） |
| `net/LlmClient.kt` | `POST /chat/completions`，`stream:true`，解析 SSE |
| `net/TtsClient.kt` | `POST /audio/speech`，从 `Content-Type` 判断播放方式 |
| `net/ApiTester.kt` | 设置页的测试 = 真实调用这三个接口 |

**URL 三种写法都兼容**：`https://api.xxx.com`、`https://api.xxx.com/v1`、
或直接给完整端点 `https://openrouter.ai/api/v1/audio/speech`。

## LLM 的两种模式

设置页 LLM 区的开关决定请求形态：

| 模式 | 请求体 | 上下文 / 记忆 |
|---|---|---|
| **TavernLab（默认）** | `{"text":"这一句","stream":true}` | **服务端负责** |
| 通用 OpenAI | `{"messages":[…]}` | 客户端自己带 |

TavernLab 模式下，角色卡、Context Budget（16384−4096）、RAG 检索、蒸馏日记
全在 TavernLab 服务端；DeskSpirit **不发 `messages`、不保存任何历史**，只把
当前这一句转发出去。多轮记忆、角色不失忆都归服务端管。

TavernLab 模式配置要点：

- **Base**：`http://<tavernlab-host>:8888/v1`
- **Key**：TavernLab 的 **app_token**（不是 one-api 的 key）
- **不要传 `session`**：留空才会落到服务端 `current_char`，
  角色卡与记忆集合（collection）才会正确解析

通用 OpenAI 模式则自己维护一小段历史：**system 钉在 index 0 永不淘汰**，
只按 `user+assistant` 成对淘汰，最多 8 轮（保证窗口永远以 user 开头）。

## 口播清洗（`conversation/SpeechText.kt`）

角色卡（以及 Gemini 风格的模型）会这样写「舞台提示」：

| 写法 | 含义 | 处理 |
|---|---|---|
| `（*她轻轻笑了笑*）` | 全角括号 + 星号的动作描写 | 丢掉 |
| `*她轻轻笑了笑*` | 星号动作描写 | 丢掉 |
| `（她轻轻笑了笑）` | 全角括号旁白 | 丢掉 |
| `(¬‿¬)` | 半角括号里的颜文字 | 丢掉 |
| `**加粗**` | **往往是台词本体**（角色爱把重点句加粗） | **脱壳保留文字** |

最后一行是坑：一开始把 `**…**` 整段删掉，结果**把主台词删没了**
（开头那句 `**哦～原来一睁眼的『开机仪式』是先摸手机啊？**` 直接消失，
列表里的 `**别急着刷消息！**` 也只剩一个 `1.`）。现在只脱壳、不删文字。

清洗顺序：脱 `**加粗**` / `_斜体_`（保文字）→ 丢 `*动作*` / `（描写）` / `(颜文字)`
→ 丢分隔线 `---` → 丢标题 / 引用 / 有序无序列表标记 → 箭头 `→` 转顿号
→ 最后走一遍**白名单**（只留汉字、字母数字、常见标点），emoji 与颜文字碎片
（`ﾉ` `▽` `✧`）一并清掉。

**只清洗送进 TTS 的那一份**，屏幕上的 `tvReply` 仍显示原文。

实测：原文 752 字 → 清洗后 538 字。

## KTV 字幕

文字区是一块**固定高度（180dp）的可滚动区域**，长回复不再被 `maxLines` 裁掉。

- **流式阶段**：显示原文（含动作描写），自动跟到底部；
- **开始播报**：换成**口播清洗后**的文本，按播放进度逐字高亮
  （没念到的暗、念过的亮青），并把当前行滚到可视区偏上的位置。

两个关键设计：

1. **高亮打在清洗后的文本上，而不是原文上。** 原文里那些动作描写不占播放时间，
   按它来分进度会越走越偏（一句 60 字的舞台提示会把高亮拖后好几秒）。
2. **进度用真实播放位置**（`AudioTrack.playbackHeadPosition`），再用一张
   「按标点加权」的字符权重表映射成字符下标：`。！？` 后面给 3.5 倍权重、
   `，、；：` 给 1.5 倍，因为 TTS 在这些地方会自然停顿。比按字数平均分准得多。

（`MediaPlayer` 分支用 `currentPosition / duration`，同样处理。）

## 多 profile 配置（云端 + 本地混搭）

`config/ApiConfig.kt` 从「单套」升级为**每槽位一组命名端点**：

| 槽位 | 用途 |
|---|---|
| `LLM 对话` | 角色对话（可 TavernLab / 普通 OpenAI） |
| `工具 LLM` | 口述整理（3-pass）；**必须是普通 OpenAI 兼容端点**，不支持 TavernLab |
| `ASR` | 语音识别 |
| `TTS` | 语音合成（带 voice） |

- 每个槽位一组 `Endpoint(name, base, key, model, voice, mode)`，选一个"当前使用"。
- 设置页顶部四个按钮切换槽位；下拉选 profile，`＋/复制/删除` 管理，长按下拉可重命名。
- **旧版单套配置会自动迁移**成名为「默认」的 profile，不用重填。
- **拉取模型**：`GET /v1/models` 列出可用 model id，点选即填。
- 本仓库默认预置本机 LAN 的 ggml 三件套（可在设置里改）：
  - ASR `http://192.168.100.200:11438/v1`（speech.cpp，`/v1/audio/transcriptions`）
  - TTS `http://192.168.100.200:11439/v1`（audio.cpp，model `qwen3-tts`，voice 预设 `serena`）
  - LLM `http://192.168.100.200:12888/v1`（llama.cpp `llama-server`）

## 收音参数（可调）

`config/AudioSettings.kt` 存一组 `VadParams`，`audio/SpeechInput.kt` 构造时注入，
`ConversationController` 每次开麦都读最新值。设置页「收音 / VAD」：

| 参数 | 含义 |
|---|---|
| 结束静音 (ms) | 说完后静音多久算一句（默认 900） |
| 绝对门限 RMS | 环境底噪一般远低于它（默认 0.012） |
| 相对噪声倍数 | 比噪声地板高这么多倍才算说话（默认 3.0） |
| 最长一句 (ms) | 超了强制切（默认 15000） |
| 最少有声 / 占比 / 最短有效 | 过滤环境噪声 |

## 三种输入模式

| 模式 | 行为 |
|---|---|
| **Live（1-pass）** | 常驻 VAD，一句话说完自动 ASR → LLM → TTS |
| **口述（2-pass）** | 点「🎙 口述」开始长时间聆听（VAD 不切句，停顿无妨）→ 点停止 → 整段 ASR → **可编辑**，可保存/朗读/发送给伙伴 |
| **整理（3-pass）** | 口述结果上「整理」→ 发给**工具 LLM**，用可编辑 prompt 模板（`AudioSettings.DEFAULT_REFINE_PROMPT`）→ 可撤销、可反复 |

「发送给伙伴」把编辑后的文本直接送进角色对话（跳过 ASR）；「重录」丢弃当前结果重新录。

## 语音库与重放

- `voice/VoiceStore.kt` 把 TTS 生成（以及可选的 Live 用户录音、口述录音）落盘：
  - 目录 `/sdcard/Android/media/<pkg>/DeskSpirit/voice/` —— **文件管理器可读，无需任何权限**；
    不可用时退回私有 `filesDir/voice/`。
  - 每条 = 音频（PCM 包成 WAV，其余原样）+ 同名 `.json` sidecar（时间/文本/model/voice/profile/时长/收藏）。
- 「🗂 语音库」页：播放、⭐收藏、删除、清空（保留收藏）；「打开目录」可复制路径交给外部文件管理器导出。
- **重放**：主界面「⟲ 重放」重播最近一条 AI 语音（同样关麦防回声）。不存档时也保留内存副本可重放。
- 自动清理：超出「保留条数 / 天数」的非收藏项会被删（`ConversationController.pruneQuietly`）。

## 已知事项

- **明文 HTTP**：`http://` 的 LLM / TavernLab 需要 `usesCleartextTraffic`（已在 manifest 打开）。
- **回复偏长（首要待优化）**：实测角色回复 490–752 字，清洗后 315–538 字，
  TTS 出来 **60–95 秒**。这期间轮次互斥、麦克风关闭，桌面上就是「它一个人讲了 1 分半」。
  这是**内容长度**问题（角色卡风格），不是清洗问题；解法见「下一步」。
- **超时策略：故意不设激进超时**。TavernLab 模式下这个连接要先等服务端做完
  RAG 检索 + 上下文组装才回响应头，而 embedding / rerank 可能落在 one-api 的
  **CPU channel** 上（RTX 4060 在跑别的任务时就是如此）。所以 LLM 的 `readTimeout`
  给到 30 分钟——宁可等，也不要让本来能跑通的一轮失败。等待期间状态栏会走秒
  （`思考中… 12s`），看得出它还在干活，不是卡死。
- **首字延迟**：LLM 走聚合/推理模型时首字可能十几秒（TavernLab 还会先做 RAG 检索）。
  实测首字 ~19s、整轮 ~20s。想要快就在网关里放行一个直连快模型。
- **TavernLab 模式会写进正史**：说的话会出现在该角色的聊天记录里（这是预期行为）。
- **app_token 明文存在 SharedPreferences**：后续可换 `EncryptedSharedPreferences`。
- ASR/TTS 用的是 OpenRouter 的 audio 端点，实测 ASR 返回 `{"text":…}`，
  TTS 返回 `audio/pcm;rate=24000;channels=1`。

## 构建 / 安装

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 下一步

已完成（本次）：多 profile 混搭、收音参数可调、语音留存/重放/导出、口述（2-pass）+ 整理（3-pass）。

- [ ] **逐句流式 TTS**（先搁置，当前优先「稳定可用」）：LLM 一边出字、一遇到 `。！？`
      就发一小段 TTS 排队播放。现在要等「整段 LLM + 整段 TTS」才开口（60–95s）；
      改完后听到第一句的时间能砍到 ~1/3。**纯客户端改动，不动 TavernLab。**
- [ ] 打断（播放中说话就停掉，而不是等它说完）
- [ ] 唤醒词（现在是一有话说就接）
- [ ] 半分辨率渲染（更低端机型）
- [ ] 球体跟着角色情绪变（TavernLab 有 `/api/expression/classify`）
