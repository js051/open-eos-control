# Android Bridge Live View 停止責任候選

原 Android direct CCAPI 已有 pending stop；Bridge client 尚未將開始回應遺失／停止失敗接入相同 repository 的停止恢復機制。本批只對齊此原 P1 路徑，沒有改 direct CCAPI 或新增相機命令。

Start dispatch 前保留原 Bridge session object 與固定 Stop URL。新 Start 在責任存在時拒絕；Stop 成功前不清除，取消不代表相機沒有收到命令。Backend 的 `liveViewStopRequired` 接既有 repository/UI，既有關閉預覽動作可重試相同 Stop。Close 退休舊 session，重用 wire ID 的新 session 不繼承舊責任。

新增 optional `CameraStatus.liveViewStopUnconfirmed`；只有 true 會建立未確認責任，false／缺欄位不解除已有責任。狀態讀取以 session 與本地 lifecycle revision 隔離，停止跨過舊讀取時必須重讀，不能將舊 true 寫回停止後的新狀態。

Stop 需收到 `active=false` 及明確 `liveViewStopUnconfirmed=false`。已確認合法 Start 的第一次 Stop 可相容舊版只有 active=false；一旦 Start 或 Stop 不確定，舊版 bare false 不再證明停止成功。錯誤或不合法型別保留責任，不自動重試 Start。

Start 接納時同時記錄 lifecycle revision；若 HTTP 回應等待期間收到新的停止警告，延遲抵達的 `active=true` 不得恢復舊版 Stop 相容資格，原停止責任仍保留。`DesktopBridgeLiveViewRecoveryTest.lateStartAcknowledgementCannotRestoreRevokedLegacyStopProof` 以受控延遲涵蓋 Start → 狀態 true → 延遲 Start ACK → bare false 拒絕 → 明確 false 恢復；此測試與 Kotlin 編譯目前均為 `NOT_RUN`。

Start 回應即使包含 `active=true`，若同時傳回明確 Boolean `liveViewStopUnconfirmed=true`，仍拒絕開始成功、保留原停止責任且不給予舊版 Stop 相容資格。此 optional 欄位維持既有相容處理，不強制轉型；缺欄位或 null 的合法 Start 仍可相容舊版第一次 Stop。`startStopWarningCannotGrantLegacyStopProof` 涵蓋明確 true 拒絕及明確 Stop false 恢復；既有 `knownSuccessfulLegacyStartStopRemainsCompatible` 增加 null 正向控制，執行狀態均為 `NOT_RUN`。

新增 MockWebServer 單元來源涵蓋：Start ACK 遺失、Stop 失敗及重試、legacy 正反控制、不合法 Stop 證據、狀態 true/false、延遲狀態跨 Stop、取消及同 ID 新 session。當地 Kotlin 編譯與 runtime 尚未執行，不能將這些來源稱紅綠已證；exact-head 必要 CI 待本完整批次批准的 runner 範圍與正常交付。

這是合成 Bridge 通訊測試候選，不是實體相機停止或 Android 背景生命週期證據。公開欄位向後相容，沒有憑證、來源路徑或其他產品內部資訊。

UI 會持續保留本連線的停止警告，後續 false／缺欄位狀態不能清掉。即使 Live View capability 消失，仍提供既有 Stop 重試；確認停止只清理原責任，不隱式再 Start。斷線時 repository 仍嘗試原停止與關閉，若結果未確認，UI 保留「前一台相機」警告而不將責任轉給新連線。新增 15 個 HTTP／repository 測試方法及 3 個 UI-state 測試方法；全部 Kotlin 執行與編譯仍為 NOT_RUN。

## 首輪完整 CI 的單元失敗

原 cd1afe／run38412149915 的 JVM gate 執行1162個case、1個失敗。`DesktopBridgeShutterRecoveryTest.statusWarningBlocksMutationsAndRetainsStopEndpoints` 的模擬peer把 liveview/stop落到generic bulb-status回覆，缺少停止確認欄位。新版production依契約拒絕該回覆；修正僅為該fixture的liveview/stop明確回active=false、liveViewStopUnconfirmed=false，原命令封鎖、Stop可達、次數斷言均保留。此修正目前僅source review通過，不能沿用原run或其他平台結果稱新候選已通過。

同run的API34／36 raw XML均為360案、6失敗、0略過；12個failure stack都在`CameraBridgeShutterRecoverySessionTest.setUp:91`等待LiveView影格。該共享peer原Start／Stop都回`{}`，無法滿足新Start確認契約。修復將兩條正向回覆分開為active=true與active=false/stopUnconfirmed=false，保留原15秒期限、6個bulb負例及命令次數斷言。這是來源與實際setup失敗一致的修復候選，新候選runtime仍待完整CI，不把舊354個通過當修復後全綠。

## 配對版本界線

新的explicit Stop確認fixture對應PC草稿#237的Bridge修正（776a239）：LiveViewState新增可選停止證明，Stop route在確認清理後回傳該欄位。Android草稿自己的Bridge目錄仍是原基底版本，不能據其Android mock通過聲稱該整樹已驗跨端互通。舊Bridge只在已確認Start的legacy安全條件下可完成Stop；模糊Start或Stop-only責任需更新Bridge。三候選尚需正式整合與集中runtime驗證。

相容性以回覆證據判定，不能用目前兩份Bridge均標示的0.13.0字串猜測功能存在。已確認Start且期間未出現不確定證據時，`knownSuccessfulLegacyStartStopRemainsCompatible`保留舊Bridge的active=false相容路徑；一旦Start回覆遺失、Stop失敗或新status警告，舊版缺欄位不能安全解除責任。`lostStartIsNotReplayedAndRequiresExplicitStopProof`、`failedStopKeepsOwnerAndCannotUseLegacyFalseOnRetry`及warning競態控制保留拒絕條件。UI的EN／zh-TW `live_view_stop_unconfirmed`明示只重試Stop、必要時更新Bridge；不以忽略停止證明換取舊版表面成功。發布時必須把對應新版Bridge與手機候選一起整合驗證，不能僅憑手機mock結果發版。
