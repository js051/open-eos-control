# Android 拍後唯讀查找：觀察結果與讀取證據

狀態：implemented；尚非 PR ready、main accepted 或已發布。來源基準為
`6c578399e3ac49dc3fecec2f29377b734c3750f1`。本批未 commit、push、啟動 CI、下載、安裝或操作相機。
逐檔 SHA-256、實際測試結果與證據等級記於同名 `.json`。

## 行為與安全邊界

- 分開保存觀察結果 `FOUND`／`NO_CANDIDATE_OBSERVED`／`UNREAD` 與不可變的
  `successfulReads`、`failedReads`、`lastCompletedRead`（success／failure／未完成時 null）。
- 各次查找重新累計；前次成功不能掩蓋本次全失敗。成功必須是有效解析清單，不能只憑 HTTP 200。
- 成功清單曾未找到符合條件的新 ID、但最後一次讀取失敗時，保留兩項事實；按鈕的 tooltip／無障礙描述及
  狀態 dialog 均顯示英文／繁中雙重說明。全失敗仍為 READ_FAILED；最後成功且無候選時保留普通 NOT_READY。
  文字不宣稱整張卡沒有新素材、不保證素材來源，也不把清單失敗當成拍攝失敗。
- 保留原本狀態機、舊素材物件、不可變 previousIds snapshot、明確 PHOTO／VIDEO／RECENT、
  第一個符合條件的相機回傳順序、每次八個候選上限與四輪查找（間隔 250／750／1500 ms）。
  retry 不新增 shutter、REC、AF 或其他相機寫入，不增加整卡 baseline。
- 取消／owner 或 session 取代不能發布部分或晚到結果。仍屬同 owner 的取消會還原原有 terminal 顯示、
  保留原有 evidence 並停止 loading，涵蓋零次完成讀取／coroutine 啟動前及 backoff 中取消。
  若 FOUND 已接受，後續縮圖取消只清 loading，不回復舊結果。舊 owner 不能改寫後繼 owner。
- 完整有效的 event／gallery 更新以自己的單次讀取 evidence 更新；既有完整性、owner 與 stale response 守門保留。

## 必要 adapter 修正

原 simulator 清單缺少 items／items 類型錯誤會被視為空清單，無效 ID 也可能被過濾掉。
現在缺少／null／非 array 的 items，以及 null item、空或非字串 ID／name 都會失敗。
原生 CCAPI path 清單與 page-count metadata 也驗證形狀與 identity；原有 transport fallback 不變。
合法 `items: []` 或 `path: []` 仍算成功空清單。

## 已執行證據

| 等級 | 結果 | 範圍 |
| --- | --- | --- |
| 實際 pure JVM 執行 | 44 JUnit tests 通過 | production 日期 parser／library／review policy；真實 coroutine；原始 DTO 區塊原字節抽取 |
| 固定跨端契約 | 106 terminal + 2 retry + 4 retirement cases 通過 | 固定預期值、兩種意圖、16 組 S/F、empty／known-only／wrong-kind、各輪新候選、取消與重試 |
| 來源／資源檢查 | 37 tests 通過 | 含五個新增 evidence／owner／UI／adapter guards；既有 RTP/LUT guards 保留 |
| Kotlin PSI | 15 個變更 `.kt`／`.kts` 語法通過 | 僅語法；不是 Android SDK typecheck 或 runtime |
| CI 同範圍 lint | ruff 通過 | 從 bridge 執行 `python -m ruff check . ../scripts/validation` |
| diff | diff-check 通過 | 無 whitespace error |

JVM harness 使用既有 receipt-checksummed Kotlin 2.2.21／JUnit 4.13.2／coroutine jars，compiler 上限
768 MiB，JUnit 上限 256 MiB。沒有下載／安裝，也沒有假造 Android API。

```sh
python scripts/validation/run_media_date_review_jvm.py \
  --jvm-tools <existing-pinned-EOS-jars> \
  --output build/capture-review-evidence-20261015
python -m pytest -q scripts/validation/tests
(cd bridge && python -m ruff check . ../scripts/validation)
git diff --check
```

新增固定 resource 為 `contracts/capture-review-evidence/v1/review-evidence-vectors.json`：
25,745 bytes，SHA-256 `aab83ace169997c2f9788ea45f2863513f0a0d99e3211143c0e60475eb7ec7b1`。
正式 loader 與 Gradle mapping 已加入；離線 runner 只替換 fixture 載入層為明確標示的 data-only provider，
production policy 與 JUnit 測試本體照原檔編譯執行。

## 未執行與限制

- `org.json:20240303` 正式 resource loader、Gradle resource mapping 與完整 Android SDK 編譯：NOT_RUN。
  先前受阻的官方 checksum／下載路徑未重試，也沒有改用其他路徑下載。
- 真正 HTTP／JSON adapter 解碼、ViewModel→repository→HTTP integration：NOT_RUN。
  JVM malformed cases 測的是明確的「adapter 已拒絕清單」邊界，不是實際 decoder。
- 已加入 native regression source：simulator／CCAPI 各種無效清單、合法空清單恢復、mixed 後全失敗 retry、
  零讀取取消、backoff 取消、晚到舊 owner，以及原有零寫入／原素材 identity assertions；需 SDK 環境執行。
- 已加入 Compose EN／zh-TW button／dialog、最後成功恢復普通文字、舊素材與零 shutter／REC／AF callback
  regression source；UI／無障礙／語系 runtime：NOT_RUN。
- foreground import／Files 原生整合與實體相機：NOT_RUN。日期、RTP/LUT、Files 既有 production cohort 保留。
- 既有 MIME-only 影片分類差異不在本批範圍，也未改動。

## Release Assessment

- 最近先前已核實 release baseline：v0.12.0 Development Preview；目前開發版本 0.13.0，本批未改版本。
  此工作未重新查詢線上 release 狀態。
- proposed impact：本 evidence cohort 為 patch，修正既有拍後查找結果／錯誤證據與無效清單誤判；
  相對先前已核實 v0.12.0 Preview 的累積開發功能仍為 minor。
- blockers：正式 JSON loader、SDK／ViewModel／Compose 原生驗證及 exact-head CI 尚待；本文件不代表可合併或可發布。
- physical-device status：待驗。模擬或純函式測試不能支持未驗證的相機相容性或實體可靠性宣稱。

協調端追加本地聯合檢查：scripts/ci/tests、scripts/validation/tests、scripts/contracts/tests 共 80 項通過（含前述 37 項，不重複加總），1 個既有 test_tree return warning。
