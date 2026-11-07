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
- 首次回调要等引擎加载 + 预灌（`HookSession` 的 `primeSamples`，按后端块长自适应，
  300–800 ms），这段时间目标 App 听到的是原始声音；之后如果下行队列被抽空，
  会退回原声重新预灌，而不是把原声和变声拼在一起。
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
