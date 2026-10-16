# Bridge gphoto2 下載準備取消（2026-10-16 UTC）

## 原需求與範圍

P2/G09：取消原檔下載時，不讓已離開的 HTTP 請求繼續佔用相機與準備暫存。
來源基線為共同 dev `c0af91f5061aa9e2d8ca37a3e885f1f4f9c39990`。

原問題：同步 GET 在回傳 HTTP headers 前完整 `_stage_media`；前端中止 fetch 不會通知此 worker。
gphoto2 子程序可繼續讀取，session 鎖保留至完成或 600 秒 timeout。

## 產品修正

- 普通原檔與播放準備使用每次請求獨立 cancellation owner；監測 ASGI disconnect 及 task cancellation。
- 同步準備與播放 cache gate 留在 worker；event loop 不等待同步 lock。
- 取消清理有獨立有限 executor，不排在已被準備工作佔滿的 executor 後面。
- gphoto2 使用可關閉的原子程序串流；取消關閉／等待 child、結束 iterator、釋放原 session 鎖。
- 取消不產生下載成功觀察；partial 暫存移除。播放剛放入 cache 後取消，也撤銷 ticket 與檔案。
- Windows／WSL launcher 的退出不證明相機程序停止；取消後採 runner-wide 未確認清理 fence，必須確認相機並重啟 Bridge，不能假稱可安全立即重試。
- 維持原長度、JPEG 原檔驗證、Range 與 HTTP 資料格式。

## 真實驗證與限制

基線實際執行 production ASGI app → gphoto2 session → controlled Python child：先證明 child 正阻塞，再送 `http.disconnect`；一秒後仍在準備，確實失敗。測試 finally 釋放 peer 並收回 request，未留下相機程序。

修正後案例涵蓋：普通／播放 × disconnect／task cancellation × 有無同時排隊請求；將 default executor 限制為一個 worker，驗證取消仍能前進。另驗晚註冊 close、舊 owner 不影響新 owner、未確認平台清理 fence、cache put 後取消清檔與撤銷 ticket。

PC `cancelMediaDownload` 的 AbortController、Android `DesktopBridgeClient.downloadMedia` 的 OkHttp call.cancel，以及 iOS `CameraHTTPTransport.download` 的 task cancellation 都可通知同一 Bridge HTTP 路徑。此處是 caller source 對照；未宣稱 Android/iOS native 實測。

CCAPI urllib 阻塞 headers/body 與外部 EDSDK provider 尚無本批等價中止證據；逐 chunk 拒絕取消後結果，不等於能立即中斷其阻塞 I/O。它們是後續需真 HTTP stalled-peer 驗證的工作。

無真相機、Windows／WSL、Android/iOS native、KVM 或 CI 執行；未產生可下載 App，亦未合 main 或發版。

## 本地終態與獨立審查

精準回歸 test_download_disconnect / test_api / test_gphoto2 / test_jpeg_original：339 passed，exit 0，24.08 秒；Ruff 與 git diff --check 通過。新增取消測試 13 案。

獨立只讀審查提出並復核修妥：同步播放鎖阻塞 event loop、default executor 飽和令取消排隊、cache put 後 task cancel 遺留檔案，以及 close exception 不能放棄等待 preparation worker。最後一項以兩種取消路徑的 barrier 反例驗證；cleanup 失敗仍保留 runner fence，不冒充 child 已確認退出。審查者未親自跑測試。

基線紅燈使用 7-byte controlled payload，最終播放 fixture 使用符合已宣告 media 長度的 13-byte payload；基線來源 SHA 固定如上，不宣稱 fixture byte-identical。

產品／測試來源 SHA-256：
- `bridge/open_eos_bridge/app.py`: `617880c5ede73b39ece4263d5259f84ea9ffb1546f7f2ac2b7e9fb76153cad99`
- `bridge/open_eos_bridge/gphoto2.py`: `9bdf36ca734408660c8d21403015aab512c1cb7fc8625e2bc401065264d52bd3`
- `bridge/open_eos_bridge/media_cancellation.py`: `ab1d87e1d8533898cd629b0e0faef72bb371facb2def5f4c4783492731d20c2e`
- `bridge/tests/fakes.py`: `20765f842021c8aa48206088beca927b4170529c1bd98d45274d3174589013de`
- `bridge/tests/test_download_disconnect.py`: `3e151d65cbb6d8a930726c119a76379a8891342181f933f1d988008edab81b91`

保留基線紅燈、JUnit、完整 log 與收據；原生未跑範圍如上。
