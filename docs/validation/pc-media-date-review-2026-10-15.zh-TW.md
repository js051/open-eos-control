# PC 素材日期精度與拍後選片

狀態：implemented。這是原本已封存交付／LUT 候選之上的獨立日期修正群，尚未推送、未取得本群 exact-head CI。不是三端已完全對齊或實體相機驗收。

## 行為與邊界

- 原始 captureTime、素材 ID、種類、RAW/JPEG 各自身分均保留。共享固定日期契約位於 `contracts/media-date-review/v1/`。
- 日期嚴格區分 explicit-offset instant、相機字面 floating、date-only 與 unknown。trim 後限 128 字元；Gregorian AD 1–9999、實際月長及閏年、24:00／秒 60／尾隨垃圾／矛盾 named zone 皆 fail-closed。
- explicit offset 限 ±18:00；最多九位小數截到共同毫秒。只有明確 offset 可產生 timestamp。浮動時間不套裝置時區、不正規化 DST 缺口；純日期不製造午夜 instant。
- date-only／floating 的內部 UTC Date 僅供格式化，不能當排序或因果證據。浮動詳細時間有英文／繁中「時區未知」；純日期不顯示時間。
- 同一 viewer-zone snapshot 驅動篩選、排序、群組、卡片與詳細資訊。前景返回、focus、pageshow 及一般畫廊 render 發現時區變更時，整組刷新；不改 literal range、選取 ID 或傳輸 owner。
- 日期排序為展示日方向、固定 instant → floating → date-only 精度分組、同精度真 key 方向，最後保留載入順序。unknown 永遠最後，同值不以檔名打破相機順序。此呈現規則不宣稱混合精度的真實拍攝先後。
- 最近素材／照片／影片是明確三態；先排除已觀察 ID，再限制種類並保留相機回傳順序。新可見不代表由本 app 拍攝；日期、檔名、ACK、畫廊篩選不可作新檔因果依據。
- 不改既有 session/generation、取消、有限唯讀重試、零 shutter／REC 重送、前景匯入完整 inventory／新 ID／JPEG／fresh 重複 metadata／尺寸穩定守門。

## 本地驗證範圍

- 共用 JSON 固定 48 個 parse 向量 × UTC／台北／洛杉磯、9 個排序案例、4 個 review 案例；期望由獨立 Python Gregorian／zoneinfo 算術事先編寫，不從被測 JS 產生。
- 真正 production parser、filter、sort、review 執行固定向量與 2,016 組排序三元排列。另以三種程序 TZ 跑相同模組，保留 UTC、台北與洛杉磯結果。
- 日期 UI 來源單元測試 21 項，包含同一 snapshot、純日期、DST 浮動時間、時區刷新、literal range／選取／傳輸 owner 保留及拍後獨立 preview。
- 原 194 項 capture-review 與 29 項 auto-import-owner 回歸保留；新 lifecycle handler 在測試 fixture 載入，不刪任何原 owner 或重送斷言。
- 全部 35 個 Node modules 入口通過：Node 報告 939 項、另有同命令內兩組 40＋20 項自訂計數；完整 Python pytest 553 passed、2 個既有 warning；實際 CI 範圍 ruff 與 wheel build 均 exit 0。終態記錄在本批獨立本地驗證收據。向量斷言與已計數單元測試不重複計入「總測試數」。
- 本地曾因 exec transport 斷線遺失一次 modules shell 退出碼；完整輸出仍保留。後續最終版採逐步 durable exit 收據，不把未知 poll 當成功。

## 尚未執行

- 真瀏覽器流程、WebGL、IndexedDB／File System Access integration：既有本地 browser 環境阻塞，未繞過受限下載。
- 本日期群 exact-head CI；新原生群的 iOS／Android SDK、UI 與正式 JSON loader 另行報告，不由 PC 向量通過推定。
- 實體相機日期格式、返回順序、時區變更實機 UI 與 transport 相容性。

## Release Assessment

- 已核實發布 baseline：v0.12.0 Development Preview；目前開發版本 0.13.0，本批未改版本。
- 本日期修正群 impact：patch，修正既有日期精度、排序、時區一致性與拍後選片，沒有新增相機命令。自 v0.12.0 起累積完整功能差異仍為 minor。
- 未解 blockers：新 exact-head CI、真 browser integration 及其他原生候選既有失敗；實機未驗不轉成相容性或可靠性宣稱。
- 保持原草稿 PR；本證據不是合併、ready 或發布指令。
