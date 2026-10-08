# 普通 Android App 的日期篩選與原檔保存驗收

日期：2026-10-08 UTC。這是既有產品驗收的有界補充，目前仍在實作與本地檢查階段；沒有普通 App／IME runtime 通過結果。

## 已接受的 App 與本批範圍

本次 App 固定使用 accepted main `65fbabf00c78924228ec7cbfe22d733ef806094f`，tree `c1e296b9c90bac0cda001cb0bf9bc4d91ef90eb2`。[PR #222](https://github.com/js051/open-eos-control/pull/222) 的來源 `f8690158a3583e3bad3f427e43cf65e5e3d0fbaa` 通過 [CI 37835374025](https://github.com/js051/open-eos-control/actions/runs/37835374025)，包括兩個新增的普通 browser 連線歸屬旅程；squash 後通過 [Main acceptance 37837330054](https://github.com/js051/open-eos-control/actions/runs/37837330054)。受測 tree 與接受 tree 一致。該輪 Android UI／iOS／simulator 依原路徑分類跳過，不能算成本輪重驗。

本批只補 synthetic peer 與外部普通 UI 驅動，不改上述 App、Compose 時鐘、相機協定或產品 timeout。目標為一個 API 36 普通 App 旅程：預覽指定 JPEG →「另存至其他資料夾」→ Android CreateDocument → 原檔下載持續期間以真實軟鍵盤輸入日期 → Apply 排除正在下載的素材 → 同一下載仍未完成 → 明確 Release → 驗證目的文件的完整原始 bytes。

這只能建立「同一傳輸跨過可見 IME／日期 dialog 關閉，之後完成」的證據。它不保證下載完成與關閉落在同一 frame，也不解釋 PR #219 的原始 Compose 崩潰。原始根因仍 UNKNOWN，frozen v0.13 候選仍 HOLD。

## Synthetic peer 的有界控制

沿用既有 Canon-shaped captured JPEG fixture；必須在普通 App 選 HTTP 後輸入 simulator 位址。Simulator preset 使用另一組 `/ccapi/media` 路徑，不能拿來代替此案例。

- 單一明確 opt-in 的 captured item／request，使用相關 ID 綁定 release／cancel。
- 立即送出既有 JPEG 的前 64 bytes，之後每秒送一個真正的後續 byte；明確 Release 才送剩餘內容。沒有 padding、換圖、重新壓縮或 App timeout 變更。
- 固定 180 秒上限，逾時中止未完成回應，不自動成功。重複請求、fallback 或終態後重試保持拒絕，直到明確清理，避免失敗被後續普通成功掩蓋。
- Content-Length 與 SHA-256 綁原始 bytes；記錄有限的生命週期時間與計數。ASGI accepted 表示服務端交接，不代表 APK 已收到或儲存。
- 全部 bytes 已被 ASGI 接受後的取消會被拒絕；不能承諾撤回已交付 bytes。預設未啟用路徑及 thumbnail／display／info 保持原實作。

## 本地已取得的證據

17 個 focused real-socket／ASGI 案例通過，涵蓋 prefix、兩秒真實滴送、release 原檔、expiry／cancel／disconnect 的截斷、三個 Canon fallback、stale ID、重複控制、reset、blocked send，以及完整 bytes 已送後不能假稱取消回滾。

初次完整 simulator 76／76 通過後，Ruff 發現兩個本地風格問題；該輪整體 exit 1 保留。修正 timeout suppression 與精確例外斷言後，final source 再次 76／76 通過，Ruff 通過。最終輪實際為 20:42:16–20:42:22 UTC、exit 0；一個 CPU，aggregate RSS 峰值約 93.7 MiB，來源執行前後 hash 一致。失敗 deadline 測試使用內部縮短時限；固定公開 180 秒設定另有斷言，沒有聲稱做了多次三分鐘裝置試跑。

此環境實際 Python 3.12.14、FastAPI 0.143.0、Starlette 1.7.0、Uvicorn 0.54.0、Pydantic 2.14.0、Pillow 12.3.0、pytest 8.4.2。這是本地版本，不代言未執行的遠端解析版本。獨立來源 review 未發現 blocking defect，核對檔案 hash 與最終 receipt 一致；沒有把來源 review 當成裝置測試。

## 普通隔離測試 APK

從上述 accepted tree 使用 repo 既有 `-PlocalDebugApplicationIdSuffix=true` 建置。20:31:58 UTC 明確 exit 0，15.60 秒，來源／工具 hash 未變；未安裝或執行。

- Package：`dev.openeos.control.debug`
- Activity：`dev.openeos.control.MainActivity`
- Version：0.13.0／29；版本號不能單獨識別此檔案
- 大小：19,570,492 bytes
- SHA-256：`2fbd497a0bfcafdcdc2e884dd1966747518b80743d2c0bd494f7349ccab538f4`
- v2 signature 通過；本地 debug signer：`93036275eb5e93a3e0259d9a08aabaf15837345c7e8b968cdcf8b6f7913db20e`

這是普通 App 測試包，不是 release candidate。先前同 package 的 debug build 保留為歷史，因 signer 與 preview 不同，不交付為覆蓋既有 preview 的方法，也不要求清除資料。

## 尚待完成

外部 driver 的 64 個 host-only 負例已通過，包含 selector、timeout、UID／目的文件歸屬、IME／window、failure cleanup、Save 前開始預算與最後 Apply 前仍須有可見 IME。最後使用與 CI 相同的 Bridge Ruff 設定通過；較早 fixture／lint 失敗保留。整合後的完整 validation helper 84 例與 CI helper 37 例均通過，整個 scripts/validation 依 Bridge Ruff 設定通過；不是將重跑相加。普通 hosted API 36 job 尚未發布／執行；需要 unique live accessibility 目標、實際鍵盤與 dialog 影像、同一 held request、完成紀錄及目的文件 bytes／hash。機器檢查與影像留存後仍須 review 可見鍵盤證據，不能把未看過的 PNG 當作已判讀。正常 show／hide 動畫使用原 20 秒條件等待，不增加 timeout；未知 dump 為 NOT EXERCISED，未收斂則失敗。Automated PASS 保留 visual review PENDING、final acceptance false、release HOLD。

獨立來源 reviewer 已檢查 driver／launcher 整合；其指出的最後 Apply 前鍵盤可能已消失缺口已修正並新增不觸碰負例。之後僅依 CI 行長設定格式化，AST 等價檢查通過。候選 launcher 的 Python AST／Ruff、合成完整 workflow 的 pinned actionlint 1.7.12 及 preflight Bash syntax 均通過；actionlint 先前發現 job env 不允許 runner context，改為 preflight 使用 RUNNER_TEMP 建新目錄，未改安全設定。Local ShellCheck 未安裝，未聲稱該子檢查執行；原 CI 檢查保留。

本次採當前分支明確 opt-in 的獨立 job，固定 accepted App 與當次 fixture／driver 身分，不永久接入 `ci-complete` 重驗舊 App。所有原必要 CI 保留。KVM 僅做 read/write access preflight，沒有權限即 BLOCKED；不變更 udev／權限，也不退成 CPU-only。動畫保持正常，無 test APK 或 `am instrument`。

任何缺少來源／APK／裝置／IME／目的檔證據的結果均不得列為 accepted。新服務、裝置操作、hosted run、merge／release 均未由本頁執行。此工作不代表實體相機、手機網路、USB、外部儲存 provider 或原崩潰根因已驗證。

## Release Assessment

- Baseline：[v0.12.0 Development Preview](https://github.com/js051/open-eos-control/releases/tag/v0.12.0)；source metadata 0.13.0 未變。
- Impact：`none`，驗收工具與 synthetic fixture，不含產品修復或新相機能力。
- Value：用普通 App main loop 補充一個可辨識、可停止、可查 bytes 的媒體旅程證據。
- Blockers：本批 driver／獨立 runtime 尚未驗收；原 Compose 根因 UNKNOWN；frozen v0.13 payload 不包含之後已接受的控制修復，不能回填歸功。
- Physical device：pending。沒有新增物理結果或發版。
