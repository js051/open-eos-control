# iOS RTP 生命週期 source assertions 對齊

## 範圍與根因

此批只維護測試與本文，不修改產品行為，也不屬於倍率 → 座標重新取得功能修正。

基線為 `9bde4450046ebd465f5cc873b80c91f0edd064ff`。`1d3a4a3660d9df52368a5534589f3aaa37b29ed9` 加入的三項 source assertions，未隨 `806cbf2d3b4acaf81ed2f5861ec50c770f24b034` 的 RTP 真解碼／呈現重構更新：

1. 首影格 decoder 已改為 controller 共用、可注入且預設為真實 `IOSRTPVideoProcessor`；舊檢查仍要求 session 內直接初始化的字串。
2. decode-only 現在於實際解碼輸出與完成時間成立後，回傳 `.decoded(at:)`，且早於 LUT 色彩、Metal 與輸出池處理；舊檢查仍要求已移除的混合色彩 guard 與無 timestamp 回傳。
3. `hasUsableRendererOutput()` 已由精確 retained pixel-buffer identity 與單調 presentation progress 取代。新版 `scripts/validation/tests/test_native_rtp_lut_source.py` 明確禁止舊 API，而舊 lifecycle assertion 仍要求它存在。

舊紅燈證據與原收據保留，不改寫為通過。

## 保留及強化的驗證

`test_liveview_lifecycle_source.py` 的原 14 個測試名稱全部保留；只變更上述三個測試，抽出同樣供負向 fixture 使用的 source guards：

- Start ACK → 實際 decoded readiness → policy 驗證 → active source publication 的順序；共享 probe 的 owner、terminal、cancel 與實體 decoder retirement。
- 真實解碼 status、非空像素與實際完成 timestamp，先於 decode-only 回傳；LUT 色彩、Metal、pool 與 renderer 不得冒充 readiness。
- 像素物件身分、owner／epoch／LUT／序號與呈現時鐘；重複舊影格或 unknown readback 不得更新／清除期限，AppState 仍由 progress failure 退休來源。

`test_liveview_lifecycle_guard_mutations_source.py` 新增 18 個記憶體內反例，分為三個 unittest 方法。每組先驗證未變更的來源可通過，再移除／繞過一個真正 guard，要求相同 assertion 拒絕：

- readiness 六例：跳過 wait、跳過 decoded policy、接受非 decoded probe、跳過實體退休、移除 close cancellation、接受 terminal 之後的 probe。
- decode-only 六例：忽略 decode status、移除像素／時間 guard、提早建立 Metal、以接收時間翻新解碼時間、讓未套 LUT 的路徑受 LUT 色彩 gate 影響、沒有輸出仍宣稱完成。
- presentation 六例：以任意非空 buffer 取代 exact identity、接受已退休 owner、重複序號刷新進度、unknown 刷新時鐘、AppState 忽略失敗、以處理完成通知時間取代實際 decode 時間。

這些只是 source 結構反例，不是 Swift mutation 執行或裝置測試。

## 驗證與限制

- focused source checks：15/15。包含原三項、三個負向測試方法（共 18 反例）、新版 native RTP structural suite 九項。
- 可重現的獨立 focused 驗證使用上述不可變基線 commit 的產品／既有測試來源，以及本批新 source guards，不受同時編輯的倍率功能來源影響。來源 hash 與命令另外記錄於本批收據。
- 首次 expanded runner 執行時，另一批倍率測試已寫入但其 Swift fixture 尚未存在，得到 108 項／1 失敗；三項舊斷言均通過。這份 mixed-snapshot FAIL 仍保留，不能當作 final aggregate。倍率批次將在完整快照再做 aggregate 驗證。
- Swift compile、Xcode／Simulator／原生 decoder／renderer、UI、相機／iPhone／iPad 與 exact-head CI：本批皆未執行。source 綠燈不能代替這些證據。

## Release Assessment

- 影響：`none`，僅測試與驗收說明。
- 不需產品版本變更；不代表 PR ready、main accepted 或 preview released。
- 原生與實機驗證狀態不變；沒有合併、發版、推送或安全設定變更。
