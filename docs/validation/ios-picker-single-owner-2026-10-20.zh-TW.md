# iOS 選檔器單一呈現擁有者候選驗證（2026-10-20）

## 狀態與不宣稱的事項

本批是可被證偽的生命週期簡化候選，不是已證明的 DocumentManager 崩潰修復。
移除額外的 SwiftUI upload sheet，讓相簿頁面保有一個 UIKit Presenter；每次開啟仍建立真正的
`UIDocumentPickerViewController` 與不可變的選檔 ticket / Coordinator。
不改上傳收據、不放寬結果或互動斷言、不加入延遲、重試、私有 API 或全域保留器。

- 來源基準：`c4552f88cb6bdcc72c703c1916e282df8dc946a0`。
- 原生失敗：Apple run [39472117013](https://github.com/js051/open-eos-control/actions/runs/39472117013)，
  SHA `28464dded03de02fe713f2308a06b060187924e2`；上述來源基準未修改 Apple 輸入。
- 原始結果：App 841 tests / 0 failures；UI 44 tests / 1 failure。
- 失敗案例：`testRealDocumentPickerCancellationAndReopeningAfterReconnectInBothLanguages`，
  繁體中文第一次 Cancel 後，尚未重新連線即崩潰。六個選檔／取消上傳／lost-ACK 情境另案通過。
- 原始 runtime：Xcode 16.4、iOS Simulator 18.5、iPhone 16 Pro。
- 本批來源／離線／Apple 原生結果依下方記錄；在實際執行前一律為 pending。

## 已知證據與未知原因

18:40:36.1966 UTC 的 `.ips` 確認 EXC_CRASH / SIGABRT，例外鏈經過
`DOCWeakProxy forwardingTargetForSelector:` 與 NSXPC message decode。
這與早先 run 39416734400 的真正選檔成功情境具有相同 framework signature，
但本次檔案沒有例外 reason 或 selector，不能把前次 selector 當成本次已知事實。
限定大小的 trace 檢查未找到前次 reason；較大／超過解壓上限的 object 未展開，故不是完整不存在證明。
原始 `.ips` 與 artifact 留作私人驗收證據，不把裝置識別資料放入 repo。

失敗 journal 只剩該次 opening 的尾端；後續情境已使前段超出原有 128 筆／16 KiB 滾動上限。
其中 dismantle 確實讀取 UIKit flags；retirement-no-modal 只證明 presentation predicate 為 false，
其他欄位是預設值。Presenter／Coordinator deinit 的 flags 也是預設值。
沒有真正 picker deinit 的觀察，也不知道 weak proxy 指向哪個物件。
六個後續成功情境使用同樣的 no-modal 及 owner-release 路徑，所以不能據此宣布提早釋放為原因。

## 生命週期契約

1. 相簿背景的 representable 保有 Presenter；`selection=nil` 不移除它。
2. 每次 Session 固定自己的 selection、picker、Coordinator 與 callback；畫面更新不能重新指向新 ticket。
3. UIKit presentation completion 持有該 host/session；若此時要求移除，先等待這個公開回呼，不能同時 dismiss。
4. retirement 使用既有 modal 關係／transition／dismiss completion 邊界。取消或未註冊的 transition 仍需實際 dismissal。
5. retirement 結束前不能呈現 replacement。結束後釋放 active Session，再交付該次結果；Presenter 留在相簿。
6. gallery removal／selection replacement 先 invalidates 舊 Coordinator，舊結果不能取得安全作用域或影響新 owner。
7. 同一 consumed ticket、重複 delegate／presentation／retirement callback 都不能重新呈現或交付。
8. 仍保留 `.overFullScreen`、真正 picker 的 accessibility identifier、fixture directory hint、單選、既有 grant acquisition/release 與 reconnect admission。

[Apple 的 dismiss completion 契約](https://developer.apple.com/documentation/uikit/uiviewcontroller/dismiss(animated:completion:))
是 view-controller 生命週期邊界，不承諾 DocumentManager 的遠端 XPC 已排空。
因此 no-modal 邊界仍同步完成，沒有假造一個 quiescence 等待期。
外部 teardown 時只由已註冊的公開 completion 延續擁有權；沒有固定秒數或永久 holder。

## 限定診斷

原 aggregate journal 的格式與 128 筆／16 KiB 上限保留。
前 16 個 opening 另寫固定 allowlist slot，各自至多 128 筆／16 KiB，後續 opening 不能覆寫前面的 slot。
第 17 個以後僅保留 aggregate；單一 opening 自身超過上限仍可能截斷，不能宣稱完整歷史。
16 個 slot 合計至多 256 KiB；含 aggregate 至多 272 KiB。

- 僅 DEBUG + OEC_REQUIRE_SIMULATOR_E2E 且明確 synthetic fixture 啟用；一般版本不讀寫 journal。
- 僅 synthetic ordinal、有限列舉、相對時間與布林；禁止 URL、檔名、owner UUID、任意 label 或 log。
- 固定 slot 必須與每筆 ticket 相同；無目錄列舉；拒絕 symlink、非 regular file、過大資料與非封閉 schema。
- exporter 的 64-file／24 MiB artifact 上限不變；缺少 optional slot 不製造警告，也不改任何測試結果。
- `session-retired` 是應用程式的 retirement receipt，不是 picker deinit。
- `presenter-retained flag=1` 僅表示 host 尚未 dismantle，不是 UIKit snapshot、視窗 attachment 或 XPC 保證。

## 驗證計畫與目前結果

原有 host source guard 的 empty update／無 completion present 字串依賴舊架構；
本批僅更新這兩個 shape assertions，加入不可變 opening、捕捉 callback 與公開 completion 擁有權檢查。
其他 grant／receipt／retirement assertions 不變，舊檔保留供離線 expected-shape-RED 比對；
這種 RED 不是原生崩潰的重現。

來源／離線 gate：PASS（2026-10-20 19:26 UTC）。僅使用既有工具，串行、2 CPU，
完整 candidate gate 4.42 秒；sampled process-tree peak 155,876 KiB（含 gate，shared pages 按 process 重複計入）。
保留原五分鐘 deadline；結束時 free disk 2,238,484,480 bytes，未降低 2 GiB floor。
離線涵蓋實際來源的 retirement／ownership 決策（UIKit 邊界以 held receipts 代入）、journal 純 Swift 測試、
Python exporter／privacy／caps tests、Swift syntax、來源完整性與 secret hooks。
替代 UIKit 型別只供 Linux 決策測試，不是 Apple SDK typecheck 或 provider/XPC 驗證。

- 61 focused Python tests、125 CI helper tests、22 Swift tests（12 ownership + 10 journal）全部通過；
  Python 組別部分重疊，不相加當成唯一測試數。
- 原 full Ruff command、ordinary／fixture Swift parse、fixture journal typecheck 通過。
- 原 29 個 ownership 與 5 個 journal test methods 全部 byte-identical；Retirement、Coordinator、
  selection equality、file access 與 CameraAppState 亦與基準相同。
- 第一個 gate 在任何測試前因磁碟 working headroom 不足停止；保留該 setup receipt，
  僅清除已完成、無 process reference 的兩個可重建 Swift compiler out 目錄，保留其他檔案雜湊。
- 首次 focused run 找到新增 Python test 的 assertLessThanOrEqual 名稱錯誤；
  修正為 unittest.assertLessEqual，原上限斷言不變。保留 error 與其造成的下一個 subcase cleanup 失敗。
- 舊 source-shape guard 對 candidate 如預期失敗；只有兩個舊 shape 被替換，其他方法 AST 相同。
- Linux Swift 6.4.1 提示 weak var 可改 let，以及 harness 的多餘 try；非錯誤。
  不為消除較新 compiler 警告引入 Apple 舊 language mode 未驗證的語法。

新增 App 測試保留既有測試，另驗證：
- 同一 Presenter 經過選檔與 Cancel 後仍可重開。
- replacement 等待舊 presentation 及 retirement，沒有 modal overlap。
- 外部移除後擁有權保留至公開 completion，隨後能釋放。
- 移除／replacement 拒絕 held 選檔結果與舊 callback。
- 尚未可呈現時只採用最新 opening，且不重新導向 callback。
- consumed ticket 與重複 completion 不能重開／誤退場新 picker。
- no-modal 仍同步、單次完成。

完整 `OpenEOSControlUITests.swift` 保持 byte-identical：
SHA-256 `05a60bbd2bfc3064451fa17f094a2435d72b541cf10a41989740b8609487af37`。
原有雙語 Cancel／reopen／reconnect、六個 selection 情境、實際 provider、安全作用域、
stale owner 與 uncertainty／verified 邊界都保留。

Apple 原生 gate：pending。僅一次既有完整 Apple dev workflow，預估 40–50 分鐘加排隊；
原 45 分鐘 App/UI step 與 55 分鐘 job timeout 不變。不得自動重跑。
這是 supplemental dev validation，不等同 PR `ci-complete` 或 physical-camera 驗收。
若 runtime 與基準不同，必須另列 comparison limitation。
零尺寸背景 Host 的實際 window／viewDidAppear，以及 overFullScreen 不觸發 gallery onDisappear，
只能由此原生 gate 驗證。若其他 modal 暫時阻擋，只由合法的 update／viewDidAppear 再推進 pending opening，
不以 timer 或 polling 繞過呈現邊界。

## 可證偽與後續決策

- 若穩定 Presenter 仍在、未發生外層 sheet teardown 時重現同類崩潰，則「外層 sheet 破壞是必要條件」假設被反證。
- 移除 sheet 後仍崩潰，表示此候選不是充分修復，不追加猜測性的 retain／delay。
- 單次通過只能證明本次未重現及既有回歸斷言通過，不能證明根因、永久修復或真實相機可靠性。
- 若未取得完整失敗 opening 或 reason，明確保留 unknown，不用 source 推測補成證據。

本批九個檔案為 MediaUploadPicker.swift、MediaView.swift、MediaResponseOwnershipTests.swift、
PickerFixtureJournal.swift、PickerFixtureJournalTests.swift、export_ios_ui_evidence.py、
test_picker_journal_evidence.py、test_ios_media_upload_source.py 與本文件。
發布僅限原 dev 的這九個檔案；push 前須 fresh head／無 open head:dev PR、正常 hooks、非 force。
預期只觸發 Apple dev（含 secret scan/Core/App/UI），不改 workflow，不觸發 Android/KVM 或其他平台。
任何新增 PR／意外 fanout 都先停止並重新評估，不能關掉必要 checks。
