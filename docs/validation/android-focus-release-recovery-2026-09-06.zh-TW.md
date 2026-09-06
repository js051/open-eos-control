# Android 短按對焦釋放與恢復

## 重現與範圍

以 `v0.9.0` main `361c8fb081332c2ed217b094b939e35f05c7cad8` 為基線。按住 AF-ON 已保留停止失敗的重試責任，但短按 AF、manual half-press 與 AF 的 manual fallback 使用一次性的 `withGuaranteedRelease`：清理雖會執行，失敗後不保存 release；一般操作結束又會清掉 busy。因此未確認停止時仍可能允許新的拍攝或對焦命令。

新增的四個協定回歸在修正前全部失敗，涵蓋 native AF、manual POST 半按、manual PUT 的 AF fallback，以及 start／release 同時失敗。這是可重現的 App 失敗恢復缺口，不是使用者機身失焦問題的因果證據。

## 協定與實作

- 沿用相機公告的版本、method 與命令，不新增 vendor endpoint 或猜測 AF 模式。[固定版本 Canon Android sample 的第三方鏡像](https://github.com/Chieri-JN/Intervelo/blob/bb826a1211c49a6d3a569ec8c4b9934b585db270/CCAPI_Sample_Android_1.3.0e/Sample/app/src/main/java/com/canon/ccapisample/RemoteCaptureFragment.java) 區分 AF 的 `start`／`stop` 與 manual shutter 的 `half_press`／`release`；此鏡像不是 Canon 官方下載站，未將 SDK 內容納入 repo。
- Android CCAPI 的 bounded AF、manual half-press 與簡化 Simulator 對應命令共用既有 `HeldAutofocusSession`，讓失敗 release 保留原始操作，直到成功回應或連線關閉。manual release 仍送 `af=false`。
- 不延長短按時間，不把半按能力推定為按住 AF 能力。開始失敗仍嘗試釋放；開始與釋放都失敗時，以未釋放錯誤為主，保留開始錯誤作 suppressed evidence。取消仍使用不可取消的清理，且清理失敗可重試。
- Repository 的短按 AF／半按與連線切換互斥，斷線清理不會跨到新 backend。ViewModel 忽略已離開連線的遲到對焦結果／錯誤，避免把新連線鎖成舊的失敗狀態。
- 對焦進行中禁止衝突的拍攝、錄影切換、模式及設定寫入；反方向也阻擋在衝突寫入中開始對焦。唯讀狀態仍可讀取。釋放失敗保留 FOCUS busy 與 interlock，只有原始 release 成功後才解除。
- 無原生按住 AF 能力的相機，也會在失敗時顯示釋放恢復按鈕。按鈕使用固定 56 dp 區域與停止圖示，附英／繁中 tooltip、content description 與狀態；不在小框塞入會隨大字體裁切的英文標籤。重試中保留控制並停用重複啟用，成功後不留下沒有能力的 AF-ON 按鈕。

## 驗證

- Android 507 項 unit tests 通過，包含四項新協定回歸、取消後保留失敗 release、雙向 busy 互斥；既有 held-AF 與其他控制測試保留。
- `lintDebug`、`assembleDebug`、`assembleDebugAndroidTest` 通過。
- HTC U24 Pro／API 34 使用獨立 `.debug` package 與本機 MockWebServer，`CameraFocusSessionTest` 21 項與 `CameraScreensTest` 111 項全部通過，共 132 項，不重複累計 focused runs。
- 正式 UI-to-HTTP 驗證：短按 AF stop 失敗後無快門／第二次 AF／半按寫入；點恢復只送 stop，成功後快門才可操作。manual-only 相機從更多設定觸發半按、失敗後返回關閉設定、顯示恢復控制，重試只送原始 manual release；全程不送 native AF。
- 延遲開始回應期間阻擋其他拍攝／對焦；隨即斷線再連線，舊釋放失敗不得污染新連線。disconnect 仍嘗試停止，但不把失敗清理偽稱為機身已停止。
- 恢復控制的顯示／點擊涵蓋 360×800、800×360、800×1280、四方向、英文／繁中及 1.5 倍字體；24 個組合皆為 stop-only 回呼，觸控區不與標頭或曝光列重疊。三種尺寸截圖已檢查。既有 HUD 長字與 Photo/Video 標籤在旋轉、大字體下仍可見截斷，不將本輪控制驗收擴張為全 App 排版完成。
- 首輪 HTC 的 manual-only 測試因設定與錯誤提示都有「關閉」描述而選到兩個節點；改為執行 Activity Back dispatcher 並確認設定關閉，未刪除釋放／互斥斷言。截圖先發現英文 Release 標籤裁切，再改成標準圖示並重跑完整 132 項。曾誤用未引入的 Espresso helper，編譯即拒絕；改用現有 Back dispatcher，未增加依賴。

## 限制與 Release Assessment

本輪沒有向實體 R6 Mark III 發送 AF、半按、拍攝或設定命令，沒有覆蓋正式 Preview，沒有操作 Serein 或其 emulator。使用者暫時不能進行的機身半按／成片比較仍 pending；網路中斷或程序強殺依然可能使釋放無法送達，必須確認機身狀態。

停止失敗恢復的新增 backend 行為限定 Android CCAPI；USB／Bridge／PC／iOS 的 release contract 未在此輪同步。普通 manual full-press 與 Bulb 使用各自既有流程，本輪不宣稱它們新增了同一恢復控制，也不新增按住 AF 同時拍攝。

最新已發布基線為 `v0.9.0 Development Preview`。此修正補回既有對焦操作的失敗恢復，impact 為 `patch`。功能 PR 不改版本或建 tag；必須通過 exact-head CI、squash main acceptance，再由獨立版本 PR 交付下一個 Preview APK。真機驗收仍待完成，不宣稱 stable 或光學失焦已修復。
