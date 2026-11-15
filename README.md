# Beatrice Lite (Android)

一个安卓原生变声客户端，内置两套后端：

- **Beatrice 2.0.0-rc.0** —— 把官方 `.bin` 权重编译成 ONNX 增量图，用 ONNX Runtime 在 CPU 上跑实时变声与文件转换。
- **RVC v2** —— dreamlite 的 QNN 管线（HuBERT + RMVPE + encflow/flowreverse/decnsf），跑在 Hexagon NPU 上。

没有 WebView，不需要联网，模型导入后完全离线。

同一时刻只有一个后端驻留：RVC 的 NPU context 合计约 220 MB，两套同时加载会招来 LMK。

## 结构

```
app/src/main/java/com/beatrice/lite/
  engine/
    Proto.kt        ONNX protobuf 写入器 + GraphBuilder
    Layouts.kt      .bin 浮点16 解码、各组权重布局、speaker/KV 解析
    Rc0Graphs.kt    pitch / phone / vocoder 三张流式图（对应 beatrice-web rc0/graphs.ts）
    OrtSession.kt   跨 chunk 保持 conv/attention 状态的 ORT 会话
    PitchDsp.kt     160/560 特征、softmax+banded argmax、官方音高算术
    Fft.kt          radix-2 + Bluestein（480/512/768）
    WaveformSynth.kt 源-滤波合成、相位累积、重叠加法
    Rc0Processor.kt 帧调度（分析超前 2 帧、能量回看 1 帧、音高回看 2 帧）、kNN-VQ
    ModelLoader.kt  toml 解析、文件校验
    LiveProcessor.kt 两个后端共用的接口（按绝对输入样本索引取数）
    EngineParams.kt  两套后端共用的参数结构
    EngineHub.kt     进程级引擎持有者（前台服务与 UI 共用，同一时刻只驻留一个后端）
    rvc/
      RvcJni.kt      librvc_qnn.so 的 JNI 绑定
      RvcProcessor.kt 流式适配：每 100 ms 推一段新音频，取回算好的块
      RvcStore.kt    共享包/音色包的存储、SAF 导入、软链接 staging
      RvcStats.kt    native 侧计时 JSON
  audio/
    AudioIO.kt      MediaCodec 解码、窗函数 sinc 重采样、WAV、播放
    LiveEngine.kt   麦克风→环形缓冲→推理→扬声器（双线程，输出采样率由后端决定）
  hook/
    HookEntry.kt    Xposed 入口：hook 目标进程的 AudioRecord.read
    HookAudio.kt    byte[]/short[]/float[]/ByteBuffer 四种重载的就地改写
    HookTap.kt      目标进程里的上下行队列（音频线程不碰 binder）
    HookRemote.kt   配置缓存 + 调 provider 的 binder 客户端
    HookSession.kt  宿主进程里的一条转换流（重采样、预灌、推理、输出节流）
    HookBridge.kt   会话登记、引擎归属仲裁、统计
    HookProvider.kt 导出的 ContentProvider（config / process / report）
    StreamResampler.kt 带状态、带抗混叠的流式重采样
    FloatFifo.kt    有界浮点环形队列
  LiveService.kt    前台服务（microphone 类型），锁屏/后台继续变声；hook 模式只保温进程
  MainViewModel.kt  模型导入、后端/音色切换、转换、参数、播放、保存、hook 开关
  MainActivity.kt   单页 Compose UI
app/src/main/cpp/   RVC 管线（dreamlite 移植，JNI 包名改为 com.beatrice.lite.engine.rvc）
app/src/main/jniLibs/arm64-v8a/  QNN HTP 运行库（Htp / V79Stub / V79Skel / System / NetRunExtensions）
```

## 模型

### Beatrice

模型文件夹内含 `*.toml` + `phone_extractor.bin`、`pitch_estimator.bin`、
`speaker_embeddings.bin`、`waveform_generator.bin`、`embedding_setter.bin`，
导入后整体放在 `files/model/`。

### RVC v2

按 dreamlite 的包结构：

```
files/rvc/shared/    hubert_c0_s23.bin, hubert_r_2063_s23.bin,
                     hubert_enc_140_s23.bin, rmvpe_e2e_s23.bin, conv0_gn.f32
files/rvc/voices/<name>/  encflow.bin, flowreverse.bin, decnsf_ext.bin,
                     voice.json（取 *_manifest.json），可选 retrieval.index
files/rvc/models/    管线实际读的扁平目录（切换音色时用软链接 staging）
```

导入时选文件夹即可，扫描规则：

- 含 `.toml` + `phone_extractor`/`waveform_generator` → Beatrice 模型；
- 含 `hubert_c0*` + `hubert_r*` + `hubert_enc*` + `rmvpe*` + `conv0_gn*` → 共享包；
- 含 `encflow*` + `flowreverse*` + `decnsf*` + 清单 → 音色包（一个扁平目录里放多个音色也能按清单里的 `tag` 拆开导入）。

## 关键参数

| 项 | Beatrice rc.0 | RVC v2 |
| --- | --- | --- |
| 输入采样率 | 16 kHz | 16 kHz |
| 输出采样率 | 24 kHz | 音色清单里的 `sr`（v2 40k = 40 kHz） |
| 步进 | chunk 12 帧 = 120 ms | 1600 样本 = 100 ms（每 4000 样本吐一块 0.25 s） |
| 实时延迟 | 约 2 个 chunk + 单次推理 ≈ 330 ms | 首块约 0.45 s 前瞻 + 一块 |
| 设备 | CPU | Hexagon NPU（HTP） |
| 文件转换 RTF | chunk 12 约 0.81，chunk 20 约 0.73，chunk 40 约 0.60 | 取决于音色（dreamlite 实测约 0.5–0.7） |

参数按后端显示：Beatrice 有 formant / intonation / pitch correction / VQ；RVC 有
protect（0–0.5，0.5 为关）、loudness match、f0 平滑窗口（1–7）；pitch 与输出电平两边共用。

## 验证结果（SM8750）

Beatrice（CPU）：

- 受控 150 Hz 合成元音：pitch 0 / +12 / −12 半音 → 输出 150 / 300 / 75 Hz。
- 真实语音 3.36 s：转换后与输入包络相关 0.90。
- 流式路径与离线转换：包络相关 0.997，RMS 0.1267 vs 0.1263。
- 模型加载约 0.5 s，三张图构建约 2 s，PSS 约 310 MB。

RVC（NPU）：

- 本仓库内置的 QNN 运行库在 app 进程里 `QnnDevice_create` 成功（117 ms 完成
  `nativeInit` + `pipelineCreate`），`libQnnHtpV79Skel.so` 走 `/vendor/lib64` 的
  `libcdsprpc.so`，因此 `AndroidManifest` 里必须声明 `uses-native-library`。
- **一条 pipeline 只允许一个 NPU worker 线程**：`rvc_pipeline_start_stream` 是“软复位”（只清音频状态，
  线程只在第一次创建），`end_stream` 也不会停线程，线程活到 `pipelineDestroy`。踩过的坑：
  ① 在 worker 还 joinable 时重新赋值 `std::thread` → `std::terminate`（进程直接 `libc++abi: terminating`，
  没有 Java 栈）；② 停线程再起重启 → HTP 上第一次 execute 永远不返回，收集端在 futex 等 ticket。
- 端到端实测（3.36 s 输入）：`mygf_zbhy` RTF 0.596、`mygf_wh` 0.585；带 retrieval.index
  时 0.639 / 0.657。两音色互相关 0.15、同音色带/不带 index 相关 0.26（retrieval 生效）。
  PSS 827 MB，未触发 OOM；native 侧把峰值限到 0.99，无削波。
- 与 dreamlite 同输入对照：dreamlite 3.0 s / 120000 样本，本仓库 3.25 s / 130000 样本
  （多出的是补零尾块，离线按输入时长裁剪）；三频段能量占比 73.4/25.5/1.1 vs 75.2/23.7/0.9，
  RMS 0.156 vs 0.169。


## 把声音接进别的 App（Xposed）

这个 APK 自己就是 Xposed 模块（`assets/xposed_init` → `hook.HookEntry`，
`compileOnly` 编进 `libs/xposed-api-stub.jar` 的 API 桩，运行时用框架真正的实现）。

进程模型是刻意的：

```
目标 App 进程                            Beatrice 进程
AudioRecord.read  (音频线程)
   │  就地取 PCM，压成单声道 float
   ├─► HookTap 上行队列 ──┐
   │                      │ 后台线程
   │                      └── ContentResolver.call("process") ──► HookProvider
   │                                                              │
   └─◄ HookTap 下行队列 ◄── 与输入等长的转换块 ◄── HookSession ◄───┤ EngineHub
                                        （16 kHz 环形缓冲 → 推理 → 重采样回目标采样率）
```

- **音频线程只做搬运**：取样本、入队、出队写回，绝不碰 binder。RVC 的 context 有 220 MB，
  放进目标进程会直接把微信撑爆，所以转换全部留在本进程。
- 单声道化后送引擎，写回时复制到每个声道；`byte[] / short[] / float[] / ByteBuffer`
  四种重载都处理（按 `AudioRecord.getAudioFormat()` 区分 PCM16 / PCM_FLOAT）。
- 进程名包含 `recorder / voicenote / soundrecorder` 的直接跳过，自己的包名也跳过。
- 一条流独占引擎：新流接管前会把旧流关掉（`HookBridge.claim`），空闲 8 s 的会话自动回收。
- 首次回调要等引擎加载 + 预灌（`HookSession` 的 `primeSamples`，等于后端一块的长度，
  250–800 ms 自适应），这段时间目标 App 听到的是原始声音；之后如果下行队列被抽空，
  会退回原声重新预灌（并把这个进程学到的预灌值 +100 ms 记下来共享给后续会话），
  而不是把原声和变声拼在一起。
- 队列有上界：出块时如果高于「预灌 + 一块」，就一次性裁回预灌深度（丢的是最旧的转换后
  音频，带 2 s 冷却，稳态不触发）；预灌阶段也做一次同样的裁剪。旧的「每次回调削 5 ms」
  策略会让延迟停在 0.75–1 s 且永不回落。
- 配置（开关 / 目标列表 / 参数）每秒从 provider 拉一次，改完不用重启目标 App。
- UI 里“每秒接收”的开关是本人开关；**模块本身还要在 Xposed 管理器里启用并勾上同样的
  App（scope）**，两者都满足才会转换。
- 本地麦克风模式与 hook 模式互斥：开 live 会自动关掉 hook 开关，因为两者共用同一个
  有状态引擎。
- `HookProvider` 是 `exported="true"` 且没有权限保护（个人调试用）：同机任何 App 都能调它拿到
  当前配置/喂音频。要长期使用就该加 `signature|knownSigners` 级别权限，或改成只在模块里用
  bindService 拿到的本地 socket。
- 自检结果（3.36 s 输入 → 按 48 kHz、4 800 样本/次实时喂 `processCall`，再和同段音频的
  直接转换比 20 ms 包络）：rc.0 相关 **0.998**、RVC `mygf_zbhy` 相关 **0.996**（lag 0 ms），
  输出无削波（峰值限到 0.99）。

### 延迟实测（hook 路径，SM8750）

`DebugActivity --es mode hook` 按目标采样率实时喂 `HookBridge.processCall`。延迟不再靠
「前瞻 + 队列」估算，而是**逐缓冲打时间戳**：`HookSession` 给每个采集样本记下到达时刻
（`ringAt`），交给目标 App 时用 `now - ringAt[已交付 + 已丢弃]` 算出这段音频真正等了多久
（48 kHz 时按 16 kHz 折算环下标）。`hook_session` 日志的 `lat=` 与 harness 的
`hook latency: p50` 两条独立路径互相印证（2026-11-15 实测）：

| 后端 | 客户端 | 预灌 | 下行队列（稳态） | 单块推理 | 实测端到端 |
| --- | --- | --- | --- | --- | --- |
| RVC `mygf_wh` | 16 kHz / 320 帧 | 250 ms | 50–160 ms | 110–175 ms | **760 ms**（p50，n=155） |
| RVC `mygf_wh` | 48 kHz / 960 帧 | 250 ms | 90–180 ms | 120–130 ms | 790–950 ms（harness 自身比实时慢 14%，队列被撑大） |
| Beatrice rc.0 | 16 kHz / 320 帧 | 250 ms | 250–310 ms | 60–75 ms | ≈550 ms |

RVC 的 760 ms 由四段构成：管线前瞻 225 ms（首块 6720 样本）+ 单块推理 ≈140 ms +
下行队列 ≈90 ms + 块内位置（0–250 ms，取均值 125 ms）。前瞻是模型窗口决定的，改它得
重编 NPU 图；其余三段是纯实现开销。这一轮压掉了 380 ms（1140 → 760）：

- **RMVPE 窗口提前提交**：一块要 2–3 个 f0 窗口，只有最后一个窗口受整块前瞻约束，
  其余窗口的 320 ms 音频早就到齐了。现在窗口一到齐就提交（`stream_submit_f0_window`），
  NPU 的 90 ms 算力藏进前瞻等待里，而不是在出块时排队。
- **重启时排空队列**：`stream_reset` 原先 `pending.clear()` 直接丢弃在跑的作业，
  而 `q_done` 永远到不了这些 ticket 的编号，后续 `npu_wait` 会永久挂住（表现为
  `hook returned 0 samples`）。现在先等队列排空再清状态。
- **预灌与上界**：预灌从 1.6 块降到 1 块（250 ms），出块后一次性裁到目标深度，
  高水位只丢整块并带 2 s 冷却；`keep=0` 丢掉接管前的陈旧采集。
- **粒度**：`stepSamples` 1600 → 400（25 ms），首块提前 ~120 ms；tap 空转 5 ms → 2 ms。

单块推理 130–190 ms（RVC，NPU）／60–75 ms（rc.0，CPU）。剩下的 200 ms 不是模型造成的：
worker 线程在跑推理时无法同时喂数据，采集在环里积压 ≈140 ms，再被下一块重新等一遍。
要拿掉它得把「喂数据」和「出块」拆成两个线程（原生侧拆 `feed_stream`），预计能到 ≈600 ms。

## 调试

debug 包里有 `DebugActivity` 与 `LiveService` 的调试入口，结果同时写
`files/debug_status.txt`，方便在没有界面交互的情况下取回输出：

```sh
# QNN 自检
am start -n com.beatrice.lite.tmp/com.beatrice.lite.DebugActivity --es backend qnn

# Beatrice 离线转换（模型需放在 files/model）
am start -n com.beatrice.lite.tmp/com.beatrice.lite.DebugActivity \
    --es input /data/data/com.beatrice.lite.tmp/files/test/in.wav \
    --es output /data/data/com.beatrice.lite.tmp/files/out.wav --ei chunk 12 --ei speaker 0

# RVC 离线转换（需要 files/rvc 里的包）
am start -n com.beatrice.lite.tmp/com.beatrice.lite.DebugActivity --es backend rvc \
    --es voice mygf_zbhy --es input .../in.wav --es output .../rvc.wav

# 实时链路（文件按实时节奏喂入，验证调度，不需要麦克风）
am start-foreground-service -n com.beatrice.lite.tmp/com.beatrice.lite.LiveService \
    --es feed .../files/test/in.wav --es tap .../files/live.wav --ei seconds 8

# hook 链路自检（不需要 Xposed：直接按目标采样率实时喂 HookBridge.processCall，
# 再和同段音频的直接转换结果比包络相关）
am start -n com.beatrice.lite.tmp/com.beatrice.lite.DebugActivity --es mode hook \
    --es backend rvc --es voice mygf_zbhy --es input .../files/test/in1.wav \
    --es output .../files/test/hook_rvc.wav --ei clientRate 48000 --ei frames 4800
```

## 已知限制

- 某些音色/参数下输出峰值会超过 1.0（与官方引擎一致，不做归一化），WAV 写入时按 1.0 截断；UI 提供输出电平（0.2–2.0）。
- Beatrice 还没有模型内测的对照基线：正确性依赖对 beatrice-web 的逐行移植 + 数值检查。
- RVC 的 `speaker_id`（音色包内部的多说话人）与 `index_rate` 在管线初始化时烘死；切换需要重载引擎，UI 目前没暴露这两个。
- RVC 的尾块：本仓库把 dreamlite 的 `pipelineStreamEnd` 改成 `rvc_pipeline_end_stream`（补零），否则每段音频最后约 0.25–0.5 s 会被丢掉；离线转换额外按输入时长裁剪。
- UI 未做视觉验收（验收时设备锁屏）；SAF 导入流程未在真机上点过。
