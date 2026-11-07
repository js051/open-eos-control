# Android 素材日期精度與拍後選片

狀態：implemented；尚非 PR ready、main accepted 或已發布。無實體相機驗證。

## 本批行為

- 原始 `captureTime` 與 item ID 不改寫。解析區分 explicit-offset instant、相機字面浮動時間、純日期及 unknown。
- 嚴格 trim 後全字串 Gregorian AD 1–9999；不接受非法日期、24:00、秒 60、尾隨垃圾及越界 offset。小數秒只截到毫秒，沒有 sub-ms 排序承諾。
- 保留既有 `Z[UTC]` 相容格式；有界已知 IANA zone 必須與明確 offset 在該 instant 一致。浮動時間加 `[zone]`、未知 zone 或矛盾 offset 皆 unknown。
- offset 或展示時區越出 AD 1–9999 時 fail-closed。浮動時間不套用手機時區或 DST；純日期不製造午夜 instant。
- 日期排序依展示日方向，再以固定 instant → floating → date-only 精度分組，只反轉同精度的真實 key。未知固定最後，未知與同值保持載入順序，RAW/JPEG 不因同時刻合併。
- 篩選、排序、分組、viewer 與 metadata sheet 共用同一展示時區 snapshot；系統時區變更及 resume 觸發整組重算。已交付 JPEG 列表亦用 dialog snapshot 與同一語系 label。浮動 detail 顯示 `Time zone unknown`／`時區未知`，純日期無 00:00。
- 最近素材／照片／影片為明確三態。review 先排除本次之前已觀察 ID，再限制種類，保留相機回傳順序，不依日期、星等、資料夾或畫廊排序挑選。新可見不宣稱為本 app 所拍。
- 拍照明確 PHOTO；停止錄影明確 VIDEO；無 capture attempt 的一般最近素材是 RECENT。既有 session/generation owner、取消、有限唯讀重試與零 shutter/REC 重送不變；不加整卡 baseline。
- 前景匯入的完整 inventory、新 ID、JPEG、fresh 重複 metadata 與尺寸穩定性守門沒有修改。

## 本地證據

使用既有 checksum 驗證 Kotlin 2.2.21／JUnit 4.13.2 jars，編譯 JVM 上限 `-Xmx768m`，執行上限 `-Xmx256m`，無下載或安裝。

執行：

```
python scripts/validation/run_media_date_review_jvm.py \
  --jvm-tools <existing-pinned-EOS-jars> \
  --output build/media-date-review-validation-20261015
```

結果：36 個 JUnit 測試通過，包括 48 個固定 parse 向量 × UTC／台北／洛杉磯、9 個固定排序案例、4 個 review 案例、所有向量的 comparator 反對稱／傳遞性、日界線／DST／年界線、filter/group/detail 一致性、毫秒相等 RAW/JPEG、舊 ID snapshot、bounded read-only retry 及 cancellation。亦執行更新後既有日期範圍與星等測試。

真正編譯執行 `MediaCaptureDate.kt`、`MediaLibrary.kt`、`MediaReviewSelection.kt`。`CameraModels.kt` 的純 DTO 區塊原字節抽取，原檔及區塊 SHA-256 記於 result；未假造 Android API。JUnit 測試本體不改寫。

`contracts/media-date-review/v1/date-review-vectors.json` 是三端共用固定預期值，並非由被測程式產生。正式 `org.json` resource loader 與 Gradle resource mapping 已加入；離線 harness 只以 Python 讀 fixture 產生 data-only Kotlin provider，未執行正式 loader。

證據檔：`build/media-date-review-validation-20261015/{result.json,compile.log,junit.log}`。初次執行僅失敗於舊「review 依日期最新」預期；依本批明確相機順序契約更新該 assertion 後重跑 36/36 通過。初次結果保留於 `build/media-date-review-initial-20261015/`。最終正常 stage 檢查修除一個 EOF 空行後，以同一 harness 重跑 36/36；最新來源 SHA 與 exit 收據獨立封存。

另通過 19 個變更 Kotlin 檔的 PSI 語法檢查（非 Android type-check）、74 個 Python 來源／契約 tests、CI 範圍 ruff 及 staged diff-check。多契約來源只排除同名 README.md 文件資源，所有 JSON fixture 與必要測試均保留。

## 尚未執行與限制

- `org.json:20240303` 正式 resource loader、Gradle／完整 Android SDK 編譯。
- 新增真正 ViewModel→repository→HTTP regression：影片先回傳且有 2030 日期，無日期新照片仍為拍照 review；既有 owner/retry/foreground integration 需 SDK 環境跑。
- 新增 Compose lifecycle 時區變更 regression、浮動／純日期 viewer label、英文／繁中已交付 JPEG 日期精度檢查；broadcast／lifecycle 原生 UI 尚未執行。
- 不宣稱實體相機 timestamp 格式、傳輸順序、實機 UI 或相容性驗收。
- 未推送、未啟 CI、未合併、未發版；未改安全／權限／必要驗收規則。

## Release Assessment

- baseline：最近核實已發布版本 v0.12.0 Development Preview；目前開發版本 0.13.0。本批未改版本。
- proposed impact：本日期群 patch，既有素材日期與拍後選片錯誤修正，無新遠端相機命令；自已發布 v0.12.0 起累積既有功能群仍為 minor。
- blockers：正式 JSON loader、完整 Android SDK／UI／ViewModel integration、exact-head CI 尚待；實體相機仍待驗，不能提出未驗可靠性宣稱。
