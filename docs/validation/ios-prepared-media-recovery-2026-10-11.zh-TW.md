# iOS 批次準備紀錄與重啟恢復

## 原 P2 範圍

沿 `product-workflow-acceptance.zh-TW.md` 的「重啟後知道完成什麼」補齊 iOS 已開始批次項目的本機紀錄與仍存在自有副本的重新分享。既有批次只用記憶體與 temporaryDirectory 租約；這批不恢復相機連線、不保存相機 wire ID／URL／token，不推測重連到同一台相機，也不自動重試下載。

- 最多100筆已開始嘗試，副本保留上限512 MiB。滿額拒絕新的保留／紀錄並提示，不默默淘汰唯一原檔。這不是全部未開始選取項目的恢復佇列，也不是既有transport暫存的總硬上限。
- 完整下載先沿原檢查成為本次 App 內ready；額外保留使用有界chunk copy，不移走／刪除原暫存。記錄、保留、容量或磁碟失敗不把有效下載改成failed，本次仍可立即分享，但重啟副本可能不可用。
- 精簡manifest只保存隨機receipt／batch UUID、清理後名稱、時間、固定狀態／原因、實際bytes，以及自有副本／目錄的本機檔案身分與修改時間（不是相機識別）。冷啟動started顯示結果未確認；prepared是歷史事實，當前副本另驗一般檔案、長度與安全路徑，不能以缺檔／變更副本繼續開放分享。
- App專屬Application Support root、版本化有界manifest與原子替換；UUID目錄、descriptor相對操作、不跟隨symlink。copy完成而manifest尚未提交的孤兒不得收養成ready，仍計入容量。未知schema／不明目錄／損壞紀錄保留並拒絕不安全操作，不清空重建。
- 分享前重新驗證來源並取得強租約。明確確認「移除紀錄與 App 內副本」後先更新世代；舊ticket不得重建。已呈現的分享來源仍由租約保留，釋放後才清理，失敗保留tombstone與警告。本次仍有效的暫存原檔及外部目的地不屬此清除。
- 離線Connection與素材批次區共用紀錄頁，英／繁中、可查看歷史與個別／全部可用副本分享；原生activity完成回覆不記成外部Files／Photos已保存。重新開啟、refresh及分享不發相機請求。

## 驗證狀態

此批來源實作與獨立審查已完成；本機無Swift／Xcode，編譯、XCTest、Simulator及實機runtime皆 **NOT_RUN**，不沿用前一head的CI。另有三個不合作取消的回覆延遲控制：started receipt後取消不發GET、retained回覆晚到不覆蓋新session/batch、舊snapshot不覆蓋新refresh/clear；這是store回覆發布邊界，非真磁碟掛起。沿原canonical兩原檔UI旅程新增英/繁中真正terminate→relaunch→離線2份副本可用的來源控制，尚未runtime。必要測試來源包括冷啟動狀態、損壞schema／缺檔／長度變更／symlink、容量與partial copy、原子提交故障、clear世代／分享租約、production CCAPI／Bridge下載與紀錄故障隔離。store共27個受控測試方法、CameraAppState另6個production-client／reply-owner方法；本地38個雙語key／format parity與git diff --check通過。獨立review攔下的同長度替換／cleanup刪錯檔、FIFO blocking open、deferred cleanup繞過manifest guard三項已補捕捉檔案身分、nonblocking leaf open、先驗manifest及負例，重審無剩餘blocking。這是來源審查而非runtime通過。完整批次後沿原PR236正常CI驗證；完整App人工與真相機／真provider驗收依使用者指示集中。

已存在058d7af的導航修正正常CI38101232791全部適用成功（App248/UI27全過），只驗該舊head，不是本批恢復能力的證據。PC前景JPEG候選與其publication阻擋分開記錄，不混入iOS範圍。

## 首次正常 CI 與後續修正

Exact head `211894224ba79560005d6280c68e7e7e529615ee` 的正常 CI `38103181314`，iOS App production build完成，但App tests編譯在 `MediaDeliveryStoreTests.swift:396` 失敗：Foundation `setAttributes` 第二參數須 `ofItemAtPath:`，來源誤寫 `atPath:`。保留 job `114363199964` 的01:54:56原始編譯失敗；App／UI runtime尚未開始，不說新增測試通過。後續只修正參數標籤，不改原替換／時間戳負例或assertions。

同批來源補上App factory無法建立store（nil）時的批次警告；原有效temp原檔仍ready可分享，既有failure-isolation控制延伸nil與unwritable兩種。這是持久副本不可用的提示，沒有將下載標失敗。原run剩餘gates終態後才整批更新一次，完整App人工／實機仍後置。

## 第二輪執行證據與導航候選

Exact head `9f0202bfcb645f32254e4886111ccde05098e499` 的正常 CI `38104167802` 已終態 FAILURE。Core 277/277、App 281/281、JVM/APK及secret通過；新增27個store與6個整合方法已實際執行。既有canonical兩原檔旅程擴充的英／繁中 terminate→relaunch→離線兩份副本控制於02:17:01 UTC通過。這些結果不等同外部分享provider或實機驗收。

UI 27中26通過，唯一失敗是 `testOfflineMediaDateRangeApplyCancelAndClearInBothLanguages:546`：套用已知資料夾後，8秒內未讓 `media-actions-preview-001` 可互動。原artifact `11690075418` 為207767217 bytes，SHA256 `1ee278456a5e328e0481ce8570a9bdc8155f014f43db63d0b4f05cbcbf0253a6`，已核完整bytes。四次搜尋拖曳後才取得目標，最後仍不可互動；沒有盲目重跑。

XCResult的真實 `XCSynthesizedEventRecord` 顯示最後手勢screen端點為(201,804.67)→(201,250)，位於y180.67至874的ScrollView內。log的(201,624)→(201,69.3)是相對surface原點offset，不能據此宣稱越界或missing-origin bug。每次554.67pt移動使用500pt/s、movement約1.11秒，另hold0.1秒及AX/settling成本；8秒只完成四次lazy-cell搜尋。此test沒有保存最終target frame或失敗截圖，只有此前清除日期的截圖，因此搜尋預算是待驗候選，不是已確證唯一根因。

後續最小fixture候選集中generic helper的幾何與搜尋契約：保留原caller timeout、0.8 viewport重疊、停止後hold及全部業務assertions，較快的受支援drag速度僅用於一般導航；camera actions原速度不變。抽出絕對座標／surface offset轉換並補非零原點、截短viewport的純幾何控制，失敗時保存有界target/viewport與actual screenPoint及截圖供判讀。這不是放寬互動判準，也不改production版面追逐測試。此後續候選Swift/runtime仍NOT_RUN，必須以新head正常CI判定。

## 限制與 Release Assessment

第三輪 `39d2de7`／CI `38106141493` 仍 FAILURE：App 281/281、UI 27/28，唯一失敗改為 rating Form 的五星 row。新增失敗截圖與 target frame 已保存，揭露 generic helper 僅選 ScrollView／整個 App fallback 未排除 sheet chrome 的問題。具體圖像證據、原 artifact hash 與同批容器候選見[前景準備批次中的導航收斂紀錄](ios-foreground-original-preparation-2026-10-11.zh-TW.md#同批原-ui-失敗收斂)。沒有刪除原失敗或放寬 timeout／業務 assertions。

副本長度／本機檔案身分核對不等同來源hash、可解碼或硬體fsync；備份還原或外部替換可能改變檔案身分，此時保守停用分享並保留資料。原子rename不承諾斷電耐久。只有單一App store actor管理本機紀錄，不承諾跨App／多程序並行編輯。磁碟被外部修改、未知目錄或損壞manifest會保留資料且拒絕不安全自動清理。保留副本會額外使用儲存空間；512 MiB不包含既有相機下載暫存，亦不保證OS永久保留資料。

Latest release v0.12.0 Development Preview。本批是向後相容的iOS可見新能力，impact minor；保持原PR236草稿、必要gates、release HOLD。不main合併、不發版、不改KVM，不含私有消費端資料，不宣稱原P2或完整產品已驗收。
