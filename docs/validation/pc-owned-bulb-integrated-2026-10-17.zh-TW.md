# PC／Bridge owned Bulb 整合（2026-10-17 UTC）

原列 G04/#65、G05/#66/#69、G13/#122。這是既有停止責任修復，不是新增拍攝功能或三端驗收完成。

## 已確認的缺口與修正

1. pending Start 無可按 Stop：PC 在送出前保存精確 owner，Start／Stop／close 整個 fetch＋body 各有15秒界限；Stop不等待Start回覆。Bridge新owned路由保存不淘汰的cancel記錄，阻止Stop先到之後late Start才按下。舊Bridge無路由時不回退legacy Start。
2. gphoto 半按／獨立 AF cleanup失敗：保存原release path/value及正確feature，Stop與close只重試原release。KeyboardInterrupt仍先嘗試release再重拋，不冒稱Bulb成功。
3. 整合複審發現Bulb release的status tail失敗後，另一caller能啟動AF，舊Bulb Stop重試誤停新AF。Bridge原子admission共用execution lock；檢查在等待前及dispatch前皆執行，只讀、Stop和close仍可用。
4. A已terminal但HTTP回覆遺失，B其後產生未知停止警告時，A的cached receipt不可抹掉B。PC無status回覆只退休A，保留較新觀察／警告／錯誤；仍鎖定時不顯示全相機停止成功。

契約與host故障注入分見 [server owner](bridge-bulb-request-ownership-2026-10-17.zh-TW.md)、[G05 short control](gphoto-short-control-stop-2026-10-17.zh-TW.md)、[cross-control admission](../bulb-cross-control-admission.md)、[PC owner](../desktop-bulb-request-ownership.md)。

## 固定整合 source 與檢查

產品source `1128b4b2`（包含G03 Escape修正）：完整PC modules **3791 pass**（54組node:test報告，另原direct assertion scripts）；Python主批 **608 pass**、相鄰capture configuration／focus contract／shooting settings **77 pass**。Python兩項既有deprecation/OpenAPI warning保留。首次整合的static字面路徑gate失敗已透過明確owned route修正，沒有降低檢查。

## Firefox bounded evidence

[六張圖與telemetry](evidence/pc-bulb-owned-2026-10-17/assessment.json) 固定較早PC source `f3373bb7`：

- pending Start立刻顯示Stop，Stop/Disconnect均可用；實按Stop先取消owner，晚到Start沒有該owner的synthetic press。
- 第二Start已dispatch但回覆不settle，15秒後仍保留可用Retry Stop；首次Stop malformed502仍未知，第二次明確重試才解除。
- 第三Start pending時實按Disconnect，saved owner Stop成功後close；總3次Start、4次Stop，末態合成pressed=false。

這是production HTML/JS＋合成fetch/相機狀態。Bridge排序另由production server/provider host cases驗證；瀏覽器fixture不是實體相機，也不是此較早source已解所有cross-caller缺口的宣稱。較晚cached-receipt fixed GUI另記。

沒有新APK、iOS包、Windows installer、真相機曝光／AF或mobile native通過證據。Android/iOS Bridge caller是否需要同一request-ordering契約仍獨立核對，不能只因PC修復寫成三端對齊完成。

### 20:21 UTC cached receipt GUI補驗

source `4faea09a`（產品與1128b4b2相同，僅test格式整理）：[三圖與telemetry](evidence/pc-bulb-cached-receipt-2026-10-17/assessment.json) 證實A release已成功但HTTP回覆遺失，合成另一caller的AF B清理失敗後，Refresh讀到B警告；A cached released無status只清A，畫面仍Retry Stop且footer仍unknown。下一次明確legacy recovery才清B並顯示停止確認。依然是production PC＋合成transport，不是相機或mobile native。
