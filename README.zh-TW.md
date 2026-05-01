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
[裝置矩陣](#裝置矩陣)。

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
| 每次 call 的延遲 | 不到 1 秒（模型常駐） | 1-7 秒（暖）/ 12-60 秒（冷） |
| 需要 USB + 主機 | 要（一次性 sideload） | 不用 |
| 需要 sideload APK | 要 | 不用 |
| 適合場景 | 互動聊天、腳本 | 偶爾 / 批次使用 |

Native 路徑用「每次 call 多花一點 load 時間」換「完全不依賴 APK」。
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

## 裝置矩陣

`gemma-4-E2B-it`（2.4 GB）和 `gemma-4-E4B-it`（3.4 GB）的 decode 速率，50 字
output prompt，暖機後（已 drop 冷啟動），透過 APK service 的 in-process Engine。

| 裝置 | SoC | Android | RAM | E2B CPU | E2B GPU | E4B CPU | E4B GPU | GPU 冷啟動 |
|---|---|---|---|---|---|---|---|---|
| Galaxy S21+ | SD 888 (Adreno 660) | 15 | 8 GB | 8.0 t/s | 10.5 t/s | 4.1 t/s | （未測） | ~20 秒 |
| Galaxy S24 Ultra | SD 8 Gen 3 (Adreno 750) | 16 | 12 GB | 10.3 t/s | 22.0 t/s | 5.7 t/s | （未測） | ~11 秒 |
| Galaxy S25 | SD 8 Elite (Adreno 830) | 16 | 12 GB | 12.4 t/s | 23.2 t/s | 7.8 t/s | 15.0 t/s | ~8 秒 |
| Galaxy Note 9 | Exynos 9810 | 10 | 6 GB | 不支援（minSdk=33 / Android 13+） | | | | |

`decode = output_tokens / (total_duration_ms / 1000)` 的 end-to-end（包含 prefill）。

**第一次 call 的初始化成本：**

- **GPU：** OpenCL kernel-compile 第一次跑 engine 時要 8-22 秒。SDK 會把
  `model.litertlm_*_mldrift_program_cache.bin` 寫到 app filesDir；之後重啟
  service 會跳過 compile 直接用 cache。如果你重新推一次模型，cache 會失效，
  又要再付一次這個成本。
- **CPU：** XNNPack weight cache 第一次要 3-7 秒；暖啟動降到 0.5-0.8 秒。

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
