# 三端共同 dev 本地整合（2026-10-16）

## 固定來源與正常合併

由 main `089e71a3fb3249164d36173b04b682c5612d3966` 建立共同 dev，依序正常非 fast-forward 合併：

- PC `5c94a4afe7d65ea86beffb763e86ffa8f549429f` → `2bd96020741f9eb90c9998ee681f3360d11fcb72`
- iOS `7c0115900d92ff92cd0d8d0138ddd9051ffad7e6` → `eff3ed0875170b4dc80413f6b1d7d7d1cb78bdad`
- Android `6b1df91a97307c7ddae53c52f9921e5c48bedce7` → `5aa5bbfe8455c0e44c9700893f89fd42f9808700`

三個來源均為整合提交祖先，並有固定本地 archive refs。未將尚未封存的 gphoto2 與 legacy JSON 工作混入此批。沒有整包 ours/theirs 覆蓋。

iOS/Android 產品與原生測試目錄分別和固定來源完全相同；Bridge 只有既有測試兩行尾端空白清理。iOS UITest 衝突保留相同 hittable/enabled 條件及較完整 tracing。兩個 add/add 來源守門改用平台限定類別與常數，保留全部方法與斷言，不讓同名類別覆蓋另一端。

## 本地檢查與既有過時守門

- 衝突檔來源守門聯集：27/27 PASS。
- 完整 scripts/validation/tests：初次 148 項中 143 PASS、5 FAIL；更新確證過時守門後 148/148 PASS。
- 三個固定來源的所有 validation test 方法名稱均保留；各平台有效斷言沒有因衝突而刪除。
- 正常 commit hook 執行固定版本 gitleaks，沒有停用安全檢查。

先前 27 項合併守門另有 3 個既有過時斷言：Gradle fixture 已改為 generated manifest、decoder 已改為跨 session surface drain registry、GPU fallback 已加入 explicit bypass。後續 5 個失敗涉及 strict media JSON 的局部變數、已確認刪除才消耗 selection token，以及 native RTP 從 PixelCopy 改為同幀 GPU readback。均已逐檔比對固定 Android 來源同 blob，並經獨立 review；這是來源比對，不是聲稱已在舊 HEAD 執行原生 baseline。

替代守門保留且加強實質條件：fixture 真正綁定資源；前任 writer 完成後才替換；fallback 的 owner/generation；成功 JSON 先嚴格驗證再解析；刪除必須同 owner 與原 selection token；GPU readback 的 320px/250ms 上限、同幀與不可變 pixels；實體分析槽直到完成才釋放；生命週期及 LUT revision 的隔離。八個最小反向控制逐項移除相應 guard，均觀察到測試確實失敗。原始紅燈、產品 SHA256 與測試輸出保留於此批驗證收據。

## 明確未完成驗收

以上只代表本地來源整合與 host structural checks，不是 Swift/Android 編譯、瀏覽器互動、GPU、KVM 或真相機驗收。既有 exact-head 原生失敗與後續 NOT_RUN 仍成立。原六個待批 KVM 固定快照未改，沒有啟動 CI、建立 PR、合併 main 或發版。
