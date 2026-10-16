# DeskSpirit

桌面 AI 伙伴 · v0.1 概念 Demo。

一台旧手机放在桌上，**全 API 化**（本地不跑大模型）：

- **上半屏**：科幻感的 3D 玻璃 / 体积云圆球，随**音频频谱**波动（Jarvis 电影感）。
- **下半屏**：文字输出（AI 回复）。
- **听你说话** → ASR → LLM → TTS 播出来（v0.1 先留接口位）。
- **设置页**：填 LLM / ASR / TTS 的 API URL、Key、Model，每个都有 **Test** 按钮。

> v0.1 明确**不做 lip sync、不做人脸**。先把「能看、能听、能配」的骨架跑通，再迭代对话链路。

## 架构

```
本地（旧手机就能跑）                    云端（纯 API）
├─ 麦克风采集 ──► 频谱/响度分析 ─► 3D 圆球波动
├─ （待接）VAD 判端点 ─────────────────►  ASR：语音 → 文字
├─ 文字显示 ◄──────────────────────────  LLM：文字 → 文字
└─ （待接）播放 ◄──────────────────────  TTS：文字 → 语音
```

本地常驻的只有：`AudioAnalyzer`（收音+频谱）与 `SphereRenderer`（一个全屏 raymarching
着色器，无 3D 引擎依赖）。所以对手机算力要求很低。

## 目录

```
app/src/main/java/com/roychong/deskspirit/
├── MainActivity.kt              上半屏圆球 + 下半屏文字
├── render/SphereView.kt         手动麦克风数据入口
├── render/SphereRenderer.kt     GLSL raymarching 玻璃球（音频驱动）
├── audio/AudioAnalyzer.kt       麦克风采集 + FFT 频谱（后续 VAD 底座）
├── config/ApiConfig.kt          SharedPreferences 存 API 配置
├── net/ApiTester.kt             LLM/ASR/TTS 连通性测试
└── settings/SettingsActivity.kt 设置页
```

## 构建 / 安装

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 下一步（未完成）

- [ ] 本地 VAD 判端点 → 切音频段
- [ ] ASR 接入（`/v1/audio/transcriptions`）
- [ ] LLM 流式接入（`/v1/chat/completions`, `stream:true`）
- [ ] TTS 接入（Edge-TTS 或 `/v1/audio/speech`）+ 播放
- [ ] 轮次互斥：播放时关麦，播完再开麦
- [ ] 半分辨率渲染降采样（更低端机型）

## 设计参考

收音/轮次的交互逻辑参考了 MNN TaoAvatar App（流式 ASR 端点检测 + 播放时关麦），
但**不引入任何 MNN 模型**——那套 `A2BS + NNR` 数字人渲染与本项目无关。
