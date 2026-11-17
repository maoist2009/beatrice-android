# Voice Changer (Android)

安卓原生变声客户端，内置两套可切换的后端，完全离线（无 WebView、不联网）：

| 后端 | 引擎 | 运行位置 |
| --- | --- | --- |
| **Beatrice 2.0.0-rc.0** | 官方 `.bin` 权重编译成 ONNX 增量图，ONNX Runtime 推理 | CPU |
| **RVC v2** | HuBERT + RMVPE + encflow/flowreverse/decnsf 的 QNN 管线 | Hexagon NPU |

三种用法：文件转换、本地麦克风实时变声、以及通过 Xposed 把其它 App 的 `AudioRecord`
接进来做全局变声。

同一时刻只驻留一个后端：RVC 的 NPU context 约 220 MB，两套同时加载会触发 LMK。

## 环境要求

- Android 12 及以上（`minSdk 31`），`arm64-v8a`。
- RVC 后端需要 Hexagon NPU：仓库内置 V79 运行库，对应骁龙 8 Elite。
  8 Gen 2 / 8 Gen 3 需换用对应的 V73 / V75 `Skel`/`Stub`；8 Gen 1 及更早需要重新编译图。
- Beatrice 后端只跑 CPU，任何 arm64 设备都能用。

## 构建

```sh
./gradlew assembleRelease
```

debug 变体复用 release 签名，包名加 `.tmp` 后缀，可与正式包共存，便于并行测试。

## 使用

1. 导入模型（见下文「模型」）。两套后端的模型互相独立。
2. 首页是变声 / 播放页：实时麦克风开关、后端切换，以及 Xposed 的 “Use in other apps”。
   其余功能各占一页，从首页顶部的按钮进入：

| 页面 | 内容 |
| --- | --- |
| 首页 Voice Changer | 实时变声、后端切换、Xposed 开关与目标 App |
| RVC | 音色包、pitch / protect / loudness / f0 平滑 / block-tail f0 / index / 噪声门 |
| Beatrice | 模型、pitch / formant / intonation / pitch correction / VQ / 噪声门 |
| Convert | 文件转换：选音频 → 转换 → 试听 / 保存 |
| About | 版本、引擎与来源说明 |

各页共用同一个 `MainViewModel`，在任一页改的参数在其它页立刻可见。只有 RVC 音色包
和 Beatrice 模型这两类文件是分开导入的。

- **文件转换**：Convert 页选音频 → 转换 → 试听 / 保存；
- **实时**：首页打开麦克风开关，走前台服务，锁屏和后台继续；
- **全局变声**：本 APK 同时是 Xposed 模块。在 Xposed 管理器（如 LSPosed）里启用模块、
  勾选目标 App（scope），再打开首页的 “Use in other apps” 开关 —— 两者都满足才会转换。

本地麦克风模式与 hook 模式互斥：打开实时模式会自动关掉 hook 开关，因为两者共用同一个
有状态引擎。

## 模型

### Beatrice

模型文件夹内含 `*.toml` + `phone_extractor.bin`、`pitch_estimator.bin`、
`speaker_embeddings.bin`、`waveform_generator.bin`、`embedding_setter.bin`，
导入后整体放在 `files/model/`。

### RVC v2

```
files/rvc/shared/         hubert_c0_s23.bin, hubert_r_2063_s23.bin,
                          hubert_enc_140_s23.bin, rmvpe_e2e_s23.bin, conv0_gn.f32
files/rvc/voices/<name>/  encflow.bin, flowreverse.bin, decnsf_ext.bin,
                          voice.json（取自 *_manifest.json），可选 retrieval.index
files/rvc/models/         管线实际读的扁平目录（切换音色时用软链接 staging）
```

导入时选文件夹即可，扫描规则：

- 含 `.toml` + `phone_extractor`/`waveform_generator` → Beatrice 模型；
- 含 `hubert_c0*` + `hubert_r*` + `hubert_enc*` + `rmvpe*` + `conv0_gn*` → 共享包；
- 含 `encflow*` + `flowreverse*` + `decnsf*` + 清单 → 音色包（一个扁平目录里放多个音色也能按
  清单里的 `tag` 拆开导入）。

预编译好的共享包与音色包见 [rvc-models](https://huggingface.co/mistmlm/rvc-models)。

## 关键参数

| 项 | Beatrice rc.0 | RVC v2 |
| --- | --- | --- |
| 输入采样率 | 16 kHz | 16 kHz |
| 输出采样率 | 24 kHz | 音色清单里的 `sr`（v2 40k = 40 kHz） |
| 步进 | chunk 12 帧 = 120 ms | 400 样本 = 25 ms（每 4000 样本吐一块 0.25 s） |
| 实时延迟 | 约 2 个 chunk + 单次推理 ≈ 330 ms | 见下文「延迟」 |
| 设备 | CPU | Hexagon NPU（HTP） |
| 文件转换 RTF | chunk 12 约 0.81，chunk 20 约 0.73，chunk 40 约 0.60 | 取决于音色，约 0.5–0.7 |

参数按后端显示：Beatrice 有 formant / intonation / pitch correction / VQ；RVC 有
protect（0–0.5，0.5 为关）、loudness match、f0 平滑窗口（1–7）、block-tail f0；pitch 与输出电平两边共用。

block-tail f0 两档，只影响每个 250 ms 块末尾那几个 f0 帧：

| 档位 | 做法 | 代价 |
| --- | --- | --- |
| quality（默认） | 等「把这几个帧放在窗中心」的那张 RMVPE 窗 | 前瞻最长 |
| low latency | 用「最早仍包含这几个帧」的窗，窗尾补零不等 | 少约 100 ms，块尾音高更粗 |

两档之外的部分完全相同：同一份流式代码、同一批 mel/Hubert 窗、同样的合成。

### RVC 检索（index）

| 项 | 默认 | 说明 |
| --- | --- | --- |
| index rate | 0（关） | 把 `.index` 里最近的帧按比例混进 encoder 输出；0.5 是官方客户端的常用值 |
| index probes | 0（用文件值） | IVF 探测数；0 表示读 `.index` 里记录的 nprobe |

`.index` 只在管线创建时读取，所以改这两项会在松手时重新加载音色（约 1 秒）；
hook 开着时不允许改，先关掉再调。没有 `.index` 的音色包，rate 无论多少都是关的。

### 噪声门

输入 10 ms 峰值包络低于阈值的帧不进模型（`noiseGateDb`，默认 −50 dB，0 dB 为关）。
两套后端共用，文件转换 / 实时 / hook 三条路径过同一道门；开门斜坡 5 ms、关门 30 ms，
避免咔哒声和咬字被截断。

## 验证结果

Beatrice（CPU）：

- 受控 150 Hz 合成元音：pitch 0 / +12 / −12 半音 → 输出 150 / 300 / 75 Hz。
- 真实语音 3.36 s：转换后与输入包络相关 0.90。
- 流式路径与离线转换：包络相关 0.997，RMS 0.1267 vs 0.1263。
- 模型加载约 0.5 s，三张图构建约 2 s，PSS 约 310 MB。

RVC（NPU，骁龙 8 Elite）：

- 内置的 QNN 运行库在 app 进程里 `QnnDevice_create` 成功（117 ms 完成 `nativeInit` +
  `pipelineCreate`），`libQnnHtpV79Skel.so` 通过 `libcdsprpc.so` 调用 DSP，
  因此 `AndroidManifest` 必须声明 `uses-native-library`。
- 一条 pipeline 只允许一个 NPU worker 线程：`rvc_pipeline_start_stream` 是软复位（只清音频
  状态，线程只在第一次创建），`end_stream` 也不停线程，线程活到 `pipelineDestroy`。
  注意两点：① worker 还 joinable 时重新赋值 `std::thread` 会 `std::terminate`；
  ② 停线程再重启会让 HTP 上第一次 execute 永不返回。
- 端到端 RTF：两个音色分别 0.596 / 0.585，带 `retrieval.index` 时 0.639 / 0.657；
  不同音色互相关 0.15，同音色带/不带 index 相关 0.26（说明 retrieval 生效）。
  PSS 827 MB，未触发 OOM；输出峰值限到 0.99，无削波。
- 与上游 dreamlite 实现同输入对照：3.0 s / 120000 样本 vs 3.25 s / 130000 样本
  （多出的是补零尾块，离线按输入时长裁剪）；三频段能量占比 73.4/25.5/1.1 vs 75.2/23.7/0.9，
  RMS 0.156 vs 0.169。

## 把声音接进别的 App（Xposed）

本 APK 自己就是 Xposed 模块（`assets/xposed_init` → `hook.HookEntry`），
`compileOnly` 编进 `libs/xposed-api-stub.jar` 的 API 桩，运行时用框架真正的实现。

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
  放进目标进程会直接把目标 App 撑爆，所以转换全部留在本进程。
- 单声道化后送引擎，写回时复制到每个声道；`byte[] / short[] / float[] / ByteBuffer`
  四种重载都处理（按 `AudioRecord.getAudioFormat()` 区分 PCM16 / PCM_FLOAT）。
- 进程名包含 `recorder / voicenote / soundrecorder` 的直接跳过，自己的包名也跳过。
- 一条流独占引擎：新流接管前会把旧流关掉（`HookBridge.claim`），空闲 8 s 的会话自动回收。
- 首次回调要等引擎加载 + 预灌（`primeSamples`，等于后端一块的长度，250–800 ms 自适应），
  这段时间目标 App 听到的是原始声音；之后如果下行队列被抽空，会退回原声重新预灌
  （并把该进程学到的预灌值 +100 ms 共享给后续会话），而不是把原声和变声拼在一起。
- 队列有上界：出块时如果高于「预灌 + 一块」，就一次性裁回预灌深度（丢的是最旧的转换后音频，
  带 2 s 冷却，稳态不触发）；预灌阶段也做一次同样的裁剪。
- 配置（开关 / 目标列表 / 参数）每秒从 provider 拉一次，改完不用重启目标 App。
- `HookProvider` 是 `exported="true"` 且没有权限保护：同机任何 App 都能调它拿到当前配置
  或喂音频。自用没问题，分发给别人前应加 `signature|knownSigners` 级权限，或改成
  只在模块里用 bindService 拿到的本地 socket。

## 延迟

hook 路径的延迟不靠「前瞻 + 队列」估算，而是逐缓冲打时间戳：`HookSession` 给每个采集样本
记下到达时刻，交给目标 App 时用 `now - 到达时刻` 算出这段音频真正等了多久。

| 后端 | 客户端 | 预灌 | 下行队列（稳态） | 单块推理 | 端到端 |
| --- | --- | --- | --- | --- | --- |
| RVC v2 | 16 kHz / 320 帧 | 250 ms | 100–180 ms | 110–175 ms | **740 ms**（p50） |
| RVC v2 | 48 kHz / 960 帧 | 250 ms | 90–180 ms | 120–130 ms | 790–950 ms |
| Beatrice rc.0 | 16 kHz / 320 帧 | 250 ms | 250–310 ms | 60–75 ms | ≈550 ms |

RVC 的 740 ms 可以直接从一次会话的日志字段拆开（同一行：`lat`、`recv - fed`、`fifo`）：

```
lat 740 = 145  推理期间积压的采集（≈单块推理）
        + 200  管线前瞻（首块需要 6720 样本输入，由模型窗口决定）
        + 394  下行水面（其中 250 是块本身，144 是发射节拍的锯齿）
```

前瞻由模型窗口决定，改它需要重编 NPU 图；中间那段是线程模型的固有开销。最后 144 ms 的锯齿
不是浪费：后端每 4800 / 3200 样本交替出一块，间隔 250 / 300 ms 交替，实测最大间隔 ~360 ms，
水面必须盖住它再加一次读取（320 样本 = 20 ms）。把目标压到 320 / 380 ms 实测能换来 ~25 ms，
但每分钟出现 2–5 次欠载（读空 → 丢块），所以水面已经在地板。

已经压掉的 380 ms 来自四处：

- **RMVPE 窗口提前提交**：一块需要 2–3 个 f0 窗口，只有最后一个受整块前瞻约束，其余窗口的
  320 ms 音频早就到齐了。现在窗口一到齐就提交，NPU 的算力藏进前瞻等待里，而不是在出块时排队。
- **重启时排空队列**：复位原先直接丢弃在跑的 NPU 作业，而完成计数永远到不了这些 ticket 的
  编号，后续等待会永久挂住（表现为输出恒为空）。现在先等队列排空再清状态。
- **预灌与上界**：预灌从 1.6 块降到 1 块（250 ms），出块后一次性裁到目标深度，高水位只丢整块
  并带冷却；接管前的陈旧采集直接丢弃。
- **粒度**：步进 1600 → 400 样本（25 ms），首块提前约 120 ms。

剩下的约 140 ms 是线程模型造成的：worker 在跑推理时无法同时喂数据，采集在环里积压，再被
下一块重新等一遍。NPU 本身是串行的（单个 worker、全局锁），所以把编码器作业提前提交并不能
减少总计算时间 —— 实测无收益。要消掉这段需要把「喂数据」和「出块」拆到两个线程，并处理
共享缓冲区的并发访问。

## 目录结构

```
app/src/main/java/org/voice/changer/
  engine/
    Proto.kt / Layouts.kt / Rc0Graphs.kt    ONNX 图构建与权重布局
    OrtSession.kt / PitchDsp.kt / Fft.kt    ORT 会话、音高特征、FFT
    WaveformSynth.kt / Rc0Processor.kt      源-滤波合成、帧调度、kNN-VQ
    ModelLoader.kt / EngineHub.kt           模型解析、进程级引擎持有者
    LiveProcessor.kt / EngineParams.kt      后端共用接口与参数
    rvc/                                    JNI 绑定、流式适配、包存储、计时
  audio/
    AudioIO.kt / LiveEngine.kt              解码/重采样/WAV/播放，麦克风实时链路
    NoiseGate.kt                            输入噪声门（三条路径共用）
  hook/
    HookEntry.kt / HookAudio.kt             Xposed 入口、四种 PCM 重载的就地改写
    HookTap.kt / HookRemote.kt              目标进程队列、配置缓存
    HookSession.kt / HookBridge.kt          单条转换流、会话登记与统计
    HookProvider.kt / StreamResampler.kt    导出的 provider、流式重采样
    FloatFifo.kt                            有界环形队列
  ui/
    Theme.kt / Components.kt                主题、页面骨架、通用控件
    LiveScreen.kt                           首页：实时 + 后端切换 + hook 开关
    RvcScreen.kt / BeatriceScreen.kt        两个后端的参数页
    ConvertScreen.kt / AboutScreen.kt       文件转换、关于
    SharedVm.kt                             跨 Activity 共用的 ViewModel
  MainActivity.kt                           首页
  RvcActivity.kt / BeatriceActivity.kt      两个后端页
  ConvertActivity.kt / AboutActivity.kt     转换页、关于页
  LiveService.kt / MainViewModel.kt         前台服务、共享状态机
app/src/main/cpp/        RVC 管线（QNN/HTP 移植）+ JNI
app/src/main/jniLibs/    QNN HTP 运行库（Htp / V79Stub / V79Skel / System / NetRunExtensions）
```

## 调试

debug 变体带一个 `DebugActivity`，可用 intent extra 驱动无界面自检，结果同时写入
`files/debug_status.txt`，方便脚本化取回：

- `--es backend qnn`：QNN 自检；
- `--es backend rvc --es voice <name> --es input <wav> --es output <wav>`：RVC 离线转换；
- `--es mode hook --es backend rvc --es voice <name> --es input <wav> --ei clientRate 48000
  --ei frames 4800`：hook 链路自检，按目标采样率实时喂 `HookBridge.processCall`，
  并与同段音频的直接转换结果比包络相关。

输入输出用应用自己的 `files/` 目录。

## 已知限制

- 某些音色/参数下输出峰值会超过 1.0（与官方引擎一致，不做归一化），WAV 写入时按 1.0 截断；
  UI 提供输出电平（0.2–2.0）。
- Beatrice 没有官方模型的对照基线：正确性依赖对 beatrice-web 的逐行移植 + 数值检查。
- RVC 的 `speaker_id`（音色包内部的多说话人）与 `index_rate` 在管线初始化时烘死，
  切换需要重载引擎，UI 目前没有暴露这两个。
- RVC 的尾块：本仓库把上游的 `pipelineStreamEnd` 改成 `rvc_pipeline_end_stream`（补零），
  否则每段音频最后约 0.25–0.5 s 会被丢掉；离线转换额外按输入时长裁剪。
- UI 只在单一分辨率上验证过；SAF 导入流程也还需要更多机型覆盖。

## 许可

GPL-3.0-only，见 `LICENSE`。模型权重与音色数据遵循各自的上游条款，见 `NOTICE`。
