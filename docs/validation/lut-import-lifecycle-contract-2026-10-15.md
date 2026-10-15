# LUT 匯入、取消與移除生命週期契約（2026-10-15）

## 範圍與身分

沿既有 `.cube` 匯入功能對齊選檔、讀取、嚴格文字解碼、解析、套用、取消與移除。不要新增 LUT 格式、相機／transport 功能、持久化濾鏡庫或改寫原件；LUT 仍只作用於監看畫面。

有效選檔必須在進入非同步工作前同步建立 import owner。該身分貫穿讀取、解碼、解析、renderer 準備及結果發布；較舊的成功、錯誤、finally 或回呼不得改變較新的 LUT、loading、錯誤、提示或 render revision。renderer 與同影格監看沿既有接線，不重做先前像素契約。

選檔器另有自己的 ticket。僅打開選檔器不是新的有效匯入；舊選檔器的晚回呼不得代入後來選檔器或 import 的身分。

## 共同使用者結果

- 新 LUT 成功讀取、解碼、解析並具備既有 renderer 必要條件前，保留目前已套用的 LUT。
- 當次匯入失敗保留目前 LUT，錯誤歸屬該 import；不清除、不蓋掉無關相機操作錯誤。選檔／匯入錯誤不能被一般逐幀監看刷新悄悄消失。
- 明確「取消匯入」撤銷在先 picker、讀取與解析，保留已套 LUT；明確「移除 LUT」同時撤銷在先工作並清除已套 LUT。
- 選檔器的 null／使用者取消只結束該選檔器，不報匯入錯誤，不清除 LUT，也不取消此前已接受的有效匯入。
- 當前選檔器可觀察到的真正失敗，保留 iOS 既有的退休在先 pending import 行為並保留已套 LUT；immutable ticket／generation 仍須保護後來已接受的選擇。沒有該錯誤回呼的系統，不捏造可觀察能力。
- 監看面板 Done／關閉、換相機或 disconnect 不取消最近有效匯入，不清除 LUT。它屬於 app／page lifetime，不屬於相機 session。
- 外部 Files picker 造成的 scene inactive／background 不能被誤認為取消選檔。整個 app owner／page teardown 才撤銷其 pending 工作；不能在 owner 已退休後發布。
- 取消、失敗、舊回呼或無實際值變化的移除，不冒充成功換 LUT 或虛增呈現進度。其他監看設定保留。

## 有界讀取與文字規則

原始位元組最多 16 MiB，包含檔首 BOM；檔案 metadata 只能作預檢，不取代實際讀取上限。未知或變動大小也須有界；不可先全讀任意大小再計數。可多讀至上限外一個位元組來證明超限，但不繼續取完整其餘內容。

同時最多一個實際讀取／解析管線，加一個最新待處理選擇。較新選擇取代等待者；舊實際 I/O 未退場前不啟動無界並行讀取。取消須撤銷結果發布權並在可用邊界合作停止。原生 provider／檔案 API 阻塞時，不能把取消旗標當作實際已關閉；顯示等待／停止中並保留實體清理責任。

以 strict UTF-8 解碼，非法序列即使位於註解或 TITLE 也拒絕，不以替代字元掩蓋。單一檔首 UTF-8 BOM 可消除，重複檔首 BOM 拒絕；不猜測或轉換 UTF-16 等其他編碼。其餘合法 Unicode 名稱保留。解碼成功仍須通過既有 cube 大小、domain、finite value、列數及格式限制，不等於可套用成功。

共用 `contracts/lut-import/v1/utf8-vectors.json` 固定 16 組原始 byte 向量、normalized text／拒絕預期，以及 16 MiB／16 MiB+1 邊界。測試必須經 production decoder／reader；fixture 載入或常量轉譯的驗證層級應另記。

## 診斷隱私

保留既有 LUT 隱私界線：診斷不包含檔名、TITLE、路徑、URI、內容或可能夾帶上述資料的 provider／renderer 原始錯誤字串。可記錄 loaded／size、pending／retiring、是否失敗或固定安全分類；本機 UI 錯誤與可分享診斷分開。使用合成路徑 canary 驗證，不能靠真私人檔案測試。

## 最低完整旅程

- A→B 連選且完成順序反轉，A 的成功與失敗皆不能蓋 B。
- owner 已建立但 task 尚未開始、讀取中、解析中、發布前分別取消／移除，舊結果不能復活。
- 慢 provider 阻塞時連選 B／C，只有最新待處理者可接續，不能累積無界並行 I/O。
- picker null、真失敗、晚 callback、明確取消與移除分開驗；取消不是一般錯誤。
- 失敗保留目前 LUT 與無關相機錯誤；成功僅清當次匯入錯誤。
- Done／disconnect／外部 picker 生命周期保留範圍正確；whole owner teardown 不發布。
- raw-byte 大小、未知／變動 metadata、短讀／零進度、UTF-8 多位元跨讀塊、非法註解／TITLE、單／雙 BOM 等邊界。

## 證據限制

本地純邏輯、production-function、來源／接線檢查與 API 形狀替身各自列明。Swift／Android SDK、原生 Files／ContentResolver、真 browser File／picker、OS 取消與 renderer 行為，未實跑就保留待驗。共享 fixture、三端相同文件或已寫 native 測試，不代表平台執行與產品全部對齊。本群不變更原先固定待批 KVM 快照。
