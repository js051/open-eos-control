# Android instrumentation fixture 初始化與 preflight

適用於 Compose → ViewModel → repository → synthetic HTTP peer 的 instrumentation 測試。
這些測試是自動化協定證據，不代表實體相機驗證。

## 執行緒規則

- 在 instrumentation test worker 執行 `MockWebServer.start()`、URL／hostname 解析及 fixture 關閉；不要移入 `runOnIdle`、`runOnUiThread`、`runOnMainSync` 或 Compose content。
- `MockWebServer.url()` 可能透過 `hostName` 執行 DNS 查詢。`val baseUrl get() = server.url("/").toString()` 並非無副作用讀取；`by lazy` 也可能在 UI 首次存取時解析。
- 在 worker 完成初始化後，UI 只讀 local `val` 或已計算、對外唯讀的 cache 字串。每一個 replacement peer 都要遵守相同順序。
- 不可用放寬 StrictMode、增加權限／timeout、吞掉例外或減少產品斷言來修復 fixture threading。

```kotlin
private class TestPeer {
    private val server = MockWebServer()
    lateinit var baseUrl: String
        private set

    fun start() {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Fixture initialization must run on the instrumentation test thread."
        }
        server.start()
        baseUrl = server.url("/").toString()
    }
}

@Before fun setUp() {
    peer.start() // instrumentation worker；replacement peer 也先在此執行緒 start
    val baseUrl = peer.baseUrl
    compose.runOnIdle {
        viewModel.setBaseUrl(baseUrl)
        viewModel.connect()
    }
}
```

## 有界 preflight

先設定該 PR 的實際 base commit，只列新增／修改的 instrumentation 檔案，包含未提交工作；不要把相疊 PR 的共同基底誤算成每個 PR 的新增內容。

```bash
BASE="<actual-pr-base-sha>"
mapfile -t files < <(
  {
    git diff --name-only --diff-filter=ACMR "$BASE"...HEAD -- android/app/src/androidTest
    git diff --name-only --diff-filter=ACMR HEAD -- android/app/src/androidTest
    git ls-files --others --exclude-standard -- android/app/src/androidTest
  } | sort -u
)
if ((${#files[@]})); then
  rg -n 'runOnIdle|runOnUiThread|runOnMainSync|setContent|MockWebServer|\.url\(|hostName|\.start\(|\.shutdown\(|baseUrl|get\(\)|by lazy' "${files[@]}"
fi
```

搜尋只提供候選，不是靜態證明。逐一追蹤：

1. 先讀所有 UI block 使用的 fixture getter、lazy property 和 helper 定義，再判斷是否會啟動 server、解析 hostname 或等待網路。僅搜尋 UI block 內的 `server.url` 會漏掉間接呼叫。
2. 確認 initial、reconnect、replacement peers 都在進入 UI block 前 start 並完成 URL cache。必要時只追加讀取它們直接引用的共用 fixture。
3. 保留在 worker 的 cleanup；fixture dispatcher 自己的 HTTP 工作執行緒與 UI 執行緒須分開判讀。
4. 記錄實際風險呼叫鏈、已安全 cache 的路徑與核對範圍；不要把文字搜尋無命中當成全套測試通過。

## 分層驗證與故障分類

1. 最終修改後先跑 `:app:assembleDebugAndroidTest`。這只證明編譯與 APK 打包，不能證明 fixture 成功初始化或產品行為通過。
2. 在要求的 emulator API 上跑 `connectedDebugAndroidTest`，核對 exact-head SHA、實際執行數、JUnit XML failure stack 與必要 UI 行為；每次後續 source 修改都須重新驗證。
3. 若所有案例都在 `setUp` 的同一 getter 因 `NetworkOnMainThreadException` 終止，應記為「fixture 初始化失敗，產品行為尚未執行」。不能宣稱為多個產品行為失敗，也不能以環境問題排除；CI 仍是紅燈，必須修復並重新跑 runtime。
4. 初始化修復後若出現新的 runtime 失敗，依新的 stack／產品斷言重新診斷，不沿用前一次根因，也不把單次 build 成功描述為 PR ready。
