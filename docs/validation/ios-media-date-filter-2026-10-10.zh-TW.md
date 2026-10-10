# iOS 已載入素材日期區間挑片

## 來源與開發範圍

基底 accepted main `089e71a3fb3249164d36173b04b682c5612d3966`，tree
`bbd9e51483e564c7234ab60b6b85035f74737637`。接續原產品計畫 P2 日期挑片：Android、PC 已有各自批次，
本批補 iOS 的已載入素材日期區間，不代表其他平台驗收可以替代本批。

- 原生日期選擇 sheet，開始與結束日期都包含；同一天合法，反向區間不能套用。
- 開啟時捕捉時區，使用 Gregorian calendar 的完整日區間；結束以次日邊界排除，不能假定每天固定24小時。
- 只使用既有 captureTime。既有 ISO8601／Canon本地時間解析維持原行為，不增加 metadata、listing 或其他網路 fan-out。
  無時區的Canon字串仍按既有static DateFormatter首次建立時的 `.current` 解讀；日期範圍另捕捉開啟sheet時的時區。
  手機之後換時區不會回溯重新解讀原parser，也不會改寫已套用範圍；這是保留的既有時間語意，不宣稱跨時區重新推定相機拍攝地。
- 日期與既有照片／影片類型及排序交集；未知／無法解析日期僅在日期篩選啟用時排除。
  RAW+JPEG 仍是不同 ID，不合併或捏造拍攝因果。
- 顯示範圍、時區、已載入總數、符合數及所選類型的未知日期數。Recent／不完整清單明示只篩選已載入內容。
- 取消保留已套用條件；清除只移除日期限制。session退休清除範圍；舊sheet的套用／清除不能改動新session範圍。
- 不改原素材陣列、已選預覽或下載工作。現有 iOS 沒有多選契約，本批不新增多選能力。
- 日期與素材工具列納入同一個可捲動畫面，避免大字級時固定工具列把素材區推到畫面外；視覺／裝置證據仍待執行。

## 測試來源與目前證據

- MediaLibraryTests 新增11方法：包含兩端／次日排除、同日與leap day、反向/nonfinite、DST23/25小時、
  UTC/Taipei/Los Angeles、unknown/malformed、RAW+JPEG、排序交集、計數、空清單及既有本地時間格式。
- MediaDateFilterOwnershipTests 新增2方法：disconnect退休範圍，舊draft／clear不能改新owner。
- MediaResponseOwnershipTests 新增1方法、CCAPI/Bridge兩情境：先實際選取非nil預覽，套用會排除該項的日期區間，再清除；
  原選取、預覽bytes與素材陣列仍相同，沒有額外listing/display請求。合成peer不連網，不能宣稱真相機或實際畫面解碼證據。
- 一個雙語、大字級 UI 測試來源覆蓋開啟、取消、套用、再次取消及清除恢復原素材入口；使用原互動可達性helper。
- 本機 Swift 工具不可用；編譯、單元runtime、UI runtime與visual review均 **NOT_RUN**。
  whitespace及localization來源檢查只證明各自範圍，不替代Swift編譯。
- 正常 workflow/classifier 未變；本批僅ios/docs路徑，預期適用ios_app，不觸發Android KVM jobs。
  實際 exact-head CI 結果另記；不把推測classifier當run成功。
- 依最新節奏，完整 App 人工／端到端驗收集中在既定開發批次之後。必要原CI仍保留。

## Release Assessment

Latest released baseline：v0.12.0 Development Preview。這是新使用者可見的 iOS 日期挑片能力，屬 minor 類影響；
不因此自行改版號或發布。來源0.13.0尚未發布，既有release HOLD保留。
本批目前未編譯、未完成exact-head CI、未實機驗證，不能標記main accepted或完整產品完成。


## 同批補齊評分挑片（待新版 CI）

日期 head `d6bc46c05bf2412fdfd55c3c2aae132a2a375a33` 的 CI 38084521728 已成功：209 App／21 UI，包含雙語大字級日期套用／取消／清除，另有 JVM／APK 及 secret scan。這些結果只驗證該舊 head，不能沿用為下面新增評分來源的執行結果。

同一 iOS 挑片批次新增評分條件：全部、已知 0 未評分、至少 1–4 星、5 星及未知。資料模型 Int? 中的 nil／範圍外數值視為未知；不以 0 取代。高至低／低至高均把未知排最後，同分維持載入順序，與日期／種類取交集。評分排序不顯示日期分组標題。

評分 sheet 使用英文／繁中、可捲動選項、取消／套用／清除；草稿綁定 media library session 身分，斷線清除已套用條件並退休舊草稿。套用／清除不改載入項目或既有預覽資料，也不逐檔查詢 metadata。日期摘要 matched 反映評分交集，unknown 日期只計所選種類／評分；評分摘要 unknown 則計所選種類／日期範圍，避免先被 unknown 篩選自身消去。所有分母仍是已載入清單，並非全卡。

新 helper 測試、session 正向／舊 owner 反例及 CCAPI／Bridge 真選取保留與請求計數測試已寫。既有雙語大字級 UI 旅程延伸評分取消、5 星套用、清除及原素材可達性。Swift 本機不可用，新版編譯／runtime NOT_RUN，待此完整批次正常 CI；來源檢查不代表視覺或實機通過。完整 App 驗收仍集中於開發之後，PR 不合併 main、不發版。
