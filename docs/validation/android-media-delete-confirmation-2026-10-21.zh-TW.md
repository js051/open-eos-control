# Android 刪除確認名稱與目前項目一致性

## 範圍與來源

- 基準：`d01c07db87f59838da85d45b16275d2df20a1645`。
- 本批修正永久刪除檢閱清單的名稱授權，不新增刪除能力、transport、相機請求或自動重試。
- 2026-10-21 02:57:41 UTC，凍結基準八例精確得到四個預期 assertion RED／四個 controls PASS；候選 **139／139 PASS**。完整 Android host CI 在本文件封存時仍待本批正常 dev 交付後執行。
- Release Assessment：patch。未改版本、main、workflow、安全設定或發布。

## 可達的原始問題

`MediaDeleteSelection` 在開啟確認時保留原 ID、名稱、來源、owner/generation 及選取 token。
`MediaDeleteConfirmation` 顯示該名稱，但 `MediaScreen` 的 dialog 只依 session owner 退場。
同 session 的 `contents` event 或既有清單更新，可以接受同 ID、不同名稱的目前項目，而不更換 session generation。

原 `CameraViewModel.deleteMediaBatch` 只依 ID 找目前項目；兩次 preflight 驗證 owner、source、已載入、能力、保護及可寫性，未比對已確認名稱。
因此檢閱顯示 `REVIEWED.JPG`，實際 action 卻可取得目前的 `REPLACEMENT.JPG`。
既有 backend 使用 ID/handle 送出刪除，不會補查或比對檢閱名稱。

這是**顯示名稱已過期的確認仍可授權目前項目**，不是已證明刪錯實體相機檔案。
相同 ID／名稱不等於相同 bytes；本批沒有 content hash，也不證明任何特定韌體會重用 handle。
即使只是同一物件改名，使用者原本確認的顯示名稱也不能被靜默替換。

## 最小修正與保留契約

1. 所有原 eligibility veto 保持原順序。通過後，要求目前項目的名稱與該次 frozen request 完全相等，不做大小寫、空白或 Unicode 正規化。
2. 不一致在首次 preflight 為 `SKIPPED / ITEM_CHANGED`，沒有 backend delete；批次內其他原本已確認且仍合格的項目保留原流程。
3. 原 transport-boundary fence 再執行相同 preflight。若在這個邊界改變，結果為 `NOT_SENT`，保留確切 veto 原因並停止剩餘派送。
4. 英文與繁中明確提示名稱已變更、未送出刪除、需要重新檢閱。舊 confirmation 仍然 single-use，不能因刷新或改回原名而重播。
5. 不修改原 HTTP/PTP receipt 分類、redirect/retry、source 判定、session/recording 安全 gate、取消與 join、原 selection epoch 或 ACK retention。
   已派送後的未知結果仍為 `UNCERTAIN`；名稱變化不能把它改成未送出或已拒絕。
6. 同名的 rating/archive 等 metadata 更新不一概撤銷確認；已知 protected/read-only 仍由原資格規則禁止。

這不是在所有更新間提供原子相機快照，也不是相機端 compare-and-delete。
未觀察到的 bytes 置換、同名物件變化、派送後的外部修改仍超出此名稱見證。
既有 UI dialog 不因一般 metadata refresh 被整個關掉；失效名稱在真正派送前拒絕，結果可見，下一次必須重新明確確認。

## 凍結範圍與實際離線紅綠結果

新 `CameraMediaDeleteConfirmationTest` 八個方法使用完整 production ViewModel、repository、selection、batch coordinator 與受控 backend。
backend 只記錄合成派送與 ACK，不開網路、相機或刪除任何檔案。
前三個負例經真 `refreshMedia()` 接受清單後，再送原確認；固定相同 owner/generation。
第四個在 backend 邊界放入 production 清單 reducer 的合成 interleaving，驗證原 fence；不宣稱 event polling 在 DELETE busy 期間同時執行。

基準八例實際符合事前凍結的四個精確 assertion RED 與四個 controls PASS：

| 新案例 | 基準預期與實際結果 |
|---|---|
| acceptedReplacementCannotUseSingleConfirmation | `STALE_DELETE_SINGLE_NAME` |
| acceptedReplacementCannotUseBatchConfirmation | `STALE_DELETE_BATCH_NAME` |
| confirmedNameComparisonIsExact | `STALE_DELETE_EXACT_NAME` |
| changedNameAtDispatchGateSendsNothing | `STALE_DELETE_BOUNDARY_NAME` |
| unchangedNameWithNewMetadataStillUsesOneConfirmation | PASS |
| spentConfirmationCannotDispatchAgain | PASS |
| knownProtectionAndReadOnlyVetoStillSkip | PASS |
| lostAckStopsRemainderAndCannotReplay | PASS |

候選實際 139 個執行案例全部通過：原縮圖／狀態／preview 七類 108 項位元不變，加原 deletion 三類 23 項與新八項。
原 deletion shared-contract 七個方法仍讀取原 SHA256 固定 JSON，涵蓋原 540 vectors；不把 vectors 與 JUnit 方法混算。
編譯／fixture／resource 錯誤不能冒充上述 RED，非預期 assertion 不得調整白名單掩飾。

本批原公告窗口為 480 秒。第一次實際執行 133.931 秒：shared data 與 baseline UI／tests 兩次編譯均成功，但 Robolectric native runtime 解壓期間觸發共享磁碟 2 GiB 停止線，基準方法只進入而未完成，不能算 RED。保留原停止 receipt、編譯 log 與 partial JSON。清理僅限已確認無使用者的可重建 Swift module／compiled outputs；來源、證據、套件與目前編譯產物均保留。原絕對窗口不足，沒有自行重設或趕跑。

另經同批公告追加一次 240 秒完整 continuation。以 SHA 綁定前次成功的新編譯 data／baseline UI 產物及全部 inputs，並要求 2 GiB floor 之外，保留完整 native JAR 的 244,405,791 bytes 解壓上界、32 MiB stage 與 64 MiB concurrent reserve。第一次 continuation preflight 在 0.735 秒發現既有 partial JSON，尚未啟動 JVM；保存該檔與失敗 receipt，只修正輸出命名，沿用同一個 02:59:14 UTC 絕對截止點。

Continuation 從原起點實際 146.704 秒內完成，包括該 setup 停止、命名修正與封存；原 gate 加 continuation 的實際窗口累計 280.635 秒，不把原失敗改寫成通過，也不把等待時間當成另一次新預算。基準 8 例執行 7.20 秒；candidate 全 UI／test 編譯 58.10 秒；兩組 candidate 測試分別 18.05、8.37 秒。全部 exact method IDs、四個 RED markers、原 suite/source hashes 與 classpath origins 已核對。

兩 CPU、取樣 process-tree RSS 上限 768 MiB 不變；整批最高取樣 RSS 763,536 KiB，成功 continuation 最低取樣 free space 2,305,413,120 bytes。RSS 是監測停止線，不是 kernel hard cap。只使用既有 JDK/Kotlin/Robolectric/API35 材料，沒有下載或安裝。
77 個 shared production source 與各 variant 的 43 個完整 UI source 分別重新編譯；baseline 不覆蓋 candidate ABI。
另使用原 `CameraComponents.kt` 最後一個 top-level error helper 的逐位元切片，並非完整 Compose screen 編譯。
實際 closure、method IDs、baseline blob、所有原測試與產物 SHA 另封存；不得用舊 jar 當這次新編譯。

本機 gate 不編譯新的 Compose label/resource 連結，只靜態核對兩語資源與 mapping；完整 Android host CI 才補這個 SDK/resource 編譯範圍。
不表示原生 dialog 操作、SAF、GPU、USB 或實體相機已驗收。

## 交付與仍未涵蓋

只有 Android 原碼／測試／兩語資源與本文件七個路徑。正常 dev push 在沒有 open head:dev PR 的前提下，只匹配既有 Android host workflow，約 10–20 分鐘加排隊，原 45 分鐘 job 上限不變。
發布前重新核對 dev SHA、沒有 open head:dev PR、path fanout 與正常 hooks；若有新的 PR 或額外 trigger，不繞過檢查。
沒有 Apple／Desktop／Windows／Android emulator／KVM，也不修改 workflow。

先前縮圖 identity/LRU 的已完成證據保留；本批沒有重開其交付。
原生 color、Apple picker crash、未支持的 Apple wire／SDK／upload 契約及各真機驗收仍是獨立範圍。
