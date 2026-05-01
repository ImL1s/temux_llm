# temux_llm — 在 Android 上跑本地 LLM，仿 Ollama

[English](README.md) · [繁體中文](README.zh-TW.md)

[![build](https://github.com/ImL1s/temux_llm/actions/workflows/build.yml/badge.svg)](https://github.com/ImL1s/temux_llm/actions/workflows/build.yml)
[![release](https://img.shields.io/github/v/release/ImL1s/temux_llm?include_prereleases)](https://github.com/ImL1s/temux_llm/releases)
[![license](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
[![android](https://img.shields.io/badge/android-13%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/about/versions/13)
[![runtime](https://img.shields.io/badge/runtime-LiteRT--LM%200.11.0--rc1-yellow)](https://github.com/google-ai-edge/LiteRT-LM)

一個 self-contained 的 Android app + Termux 客戶端，用你手機的 GPU 跑 Google
**Gemma 4**（或 Qwen3），透過 `http://127.0.0.1:11434/api/generate` 提供服務。
不連網。不上雲。資料不離開裝置。

```
$ litertlm "Reply with just: hi."
hi.

[gpu  total=2676ms  tokens=62  decode=23.2 t/s]
```

實機驗證：**Galaxy S21+**（SD 888 / Adreno 660，Android 15）、
**Galaxy S24 Ultra**（SD 8 Gen 3 / Adreno 750，Android 16）、
**Galaxy S25**（SD 8 Elite / Adreno 830，Android 16）。
理論上任何 arm64-v8a Android 13+ Snapdragon 機都能跑。CPU + GPU 實測數字見
[效能](#效能)。

## 用途 / Use cases

- **離線、不上雲：** 飛航模式、地下鐵、不能讓 prompt 離開裝置的場景，照樣跑 LLM。
- **Ollama-相容 endpoint：** 預設綁 `http://127.0.0.1:11434`（Ollama 標準埠），既有
  腳本/app 不用改就能接。
- **Termux 腳本：** 把 LLM 輸出 pipe 進 shell：`curl -s ... | jq .response` 就能在
  手機上做結構化查詢。
- **離線助理：** Gemma-class 模型常駐記憶體，短 prompt 不到 1 秒回。
- **可換模型：** 任何 `.litertlm` 格式（Gemma、Qwen、未來釋出的）都能換上去。
- **隱私 prototyping：** 早期開發直接打本地 endpoint，不用 API key、不燒 token、
  prompt 不會送到第三方。

**不適合：** 正式 production 流量（單機單租戶）、長 context 工作（最多 8k）、
多人服務（設計上只綁 localhost）。

---

## 開箱即用

從乾淨 clone 出發，手機接上 USB，一行：

```bash
bash scripts/install.sh
```

安裝腳本會：

1. 檢查主機有 `adb`、JDK 17+、設定好的 Android SDK。
2. 自動挑你的手機（或指定 `DEVICE_SERIAL=...`）。
3. 依 SoC 自動挑模型，或設 `MODEL=e2b|e4b|qwen3`：
    - **`e2b`**（舊機預設）：`gemma-4-E2B-it.litertlm`，2.4 GB。
       S21+ 等級的硬體跑得動。
    - **`e4b`**（SD 8 Elite 等級預設 — Fold7、S25）：`gemma-4-E4B-it.litertlm`，
       3.4 GB。比較聰明、慢一點，需要 ≥10 GB RAM 的手機。
    - **`qwen3`**：`Qwen3-0.6B.litertlm`，614 MB。最小的退路；話多。
4. 下載 binary + accelerators + 選定模型（sha256 驗證）。
5. 用內建 Gradle wrapper 編出 debug APK。
6. 把所有東西推到裝置、安裝 APK、啟動 service。
7. 對 `/api/generate` 做 smoke test，印下一步指引。

跑完之後，安裝 Termux（請用 [F-Droid 版][termux-fdroid] — Play Store 那個沒在維護），
把 wrapper 加進 PATH **一次**：

```sh
echo 'export PATH="/data/local/tmp/bin:$PATH"' >> ~/.bashrc
```

之後任何 Termux session 都能用：

```sh
litertlm "你好"
litertlm --backend cpu "Reply OK in 3 words."
litertlm --json "what is 2+2?"            # 輸出原始 JSON 供腳本使用
litertlm --help
```

[termux-fdroid]: https://f-droid.org/packages/com.termux/

---

## 跟 CLI coding agent 一起用（v0.3.0+）

`temux_llm` 對外協定模仿 Ollama 0.13+，所以常見 coding agent CLI 可以直接接，
不需要 proxy。手機上 service 起來後，host 端先 forward 11434：

```sh
adb forward tcp:11434 tcp:11434
```

接下來自己設 env vars，或直接用內建 launcher：

```sh
scripts/temuxllm launch claude        # Claude Code → /v1/messages
scripts/temuxllm launch codex         # Codex CLI → /v1/responses (--oss)
scripts/temuxllm launch opencode      # sst/opencode → /v1/chat/completions
scripts/temuxllm launch openclaw      # openclaw.ai → /api/chat (Ollama 原生)
scripts/temuxllm launch gemini        # 印出 LiteLLM bridge 用法
scripts/temuxllm launch claude --config-only   # 只印 env block，不執行
scripts/temuxllm launch codex --model gemma-4-E2B-it
```

行為對標 Ollama 0.15+ 的 `ollama launch <cli>`：probe 服務、選 model、
設 env vars / config、`exec` 目標 CLI。host 模式自動 `adb forward`。

完整 endpoint 對照與 wire format 細節：
[`docs/ollama-compat.md`](docs/ollama-compat.md)。

### v0.3.0 work / 不 work 範圍

**會 work**：四種 envelope 的純文字 streaming round-trip、跟 v0.2.x
`/api/generate` 的相容性、launcher CLI、各 CLI 進 chat 前的所有 probe。

**不 work**：

- **Agent loop**。Claude Code 內建 ~16k token system prompt、Codex CLI
  ~8k，Gemma 4 E2B/E4B 只 4096 token context — 兩個都會在 inference
  call 炸 `Input token ids are too long`。要 agent 需要 context 更大
  的 model。
- **Tool calling**。`/api/show` 只回 `["completion"]`，envelope 之間的
  tool / function call 翻譯沒接。純 chat round-trip 沒問題，agent 的
  tool 呼叫不會 work。
- **Image input**。`image_url` / image content block 一律回 400。

短 prompt 用 curl 直接打目前完全 OK。

---

## Termux-native（不用 APK）

不想 sideload APK？那就直接在 Termux 裡跑 LiteRT-LM binary — 不用 USB、
不用 Android service、不用主機。

**在手機的 Termux 裡：**

```sh
# 從你的 clone 複製 installer 過來，或手動 copy
bash install-termux-native.sh          # 預設：gemma-4-E2B-it (2.4 GB)
MODEL=qwen3 bash install-termux-native.sh   # 614 MB 退路
MODEL=e4b   bash install-termux-native.sh   # 3.4 GB，高階 SoC 才能跑
```

Installer 會把所有東西寫到 `~/.litertlm/`，wrapper 放在
`~/.local/bin/litertlm-native`。把 `~/.local/bin` 加進 PATH 一次，然後：

```sh
litertlm-native "你好"
litertlm-native --backend cpu "Reply OK in 3 words."
litertlm-native --json "what is 2+2?"
litertlm-native --help
```

**怎麼選哪一條路：**

| | APK 路徑（`install.sh`） | Native 路徑（`install-termux-native.sh`） |
|---|---|---|
| 安裝環境 | 主機 + USB（一次性） | 只在 Termux 裡 |
| 需要 sideload APK | 要 | 不用 |
| 每次 call 啟動成本 | 不到 1 秒（engine 常駐） | 3-8 秒（暖）/ 10-20 秒（首次） |
| 穩態 decode 速度 | 看 backend（見矩陣） | **CLI CPU 在 Snapdragon 上贏過或追平 APK GPU** |
| GPU 加速 | Adreno 上可用（v0.1.2 起） | 被 Termux 的 vendor namespace 擋掉 |
| 適合場景 | 短互動、要秒回 | 長段生成、無 USB 工作流、腳本 |

**反直覺但實測過：** CLI 的純 CPU decode 在所有測過的 Snapdragon 機上都贏過或
追平 APK 的 GPU 端到端速度（見[效能](#效能)）。原因：CLI 是緊湊的 native
binary，多執行緒 CPU 推理；APK 走 JNI + service + foreground 通知 +
loopback HTTP，每個 call 都要付這些 overhead。APK 還是贏在「每次 call 啟動
延遲」（engine 常駐）；CLI 每次 call 要重新 init engine。

兩條路都用 LiteRT-LM 0.11.0-rc.1（APK 走 Maven artifact
`com.google.ai.edge.litertlm:litertlm-android:0.11.0-rc1`，Termux-native
走 GitHub release `v0.11.0-rc.1` 的 `litert_lm_main` CLI binary），
模型也是同一批。

---

## 包含什麼

```
scripts/install.sh                  一鍵安裝（上面那個）
scripts/install-termux-native.sh    Termux-only 安裝（不用 APK）
scripts/litertlm-native-wrapper.sh  Termux-native CLI wrapper 的原始碼
scripts/fetch_artifacts.sh          sha256 驗證的主機端下載
scripts/litertlm-termux-wrapper.sh  Termux 客戶端（給 APK service 用）
scripts/preflight.sh                主機 + 裝置就緒檢查
scripts/setup_litertlm_android.sh   手動推送（install.sh 內部用）
scripts/run_{cpu,gpu}_smoke.sh      原生 adb-shell smoke（跳過 APK）
scripts/run_litertlm_benchmark.sh   長 prompt benchmark
scripts/parse_litertlm_logs.sh      把 BenchmarkInfo 抽到 logs/summary.txt
scripts/sha256_manifest.txt         binary + .so + 模型的 sha256

android/                            Android Studio 專案（Kotlin）
  app/src/main/kotlin/dev/temuxllm/
    LlmService.kt                   foreground service（持有 engine）
    LlmEngine.kt                    in-process Engine + Conversation 包裝
    HttpServer.kt                   NanoHTTPD on 127.0.0.1:11434
    LauncherActivity.kt             可點的 icon → 啟動 service 然後 finish()
    BootReceiver.kt                 開機自動拉起 service

docs/specs/                         phase-2 架構規格
docs/plans/                         實作 plans（phase-1 / phase-2a）
docs/findings-*.md                  各裝置實測結果
docs/screenshots/                   手機螢幕截圖（Termux 跑起來的樣子）
```

---

## API

```
GET  /healthz       -> "ok"
GET  /api/version   -> {service, phase, runtime, default_backend,
                        model_path, source_model_path, engine_loaded}
GET  /api/tags      -> {models: [{name, path, size_bytes}, ...]}
POST /api/generate  -> NDJSON 串流 {response, done}（stream=false 則回單一 JSON）
```

`/api/generate` request body：

```json
{
  "prompt":  "Hi",
  "backend": "cpu" | "gpu",   // 預設："gpu"
  "stream":  true             // 預設：true（NDJSON 一 token 一行）
}
```

串流 response：每行一個 JSON object（`application/x-ndjson`）：

```
{"response":"Hi","done":false}
{"response":"!","done":false}
{"response":"","done":true,"backend":"gpu","total_duration_ms":820,"output_tokens":2,"output_chars":3}
```

非串流（`stream=false`）：回一個 document，含 `model`、`backend`、`response`、
`done`、`total_duration_ms`、`output_tokens`。

Service 只綁 `127.0.0.1`。每次重啟後驗證：`ss -tnlp | grep 11434` 必須顯示
`[::ffff:127.0.0.1]:11434`（絕不會是 `0.0.0.0`）。

---

## 限制（沿用最初的 brief）

- 不需要 root。
- 只支援 arm64-v8a。
- HTTP 只綁 localhost。絕不對外暴露。
- Runtime 是 LiteRT-LM 載入 `.litertlm`（不是舊的 `.tflite` / `.task`）。

---

## 效能

`gemma-4-E2B-it`（2.4 GB），50 字 output prompt，暖機後。

| 裝置 | SoC | APK CPU<br>(end-to-end) | APK GPU<br>(end-to-end) | CLI CPU<br>(decode-only) |
|---|---|---|---|---|
| Galaxy S21+ | SD 888 / Adreno 660 | 8.0 t/s | 10.5 t/s | **12.1 t/s** |
| Galaxy S24 Ultra | SD 8 Gen 3 / Adreno 750 | 10.3 t/s | 22.0 t/s | 21.5 t/s |
| Galaxy S25 | SD 8 Elite / Adreno 830 | 12.4 t/s | 23.2 t/s | **35.4 t/s** |

兩個欄位量的東西不一樣，不能直接拿百分比比。各自報自己場景的誠實值：

- **APK end-to-end** = `output_tokens / (total_duration_ms / 1000)`，一次暖
  `/api/generate` 整個來回 — 含 prefill、decode、loopback HTTP/JNI
  overhead，就是使用者實際感受到的延遲。不算常駐 engine 的一次性 init。
- **CLI decode-only** = binary 的 `BenchmarkInfo` 報的 `Decode Speed` —
  engine 開始產 token 之後的穩態吞吐量，跟 prefill / 每次 call init 分開算。

看 CLI 端到端：暖機後每次 call wall time **3-8 秒**（短 prompt 是 init
主導；長段生成把 init 攤掉）。
S25 上 60 token 輸出大約 3 秒 wall ≈ 20 t/s 端到端，vs APK GPU
3 秒 / 62 token ≈ 23 t/s — 短輸出大致打平。CLI 的 decode-only 優勢
（S25 35 t/s）要在生成夠長、init 被攤掉時才看得出來。架構原因見下面
「為什麼 CLI CPU 贏過 APK GPU」。

**每次 call 啟動延遲**是另一個面向：APK 把 `Engine` 常駐記憶體，所以暖機後
`/api/generate` 短回答 200-1000 毫秒。CLI 每次 call 都要重新 init engine，
所以每次 wall 大概是 **3-8 秒（暖）/ 10-20 秒（首次）**。要選哪條路看你
要優化「每次 call 延遲」（APK）還是「穩態 decode 吞吐量」（CLI）。

`gemma-4-E4B-it`（3.4 GB）— 只有 APK 路徑測過：

| 裝置 | E4B CPU | E4B GPU |
|---|---|---|
| Galaxy S21+ | 4.1 t/s | （未測） |
| Galaxy S24 Ultra | 5.7 t/s | （未測） |
| Galaxy S25 | 7.8 t/s | 15.0 t/s |
| Galaxy Note 9 | 不支援（minSdk=33 / Android 13+） | |

**第一次 call 的初始化成本：**

- **APK GPU：** OpenCL kernel-compile 第一次要 8-22 秒。SDK 把
  `model.litertlm_*_mldrift_program_cache.bin` 寫進 app filesDir；之後
  重啟 service 跳過 compile 直接用 cache。重推模型會讓 cache 失效。
- **APK CPU：** XNNPack weight cache 第一次 3-7 秒；暖機降到 0.5-0.8 秒。
- **CLI CPU：** 首次 call 在模型旁邊建 xnnpack cache（~10-20 秒、一次性）。
  之後每 call 只付 engine init（~2-3 秒）。`install-termux-native.sh` 安裝
  最後會自動跑一次暖機，所以使用者第一次互動已經是暖機狀態。

**為什麼 CLI CPU 贏過 APK GPU：** CLI 是緊湊的 native binary，多執行緒
CPU 推理 — 沒 JNI、沒 service framework 開銷、沒 foreground 通知、沒
per-call socket round-trip。APK GPU 要付 SDK 的 JVM↔native 橋接 + 每次
loopback HTTP。長段生成（decode 主導）CLI 的原始吞吐量贏；短互動
（init 主導）APK 的常駐 engine 贏。

---

## 已知問題

### Note 9 / Android < 13

`minSdk=33`（Android 13）。Galaxy Note 9 這種更舊的裝置，APK 安裝會被
`INSTALL_FAILED_OLDER_SDK` 擋下來。沒有計畫降低門檻 — LiteRT-LM SDK 本身就要 API 33+。

### 非 Snapdragon SoC（Tensor、Exynos）

GPU 只在 Adreno（Qualcomm Snapdragon）驗證過。其他 SoC 系列各自有 LiteRT-LM 上游 issue：

- **Pixel / Tensor G3+：** Tensor 沒暴露 OpenCL — 見
  [LiteRT-LM #1860](https://github.com/google-ai-edge/LiteRT-LM/issues/1860)。
- **Exynos（S26 / Xclipse）：** ANGLE-CL 下的 Clspv kernel bug — 見
  [LiteRT-LM #2114](https://github.com/google-ai-edge/LiteRT-LM/issues/2114)。

如果你的裝置 GPU init 回 `INTERNAL ERROR ... compiled_model_executor.cc:1928`，
就在 request 裡 fallback 到 CPU：

```bash
curl -s http://127.0.0.1:11434/api/generate \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"hi","backend":"cpu","stream":false}'
```

---

## 解除安裝

```bash
adb shell am force-stop dev.temuxllm.service
adb uninstall dev.temuxllm.service
adb shell rm -rf /data/local/tmp/litertlm /data/local/tmp/bin/litertlm
```
