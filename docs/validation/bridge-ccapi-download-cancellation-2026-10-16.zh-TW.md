# Bridge CCAPI 下載準備取消（2026-10-16 UTC）

## 原需求與基線

P2/G09 延續原檔下載取消責任。來源為共同 dev `c5749763f39454b9ce9f792337c35d009998f3e9`。
前批已處理 gphoto2；CCAPI 仍在 session lock 內等待 urllib HTTP headers／body，600 秒 socket timeout 前沒有取消入口。

## 真紅燈與修正

使用本機 loopback HTTP server，只有 discovery／控制資料使用既有 FakeCcapiTransport；下載確實走 production UrllibCcapiTransport、CcapiSession 與 ASGI app。peer 分別停在送 headers 前、送完 headers 加一個 body byte 後，確認 request 尚未完成才發 disconnect／task cancellation。四個基線案例都在一秒後仍卡準備而失敗；finally 釋放 peer 並回收 request/server。最初 fixture 把 session 的 201 寫為 200，已另保留該失敗，不當作產品紅燈。

修正採每次下載專屬 urllib HTTP/HTTPS handler 與 socket owner；取消只對該 owner 的 socket 執行 shutdown，讓讀取 worker 醒來，再由 worker 關閉 response。避免跨執行緒 close BufferedReader 卡鎖。保留既有 TLS 驗證、proxy、授權 header、一般 redirect 與 single-original 禁止 redirect 的語義；不加入下載重試。

CcapiSession 在開啟前、取回 response、讀取前後與成功觀察前查取消；Bridge 普通、single-original、JPEG validation 都傳同一 request owner。舊 owner 退休不指向新請求。取消後移除 partial、釋放 session 鎖，明確新 attempt 才重新下載。

## 驗證範圍

真 HTTP 12 案：headers/body stall × disconnect/taskcancel × ordinary/single-original/JPEG validation。確認取消返回、partial 清除、未新增下載成功觀察、一次取消僅一次 media GET，以及同 session 新 attempt 成功。原 single-original/JPEG/Range 契約以既有回歸檢查，不以假 transport 取代真 HTTP stall 證據。

此處 socket 註冊發生於 connect（HTTPS 含 TLS setup）完成後。DNS、TCP connect、proxy tunnel／TLS 初建仍受原 timeout 約束，不能宣稱這些階段已能立即取消。第三方 custom transport 若未實作可取消 stream capability，仍只有 chunk 邊界取消檢查。

沒有真相機、Windows／WSL、Android/iOS native、KVM 或 CI 執行；不代表完整三端產品／可下載 App 已驗收。不變更 main 或發版。

## 獨立審查

只讀審查覆核 socket 物件 ownership、晚註冊、redirect hop、body close／release、single candidate 與取消不成功觀察，未發現待修阻擋項。新增六個 transport 測試涵蓋 redirect 後停滯 body、禁止 redirect、晚 watch、退休 owner、新 request 隔離與 close 失敗仍移除 callback。

HTTPS／proxy／跨 origin 授權轉送沒有新增真網路測試；授權轉送沿用 urllib 既有行為，並非本批新增安全加固。審查者未親跑測試。

## 最終本地結果

全部 test_ccapi*.py，加 test_download_disconnect、test_api、test_gphoto2、test_jpeg_original：603 passed，exit 0，56.92 秒。新增 18 案（12 ASGI、6 transport）。兩個 warning 為 Starlette/httpx deprecation 與 GET/HEAD route OpenAPI operation ID 重複提示；未隱藏。Ruff 與 git diff --check 通過。

測試來源 SHA-256：
- `bridge/open_eos_bridge/app.py`: `1215e171990bd7db78b1ee26b84437acc966298fd1a99c1f0f9c8ec8390d5a7d`
- `bridge/open_eos_bridge/ccapi.py`: `a5752ed0026f4460aa3beadc71d32f2927f945c444673184598b32912c17a44d`
- `bridge/tests/test_ccapi_cancel_transport.py`: `7b7691cb0290cb79fe9dba18acb83a74e7f3480a3a6b176ed7846bdd1523fe1e`
- `bridge/tests/test_ccapi_download_disconnect.py`: `c7bb4f5ebca4924241323691a1ea479306660e6ca9fddb2979046ce9abb0bfb7`
