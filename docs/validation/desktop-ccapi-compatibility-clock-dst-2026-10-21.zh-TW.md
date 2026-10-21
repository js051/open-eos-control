# Desktop CCAPI compatibility clock sync：保留 host DST 語義

## 範圍與原始反例

基底為 `f3708149825e7aab998717b032a4a1f7996e1963`。本批只修正仍保留的
`POST /v1/session/{id}/clock/sync` 與其 CCAPI backend。`CameraEngine` 仍有此方法，
Android／iOS Bridge core client 仍可呼叫；既有 API／client 測試也保留此合約。
目前 Android、iOS、PC 三個產品 UI 已使用 evidence-aware maintenance 路徑，
不受這個 compatibility DST 缺陷影響。

舊 compatibility 實作先取 `datetime.now().astimezone()`，再以回傳物件的 `dst()`
計算 flag。但該本地時刻帶的是 fixed offset，不帶 host 的 DST 規則。
例如合成 `2026-07-15T12:00:00-04:00`、host `tm_isdst=1`，舊程式送出的 `dst`
仍為 `false`。既有 fake transport 將寫入 payload 原樣回傳，因而能把錯誤要求的
echo 誤當成成功自洽。這是要求值的語義錯誤，不是實際相機時鐘損壞的證據。

## 最小修正與不變邊界

- 抽取既有 maintenance 的 `time.localtime(requested.timestamp()).tm_isdst > 0`
  為小型 private helper，兩條路徑共用同一個已取樣 instant 的 host DST 推導。
- 各 caller 原有 timestamp 取樣及 microsecond rounding 保持原樣。
- 同 API version 的 GET／PUT gate、session／Bulb admission、一次 PUT、原 request／
  error／readback 行為、包含端點的十秒容差與 observed-feature 更新位置不變。
- 不更改時區設定、環境 `TZ`、相機時間格式、HTTP route、重試規則或三端 UI；
  不添加系統 timezone framework、資料庫或依賴。
- compatibility 既有 readback 合約不因此變成新的 maintenance receipt 合約；
  讀回失敗不構成安全重播的授權，也不宣稱相機未套用原要求。

## 凍結的合成驗證計畫

新增十二個案例，使用真正 backend 與既有 in-process authenticated TestClient route，
只替換 host 時刻／DST 觀察與 fake camera transport，不改系統時區、不使用相機或 socket server。

1. backend／HTTP 兩入口各跑 winter、summer、fall-back-before、fall-back-after，共八例。
   baseline 預期只有兩入口的 summer／before 四個 `COMPAT_CLOCK_DST_MATCH_HOST`
   assertion 失敗，winter／after 四例通過。檢查完整 payload、RFC 1123 時刻與單次 PUT→GET。
2. 四個既有政策 control：PUT 拒絕、DST readback 相反、+10 秒接受、+11 秒拒絕。
   檢查原 error code、精確 GET／PUT 數量、無重播及 observed-feature 結果。
3. 原測試檔內容完整保留為新檔前綴。完整執行六個原相關 suite：`test_ccapi.py`、
   `test_maintenance_lifecycle.py`、`test_api.py`、`test_gphoto2.py`、
   `test_ccapi_bulb_recovery.py`、`test_bulb_cross_control_ownership.py`。
   原 maintenance 八個 host-DST 季節／轉換案例不變。
4. 維持原完整 lint 指令 `ruff check --no-cache . ../scripts/validation`。

## 實際證據狀態

已在 2026-10-21T04:17:44.580488+00:00 完成本機 gate：

- 原始來源 baseline：十二例，精確四個命名 DST assertion RED、八個 control PASS，無 error／skip。
- Candidate 六個完整相關 suite：401／401 PASS，其中 389 個原有 case ID 完整保留，新增十二例全部 PASS。
  這不是整個 repository 的全部測試數，也不把 focused／完整 suite 重複相加。
- 原完整 Ruff 指令 PASS，凍結的 400 個 current source／fixture hash、baseline closure 及原測試 byte prefix
  均於終態重新核對。
- 初次驗證器把 JUnit 的 unittest class 名誤轉成檔案路徑，在實際 401 個測試已 exit 0 後停止；
  保留原 38.91 秒 STOPPED receipt。只修正 class／method／parameter 到既有 collected roster 的一對一對帳，
  重用原 XML，拒絕 duplicate／missing／extra，不重跑任何測試或變更 assertion。
- 原同一 300 秒絕對窗共用 88.03 秒，包含上述 verifier 修正與其後 Ruff／source seal；
  沒有重置預算。最高 sampled process-tree RSS 為 177,924 KiB，最低 free disk 為 5,054,664,704 bytes。

本批本機 gate 上限為一次 300 秒（含 setup、必要診斷與終止封存）、2 CPU、
512 MiB sampled process-tree RSS；保留 2 GiB 磁碟 floor 與 32 MiB stage reserve。
只使用既有 Python／pytest／Ruff，不下載、安裝、啟動 browser 或實體設備。

原完整 Desktop／Windows workflow 必須另以實際新 SHA 的終態確認，並非本機 scoped PASS
即可代替。預計路徑只觸發 Desktop＋Windows；不變更 workflow、不觸發 Apple／Android／KVM。
實際 camera DST／body menu、USB UTC／local property 語義及物理驗收仍未證明。
