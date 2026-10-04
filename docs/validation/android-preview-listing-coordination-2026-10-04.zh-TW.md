# Android 最近素材預覽與相簿列檔協調

## 已確認的失敗

PR #203 首輪 head `f5fd775e20a48a8b1e6e0e4127e7dc6bbb7c8302`，CI run `37222071895` 的 API 34／36 各跑 210 例、203 通過、7 失敗。其中五例都在 `CameraPreviewSaveJourneyTest.captureAndOpenPreview` 檢查 `mediaItems` 包含新 capture ID 時停止；保存、取消、完整性及重試斷言尚未執行。另兩例 Dialog 幾何失敗不屬本修正範圍。

精確因果是：

1. `openCaptureReview` 呼叫 `setUiMode(MEDIA)`，空相簿啟動 `refreshMedia`。
2. 同一呼叫隨即 `openMediaPreview`。原本它透過共用 `MEDIA` operation，同步取消剛啟動的 listing 並增加其 generation。
3. 已知素材可以直接取得 display bytes，但相簿仍為空／`CANCELLED`。預覽位置及相鄰導航依相簿項目計算，因此使用者旅程確實不完整。
4. 已有舊相簿時也有另一缺口：進 MEDIA 只對空列表 refresh；拍後 review 更新不會自動把新素材放入該舊列表。

原碼上以真 ViewModel／Repository／MockWebServer 和可控 Main dispatcher 執行的初始四例為 3 紅、1 綠：空相簿立即成為 `CANCELLED`；舊相簿未 refresh、維持 `COMPLETE`；預期抵達 listing gate 的案例根本沒有保住該讀取；非 preview MEDIA 取消 listing 的控制案例通過。成功契約隨後明確要求 display **不得等待整張卡列完**，沒有採用等待整個 listing 的修法。

## 最小修正與不變量

- 只有 display preview 使用不取消、也不 join listing 的唯讀路徑。ALL scope／慢卡的既知素材仍可先顯示；完成 display 後解除 MEDIA busy，關閉／保存不等全卡。
- 保存及其他 MEDIA operation 保留原先 cancel＋join，先結束 listing 再開始其相機／檔案工作。
- `openCaptureReview` 發現目前相簿沒有已知 capture ID 時補 refresh；若已有可能在拍攝前取得快照的 listing，先使舊讀取失效，重新查詢一次。
- 手動 listing 的 partial page 保留使用者已要求的 preview，不能因前幾頁尚無該 ID 就關閉 Dialog。最終 manual snapshot 也保留原有 preview 行為；RECENT 的有界結果缺少某 ID，不是全卡刪除證據。
- 完整 event 與明確 deletion caller 的既有收斂語意不變。Display 結果另核對原 session generation／connection reference 及目前選取 ID。
- 沒有自動重拍、重播寫入、額外相機 endpoint、權限、安裝、無界背景迴圈或相簿自動重試。

## 回歸案例

`CameraCapturePreviewListingTest` 的八個 JVM 案例執行真 ViewModel、Repository、CCAPI HTTP：

1. 第一次開拍後素材：listing 持續，display 先完成，listing 完成後可開下一張。
2. 已有舊的 ALL 相簿：重新列檔，但 server gate 不放行時 display 已完成且 MEDIA 不忙。
3. 非 preview MEDIA operation 仍取消正在列檔的工作。
4. 列檔期間 disconnect／reconnect 不使已關閉的 preview 在新 session 重開。
5. Close 之後 listing 完成不會重開 preview。
6. 明確取消 listing 保留已完成 preview，不自行重新列檔。
7. RECENT snapshot 暫缺使用者已知 ID，不會關閉該 preview。
8. Native ALL 的第一頁沒有目標、第二頁被 gate 阻擋時，已取得 display bytes 的 preview 不被 partial page 關閉；放行後相簿才加入該 ID。

JVM 的 display body 是明示的合成 bytes，只驗狀態／HTTP 協調，不冒稱 bitmap 呈現。地址在工作執行緒預先計算，沒有改 Android StrictMode。

`CameraPreviewSaveJourneyTest` 原五例仍保留 membership、Dialog 範圍、實際 bytes、取消清理、同名 ID 與重試斷言；另外明確要求最終 album 為 `COMPLETE`。新增第六例用真 Compose／MediaStore：ALL listing 的 HTTP peer 持續被 gate 阻擋，display 先完成；使用者從 Dialog 保存原檔，原有取消列檔政策讓保存實際 bytes 完成，之後才放行 listing peer。

## 驗證界線

- 本地只執行 JVM 與 instrumentation 編譯；沒有 emulator 或實體相機。
- 修後 API 34／36 必須實際重跑，才能確認原本被 membership 擋住的保存步驟及新增慢 listing 案例。
- 修正後精確 head 的 aggregate／CI 結果另記於 PR；本文件不把來源 head 的通過數當成修後證據，也不宣稱 PR ready、main accepted 或發行。
