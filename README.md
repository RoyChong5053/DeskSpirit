# DeskSpirit

桌面 AI 伙伴 · v0.1。一台旧手机放在桌上，**常驻监听**，说完一句就自动回你（还会出声）。

- **上半屏**：科幻感的 3D 玻璃 / 体积云圆球，随**音频频谱**波动（Jarvis 电影感）。
- **下半屏**：`你：…` 的识别结果 + AI 回复。
- **全 API 化**：本地不跑任何大模型，LLM / ASR / TTS 都在云端。
- **设置页**：填三组 API（URL / Key / Model / Voice），每组都有 **测试**。

> 不做 lip sync、不做人脸。音频拾取与轮次的交互逻辑参考 MNN TaoAvatar，
> 但**不引入任何 MNN 模型**。

## 一轮对话是怎么走的

```
常驻麦克风 ──► 本地 VAD 判端点（一句话说完）──► 关麦（轮次互斥）
                                                    │
                                                    ▼
                              ASR /v1/audio/transcriptions  → 文字
                                                    │
                              LLM /v1/chat/completions（流式，边出边显示）
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

## 已知事项

- **明文 HTTP**：`http://` 的 LLM 需要 `usesCleartextTraffic`（已在 manifest 里打开）。
- **首字延迟**：如果 LLM 走的是会「路由 + 推理」的聚合模型（如 `auto-agent`），
  首字可能要 10s 以上。想要更快，请在网关里放行一个直连的快速模型
  （如 `gemini-2.5-flash` / `nemotron-3.5-lightning`）。
- ASR/TTS 用的是 OpenRouter 的 audio 端点，实测 ASR 返回 `{"text":...}`，
  TTS 返回 `audio/pcm;rate=24000;channels=1`。

## 构建 / 安装

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 下一步

- [ ] 打断（播放中说话就停掉，而不是等它说完）
- [ ] 半分辨率渲染（更低端机型）
- [ ] 唤醒词（现在是一有话说就接）
- [ ] 表情 / 状态环等更细腻的视觉
