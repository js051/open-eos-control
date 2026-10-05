# Android REC 停止後有界影片查找

基底：`56e45ba709a2b97169b7418272819ba90cdd8175`。本批修復 Android 已有 REC／最近素材旅程；證據是 production `CameraViewModel → CameraRepository → CcapiCameraBackend → CcapiClient` 與獨立 HTTP peer，不是實體相機驗證。

## 修復契約

- 在本機操作方向明確為停止，且方法正常回傳 `recording == false`、必要的狀態協調也回傳 `false` 後，建立最近影片查找。`true`、`null`、Start、被拒絕的命令與取消都不建立這次 Stop 的查找。操作方向只決定一次；狀態事件不會重播 Start／Stop。
- 查找沿用既有 8 候選、初次查詢及 250／750／1,500 ms 三次重查。停止錄影的候選使用既有 `isVideo` 判斷，較早出現的新 JPG 不會提前結束影片查找；still 保留 ALL 素材種類。兩者均先排除已知 ID，再按既有順序選出候選，避免相機時間較早的新可見 ID 被已知舊檔遮住；時間不代表本次指令的檔案因果。
- attempt 保存操作前已知 ID 的快照：已載入相簿、目前最近素材，以及最近一次既有 review listing 的至多 8 個候選。後者避免「未開相簿、畫面只顯示 JPG、但連線時已讀過歷史 MP4」被誤判為新影片。不為此新增 baseline／全卡／metadata 查詢。
- 這只是有界已知集合，不是全卡快照。未知的歷史影片仍可能後來才出現；找到新可見影片不能確定其由本次錄影產生，英文與繁中說明均保留這項限制。
- 耗盡後保持 `NOT_READY` 與明確的只讀重查。重查沿用原快照，不重播錄影／快門命令；重複點擊不建立並行查詢。MEDIA 忙碌時手動重查不開始，自動查找在下一個 listing 前用可取消的 `StateFlow.first` 等待既有 MEDIA 作業結束，不以定期輪詢消耗查詢預算。
- cache 更新要求相同 session、connection reference 及 review generation；cache 自身也綁定 session／connection。一般 attempt 取消或被拒絕的 still 不清掉已知候選，disconnect／session replacement 清空候選 cache／attempt，晚到的舊查詢不能更改新 session 或新 attempt 的排除集合與顯示結果。

## 分階段因果證據

原始 stdout、exit、XML、階段 patch／test source 保留於本地 `.codex/recording-review-causal/`，各階段不互相覆蓋。

1. 未改 production 的 `baseline-*`：REC Start／Stop 各一次，Stop 正常回傳非錄影，event 永遠空，媒體 peer 預定在第三次 Stop 後 listing 提供 MP4；斷言得到 **listing delta=0**。這證明 Stop 沒有啟動查找。10 個案例共 5 pass／5 fail，不能宣稱五個獨立產品缺陷：四個失敗依賴缺少 Stop review；另一個早到 event fixture 錯誤期待命令尚忙時立即 listing，後續已修正為等待 event 被 poll 消耗、ACK 後再同步。Start 回報 false、Stop 回報 true、Stop 拒絕、status 503、晚 Stop 取消等五個控制通過。
2. 只加 Stop review、仍排除單一 previous ID 的 `stop-only-*`：無 event／延遲 MP4 案例通過；兩個事先已知舊 ID 交換排序，早到 contents 在 ACK 後同步的案例仍失敗，查找沒有保留 `NOT_READY`。編譯時的一次 nullable Boolean 錯誤另存 `stop-only-compile-error-*`，不算產品紅測。
3. 已知 ID 集合修復、尚未限定影片的 `known-ids-*`：舊 ID 重排序通過；先出現新 JPG、稍後才出現 MP4 的獨立 HTTP 反例失敗，實際錯選 `NEW.JPG` 且 **listing delta=1**。再加入影片候選與有界候選 ID cache。
4. 中間 `pre-review-*` 回歸 96 例有 95 pass；唯一失敗是新增 rejected-Start fixture 將 native 初始 unknown 誤預期為 false，已改成核對原值未變，沒有據此更改產品狀態。
5. `review-boundaries-*` 兩個獨立 HTTP 反例均紅：拒絕 still 取消 attempt 時遺失 cache，後續 Stop 錯選 `HISTORY.MP4`／`IDLE`；已知舊 MP4 時間較新時，新可見但時間較早的 MP4 被遮住，仍為 `NOT_READY`／`OLD.JPG`。對應修復為 session 綁定 cache 與先排除已知 ID 再排序。
6. 最終 focused JVM **98/98 通過，0 failure／error／skip**，Gradle exit 0、1m39s。`focused-gradle.log`、`focused-exit.txt` 與 `focused-tests/` 七份原始 XML 對應同一輪來源；五個本批 production／test／resource 檔案的 SHA-256 在執行前後一致。未在此輪執行 aggregate、Lint、APK／AndroidTest 編譯或裝置測試。

focused suites：`CameraRecordingReviewRecoveryTest` 19、`CameraCaptureReviewRecoveryTest` 13、`CameraCapturePreviewListingTest` 8、`CameraEventStateRecoveryTest` 5、`CameraViewModelPreviewTest` 14、`MediaLibraryTest` 25、`CcapiControlRecoveryTest` 14。

19 個新 HTTP 案例覆蓋：空 event 下延遲 MP4、耗盡／重複只讀重查、已知舊 ID 重排與早到 event、先 JPG 後 MP4、只有 JPG 的 event 不解除影片待查、相簿未開時的歷史 MP4、拒絕 still 後保留候選、較早 timestamp 的新可見影片、Start／Stop 拒絕與不同狀態結果、未 typed 的 status 失敗、手動／自動 MEDIA 互斥、同 session 晚 retry、disconnect 晚 listing／晚 Stop。這是本機 `implemented` 證據，不是 PR ready／main accepted／preview released。

## 範圍與尚未閉合的情況

原有 Stop ACK 之後 status readback 失敗，目前沒有 recording 專用 typed acknowledgement。此批保持其既有錯誤／狀態語意，不捕捉一般 exception 當成 ACK，不猜測物理錄影已停止，也不在這種不明結果後開始本批查找。這個情況仍是後續獨立設計範圍。

未改 CCAPI endpoint、REC／still 命令、正常控制期限、檔案下載完整性或相機能力判定。8 是候選數，不是 HTTP 請求總上限。新的 HTTP fixture 使用合成媒體清單；真正有效影片播放與 MediaStore 保存由獨立裝置旅程驗證，不因 JVM 清單成功而推定通過。

Release Assessment：已發布基準為 `v0.11.0` Development Preview；本批為修復 REC 停止後既有最近素材流程的 `patch`。本頁先記錄本地 focused 驗收；未改版本、合併或發版。完整 aggregate、精確 head CI、API 34／36 裝置執行與物理 EOS／手機驗證各自獨立，未完成者不冒稱通過。
