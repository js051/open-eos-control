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

## 後續完整修正群納入

上述三來源基線封存後，再依序正常合併已獨立 review/封存的修正：

- iOS legacy JSON fail-closed `976504471f2b66d7e08504e3cca362c2d0e697e7`。
- Android legacy JSON fail-closed `0bd7592e61e652b9dd9c072d6adec77561fad455`。
- gphoto2 逐步清單與受控取消 `deff51341846815192d1649512a39339a65a3118`。

三次合併沒有衝突；整合產品 checkpoint 為 `1b3c59aedeb768dfb2f605566c88d7b2f63cf4ac`。gphoto2 原來源最終 163/163 PASS（44 個專屬 fixture）；在合併工作樹以相同已安裝 Python 環境再次驗新 44 項，exit 0。實際 PC legacy parser 的 9 個完整/超限/非法向量於整合樹通過；不拿此結果替代 mobile native。最初預設 Python 缺 pytest、PC harness 未提供必要 module path 兩次配置失敗保留在收據，改用既有 venv 和本樹精確 parser 路徑後才記 PASS，未安裝。

P2/G08 的 gphoto2 清單現在使用單一可取消子程序、受控 stdout/stderr 逐步讀取及完整 folder count 驗證；失敗不提交 partial cache。mobile legacy JSON 不再把非法列、重複 ID 或超限回覆截斷成成功。兩群詳見 [gphoto2 驗證](../gphoto-progressive-listing-validation.md) 與 [mobile legacy JSON 驗證](mobile-legacy-json-listing.md)。Windows/WSL、mobile 原生和真相機後續驗收仍待完成。

新 legacy 修正合入後，既有 iOS 守門因原 guard 末端的 `else` 前加入 duplicate-ID 條件而再次失敗；保留原 parse 驗證，補入 exact-ID 唯一性、兩種數量上限與禁止截斷斷言。移除 duplicate-ID guard 的反向控制確實失敗；最終整合來源守門 148/148 PASS。這一處是新修正的對應測試更新，與前述 8 個歷史過時守門分開記錄。
