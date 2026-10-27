# 驗證入口與控制器責任調整

## 範圍與不變條件

- LUT 選檔／解析生命週期與媒體對話框焦點恢復各自移入專責控制器；保留現有 UI wiring、取消／重新選取、session retirement 與錯誤處理。
- 不新增相機命令或傳輸功能，不改 Stop-owner、ACK、原始檔保留及重試授權。
- 修正已確認的 workflow 依賴選擇缺口與 Python contract discovery；43個既有函式納入普通 unittest，其中41個 body 不變，另2個 Android owner／RTP identity source guards 依現行型別與完整傳遞路徑更新，保留並加強安全語義。
- 日期契約原本已由 media-library 間接執行；這不是新增 coverage。保留單一直接入口、移除跨測試 require，避免重複執行。
- 七個舊非同步測試入口交由原生 Node runner 管理，保留順序與原斷言；完成性 controls 同時驗證正常完成及未完成 Promise 必須失敗。只接受有完整 marker、計數及框架錯誤的取消結果，不把外層 kill 當成成功驗收。

## Runtime manifest 相容性

Formal manifest 每筆 row 都須符合 coordinate／flat filename／小寫64位 SHA256 schema，包括未載入的 artifact。只合併完整相同的 identity；同 filename 的 coordinate 或 hash 衝突必須拒絕，loaded class 的真實來源及 hash 檢查仍保留。

這是明示的輸入規格收緊，不是行為不變修復。舊的手工裁剪、缺欄位或型別不正確 receipt 會被拒絕；應重新取得正式 Gradle manifest，不能猜測補欄位。Supplemental mode 保留獨立規則，不等同完整 app 驗收。詳細遷移規則見 `docs/development-workflow.md`。

## 驗證範圍

- Node22 的既有 `npm run test:modules`：3,992個 runner cases 通過，另4個同步 assertion scripts exit0。最終完成性 controls 在 Node22／24各15PASS。使用 repo 的 `node file.js` 入口；額外套用 `node --test` 並非已支援的 invocation。
- CI helper145PASS；Python validation258PASS；pinned contracts65PASS；受 lint 格式修正影響的 runtime-validator23PASS。actionlint 與 repo CI Ruff 命令通過。
- 普通 `:app:testDebugUnitTest` 實際執行2,110個 tests：2,017PASS，93個 Robolectric 環境初始化失敗。只重跑這93個及15個 runtime controls，108/108PASS；來源、已編譯 classes 與 runtime artifact manifest 不變。這不是重跑後整套2,110全綠的宣稱，重疊15個也不重複計算。
- Formal runtime15/15PASS，包括新增10個 identity controls；原 release-boundary／Stop-owner assertions 保留。

確切 PR head 的必要 CI 仍須通過。上述 module／JVM 結果不替代瀏覽器互動、iOS／Windows 原生建置、Android 安裝或實體相機驗收；沒有新增這些平台或裝置的相容性宣稱。
