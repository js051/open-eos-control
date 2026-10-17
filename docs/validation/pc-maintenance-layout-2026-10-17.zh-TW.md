# PC 維護側欄閱讀修正

## 範圍與原因

既有 Firefox 維護恢復畫面顯示，維護紀錄與感光元件清潔說明受到同列按鈕擠壓，英文每幾個字就換行。維護專用 modifier 將時鐘、清潔、休眠及歷史紀錄改為單欄，說明使用整列寬度；按鈕在下一列自然換行，長標籤允許折行。其他設定保持原有排版。補齊既有 cancel key 的英文與繁體中文文字。

不變更維護 handler、恢復許可、後端、Android 或 iOS，不新增功能。

## 驗證

- 先新增結構與樣式測試，原始版因缺少 maintenance-command 而失敗；修正後通過。
- cancel 語系斷言先失敗，再補上 Cancel / 取消後通過。
- maintenance-lifecycle：136 / 136。
- maintenance-lifecycle-owner：21 / 21。
- liveview-stop-ownership：85 / 85。
- 完整 `npm --prefix bridge run test:modules` 通過（exit 0）。
- 實際 Firefox 修正後視覺與互動驗收由整合工作另行執行；上述測試不代表瀏覽器或實體相機驗收。

## Release Assessment

- Baseline：本機整合提交 56d891ae。
- Impact：patch；修正既有 PC 維護資訊可讀性與取消按鈕顯示。
- 尚未推送、合併或發布；修正後原生瀏覽器驗收待補。
- 實體相機驗證仍未執行，不作相容性承諾。
