# DeskSpirit

桌面 AI 伙伴 · v0.1。一台旧手机放在桌上，**常驻监听**，说完一句就自动回你（还会出声）。

- **上半屏**：科幻感的 3D 玻璃 / 体积云圆球，随**音频频谱**波动（Jarvis 电影感）。
- **下半屏**：`你：…` 的识别结果 + AI 回复（原文，含动作描写）。
- **全 API 化**：本地不跑任何大模型，LLM / ASR / TTS 都走接口。
- **设置页**：填三组 API（URL / Key / Model / Voice），每组都有 **测试**；LLM 有 **TavernLab 模式**开关。

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
| `audio/Wav.kt` | PCM16 → WAV（喂给 ASR） |
| `render/SphereRenderer.kt` | 全屏 raymarching 着色器，无 3D 引擎依赖 |

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

## 口播清洗

角色回复常带 `（*动作描写*）`、`**markdown**`、emoji / 颜文字，
直接念出来很怪。`conversation/SpeechText.kt` **只清洗送进 TTS 的那一份**，
屏幕上的 `tvReply` 仍显示原文（动作描写是角色魅力的一部分）。

实测：原文 644 字 → 清洗后 387 字。

## 已知事项

- **明文 HTTP**：`http://` 的 LLM / TavernLab 需要 `usesCleartextTraffic`（已在 manifest 打开）。
- **回复偏长（首要待优化）**：实测一轮角色回复 644 字，清洗后仍 387 字，
  TTS 出来约 **64 秒**。这期间轮次互斥、麦克风关闭，桌面上就是「它一个人讲了 1 分钟」。
  解法见「下一步」。
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

- [ ] **逐句流式 TTS**：LLM 一边出字，一遇到 `。！？` 就发一小段 TTS 并排队播放，
      首声延迟从「整段 LLM + 整段 TTS」降到「第一句」（现在一轮要等 60s+ 才开口）
- [ ] TavernLab 侧「语音模式」：语音端请求时回复 1–2 句、无动作描写
- [ ] 打断（播放中说话就停掉，而不是等它说完）
- [ ] 唤醒词（现在是一有话说就接）
- [ ] 半分辨率渲染（更低端机型）
- [ ] 球体跟着角色情绪变（TavernLab 有 `/api/expression/classify`）
