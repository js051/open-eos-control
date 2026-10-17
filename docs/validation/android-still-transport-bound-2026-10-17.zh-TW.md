# Android CCAPI 單張拍攝傳輸有界化

## 範圍與原因

基線 `34669de2` 的 `captureStill → commandOk → requestOk`，在沒有錄影／設定寫入 evidence 時走一般 mutation client，沒有總 call timeout 或取消 watcher，且以 `body.string()` 無界讀取。相機持續緩慢送 body 時，取消與 Disconnect 的原有 join 仍需等待該 producer。

本次只讓單張拍攝的直接命令與 manual full_press／release明確選用既有單次傳輸策略：最長 15 秒總 call timeout（保留呼叫端更短的設定）、禁止重播／重新導向、最多 65,536 bytes 且完整有效的 JSON response。加入 Call.cancel watcher，但保留同步 execute／body 的 coroutine join。

manual release 仍由原 `ShutterReleaseSession` 在 NonCancellable 內執行並等待結束，絕不讓 release 超過尚未結束的 press producer。release 失敗保留原 retry obligation；stop lambda 自身攜帶 transport flag，後續明確重試也不失去限制。

非目標：不改 simulator still、Bulb／AF／其他命令，不解除 Disconnect join，不分離 mutation callback，不改正式發布或支援機型聲明。

## 驗證分層

- JVM runner：`scripts/validation/run-android-still-transport-host.py`。逐字擷取 production requestOk，編譯完整 ShutterReleaseSession 與 bounded JSON readers；以 synthetic synchronous Call/Response/InputStream 驗證取消仍 join producer、manual release 順序與仍需 join、超大 body 拒絕。runner 另檢查三條真實相機路徑 opt-in 以及既有 15 秒設定的 source routing。
- `CcapiClientTest` 新增四個 MockWebServer 案例：緩速 body 的取消與禁止重播、超大 body、manual 取消仍等待匹配 release、release timeout 保留 retry obligation。最後一項注入 250ms callTimeout，驗證更短的呼叫端限制；不是 production 15 秒實測。
- Host fixture 不模擬 total deadline；15 秒上限目前只有 source/routing 檢查，不是計時通過證據。
- 目前工作環境既有工具沒有 OkHttp／Okio／MockWebServer jar，不能執行後者；未下載依賴。
- JVM synthetic 證據不等於 OkHttp 真實 socket、完整 Android 編譯或實機測試。真實 socket／相機的 release 時序與超時仍待驗證。

## 已執行結果（2026-10-17 22:27 UTC）

- 同一個 synthetic runtime 測試集對基線 `34669de2`：編譯 exit 0，JUnit exit 1，3 tests／3 個指定 assertion failures；不是編譯失敗或缺依賴造成的假 red。
- 對本次 production 修正：編譯 exit 0，JUnit exit 0，3 tests 通過，JUnit runtime 0.155 秒。
- 兩階段 sequential runner 整體 exit 0。每個 JVM 上限 `-Xmx256m`／`ActiveProcessorCount=1`，只用既有 SHA-256 校驗工具，無下載／安裝。
- 確認取消終止並 join synthetic response producer；manual release 在前一 producer 結束後才開始，且仍需等待 release body；已知過大 body 在接受命令前拒絕。
- 輕量收據與 JUnit 原始輸出：[evidence/android-still-transport-2026-10-17](evidence/android-still-transport-2026-10-17)，manifest 保存交付檔案與原始收據 SHA-256。收據的 filename 與 SHA 分列，以避免 `CcapiClient` 名稱加 hash 被秘密掃描誤認為 API key；保留所有驗證值，不改掃描規則。
- 可重現：用 runner 的 `--phase baseline`／`--phase current`，以 `--jars` 指向既有安裝收據目錄、`--out` 指向不同輸出目錄。receipt 內保存 source/extracted method/fixture/tool hashes 與退出碼。
- 四個 MockWebServer 案例仍為 authored-only，不列入 3 個通過測試。未執行 production 15 秒 deadline 的計時測試或完整 Android 編譯。

## Release assessment

- 影響：patch，既有拍攝在取消／Disconnect 時的回應讀取上限修正。
- 尚未完成正式 CI、Android 全套與實機驗證；不宣稱 PR ready、main accepted 或 preview released。
