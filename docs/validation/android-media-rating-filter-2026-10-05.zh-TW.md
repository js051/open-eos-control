# Android 素材評分：已載入資料的篩選、排序與交付

## 範圍與資料契約

本批建立在素材日期範圍的本地來源 `44888573`，其發布基準為 `3af7df40`（v0.11.0 Development Preview）。對應產品缺口矩陣 P2「找到並挑出要交付的素材」的評分篩選／排序。

- 只使用目前 `CameraMediaItem.rating` 已讀到的值；沒有新增相機 endpoint、metadata 批量讀取、全卡自動掃描、相機寫入種類、Android permission 或依賴。
- 有效評分為 0–5 整數；0 是明確未評星，缺少／不合法值為未知。`MEDIA_RATING` 只代表既有寫入能力，不能證明列表有完整評分，也不限制使用已知的唯讀評分。
- 可選全部、0 星、1／2／3／4 星以上、5 星、未知。日期、種類及評分取交集；單獨清除不改其他條件。
- 評分高→低與低→高都將未知排最後，同分與未知之間保留來源順序；RAW＋JPEG 各自保留原 ID。評分排序不產生日期分組標題。
- 提示明列已載入數、符合數、已載入內容中的未知評分數；Recent／部分／取消／失敗列表仍顯示未搜尋其他卡片內容。未知可透過既有單張素材操作入口讀取資訊，但相機不一定回傳評分。沒有將全卡列表稱為完整評分索引。
- 稽核顯示 direct CCAPI 與 Bridge CCAPI 全卡列表多數無評分；USB 只對部分樣本讀取，host 素材通常未知。重新載入列表可能取代先前單張讀到的評分；本批不把舊值推測性保留為最新值。

## 操作與隔離

評分篩選由 ViewModel 持有，同一 VM 的 composition restoration／離開再回相簿保留；切換／重連相機、離線展示及新 VM 回到全部。menu callback 比對原 CameraInfo reference identity 與 media UI generation，避免相等欄位與重複 ID 誤認成同 session。

既有單張／批次評分及資訊讀取回應仍以 ID 更新素材，篩選與排序隨確認資料重算。direct CCAPI 評分寫入沿用既有 readback；失敗不把希望值填入已確認欄位。Bridge client 檢查其 PUT 回應的 item ID 與評分均符合要求；未知或不同評分不能回報寫入成功，不重試、不補讀，也不表示相機確定沒有執行。Bridge 單張資訊同樣核對被請求的 item ID，拒絕用其他素材的回應更新列表。這不是宣稱每個 backend 都由 Android 另做相同 readback。

被隱藏的已選 ID 保留；固定短提示顯示批次包含多少隱藏項目。全選／取消全選只改目前顯示項目。篩選仍可在原檔下載期間操作，下載 owner、名稱、進度、取消與精確 bytes 流程不變。篩選外的 capture review 以原 ID 單張 1/1 開啟；原拍後選圖 helper 不變。

## 驗證界線

以下測試隨本批提交；最終執行結果由同次來源驗證補記，不能以新增測試檔案當作已執行：

- 純 JVM：未知／越界與 0 區別、各星等邊界、唯讀評分、兩向排序與 stable ties、日期＋種類＋評分交集、RAW＋JPEG ID、選取隱藏項目與 capture review；display reducer 對日期／下載狀態的保持與 stale-session callback 拒絕。
- 真 App → CameraActions → ViewModel → Repository → 合成 HTTP peer：評星後找到並預覽正確 JPEG、direct CCAPI readback 503 不捏造新評分、未知與 0 的部分列表提示且無額外 metadata、日期＋種類＋評分取交集，清除日期或評分時保留照片種類、兩張隱藏／可見 JPEG 選取後實際 MediaStore 原檔 bytes、批次評星後 RAW＋JPEG 被隱藏仍保留選取、下載中篩選仍持有原 owner／可見取消入口並完成精確 bytes（未另驗操作取消或逐步進度）、同 VM restoration／重連與 stale callback、範圍外 capture review 1/1。
- Compose UI：真 Activity 橫向 layout 完成後以 2 倍字級驗 rating menu；320 dp 寬且最高 320 dp 的 popup 與 320 dp 實際裁切的 filter row 分別比較完整 control bounds、祖先裁切、真 Android window bounds，再單次 physical tap，不能只用 ForcedSize 或可尋找到語意節點當可操作證據。
- 資料層另有 production CCAPI／Bridge public-path fixtures，拒絕 malformed／小數／溢位評分變成 0，以及其他 item ID 的資訊／寫入回應。逐次 red／green 與 aggregate 執行結果另補，不跨來源加總。

本地 focused 驗證（2026-10-05 UTC）：

- `:app:testDebugUnitTest --tests '*MediaRatingFilter*' --tests '*MediaLibraryTest' --tests '*MediaDateRange*'` 的 XML 共 5 suites、55 tests，0 failures／errors／skipped：新 rating helper 11、rating reducer 5，既有 MediaLibrary 25、日期 helper 9、日期 reducer 5。
- 同一次指令後接 `:app:compileDebugAndroidTestKotlin` 時，instrumentation 編譯因測試引用未提供的 Espresso API 失敗，因此整次 exit 1；不能將它報成 aggregate green。以既有 Android instrumentation key API 修正測試的 Back 操作後，僅重跑 `:app:compileDebugAndroidTestKotlin`，exit 0、`BUILD SUCCESSFUL in 37s`（27 tasks：1 executed、26 up-to-date）。沒有新增依賴。
- Kotlin／XML 逐檔 SHA-256 manifest 在各次執行前後都相同。第一輪 hash 為 `bd91e954382d7b87392cba35d76312b4c2143b4f48843107caf456ebeedbd03f`；僅改 instrumentation Back fixture 後的成功 compile hash 為 `051dd7c0d5533c6b3eeb2b338f29975a6309ff4f680428524f7c334d2a5093ec`。這是工作樹來源 fingerprint，不是 commit SHA，focused 結果也不是完整 aggregate 或 CI。

最終只讀審查未發現來源級阻擋。範圍界線：新增組合條件案例沒有實際清除照片種類；下載案例核對 owner、取消入口存在與完成 bytes，沒有另操作取消或檢查逐步進度。資訊唯讀 fixture 將非本題的旋轉值設為已知，避免未知旋轉的舊讀取文案干擾評分斷言；未因此變更旋轉產品行為。

## 最後同樹驗證與資料契約反例

完整 aggregate 於 2026-10-05 06:14:33–06:20:45 UTC 結束，exit 0、BUILD SUCCESSFUL 6m11s：App JVM **689/689**、61 suites、0 failure/error/skip；lintDebug 0 errors、57 warnings、2 information；兩 APK 組建成功。執行前後來源 manifest 相同：`3c6cf4792b9f4d13c464ce232f2097c695f9da2db46d1fa9cc937a0b6a74130e`。

之後只精簡兩個英文評分／計數字串，消除新增的單複數問題；沒有再改 Kotlin 或測試。最終驗證 2026-10-05T06:24:14Z–2026-10-05T06:26:31Z 結束，exit 0、BUILD SUCCESSFUL 2m16s，79 tasks 中 11 executed／68 up-to-date。App JVM 因程式與測試未變為 UP-TO-DATE，沿用上述同 Kotlin 的 689/689，不宣稱重新執行；Lint 重跑為 **0 errors、55 warnings、2 information**，兩 APK gate 成功。最終來源 fingerprint：`b7486822136cf6fafef0cb2e3f3666215ba238e6b5a4351f51cf241db852e67e`，前後一致。剩餘新增 warning 是測試可控制 popup 的 `menuModifier` 命名慣例；未加入 suppression、降低規則或改 gate。

| 最終本地產物 | Bytes | SHA-256 |
| --- | ---: | --- |
| App debug APK | 20,736,390 | `8f7cb03c19ef71ff14eacc0b71c484e29a44d68bcb1599844d5bf125772ee1ab` |
| Instrumentation APK | 1,586,834 | `3cabdba61096df9f93e65a5176f5e6669b02ae2eed4e23076509dfd37897469b` |

資料層使用真正 public client 與合成 HTTP peer 取得三階段反例：parser 6例／4失敗→6／0；回應身分 8／2→8／0；寫入結果確認 10／2→10／0。中間 write 測試曾把未知可解析誤當寫入成功，已依 `docs/desktop-bridge-protocol.md` 的 readback 契約更正；最終 10 例均納入上面的完整 689。32種 normalized wire 值涵蓋缺值、JSON null、bool、object、浮點／高精度小數、溢位與合法整数／字串；另有12組相符寫入、20組未知／無效回應、30組不同合法星等與錯誤ID斷言。未知讀取可表示，未確認寫入不能成功或成為 observed evidence。所有負例不增加 GET、不重送 PUT。

本環境沒有連接 Android 裝置／可用 KVM；instrumentation 編譯不等於執行。沒有新增真相機、真手機、API34／36 instrumentation、真正系統 picker、跨程序恢復或跨 backend 評分覆蓋保證。既有 Close 載入中預覽限制不在本批範圍。

## Release Assessment

最新已知發布基準 v0.11.0 Development Preview。本批有新的使用者可見篩選／排序能力，建議 impact `minor`；不在本分支變更版本或發布。PR ready 仍需此變更精確 head 的 `ci-complete`。物理相機評分來源／寫入／大卡表現仍待真機驗收。
