# 2026-10-16 原生 Firefox 合成 CCAPI 窄驗收

## 固定來源與範圍

- 受驗產品 commit：`41bf6ef0684f982937f5d5fdf428feca3b683f04`，獨立工作樹保持 clean/detached。
- 文件 dev `428672724c1e08fcf44a0c2cda9c37c89ad00c1c` 僅增對帳文件，未偷換受驗產品。
- 雲端桌面原生 Mozilla Firefox 157.0.1 / BuildID 20261005135250，透過正常視窗 UI 操作。
- Bridge `127.0.0.1:18765`、合成 simulator `127.0.0.1:18766`。測試 wrapper 固定 FakeRunner、禁止 EDSDK provider、CCAPI 僅允此 simulator origin、禁止 redirects/credentials、停用 RTP/UDP；未連真相機。
- UI app.js SHA256：`83a1f6154b4f2e06cf49038f694cadf3f956e077f141e19aae76883310ea386d`。
- fixture safe_bridge.py SHA256：`2c4212e273fc52230aa97aa508e9eebbb99364ab9e7fe6ac141d21cfa5cc68d0`。
- fixture start.py SHA256：`edc8b0fed4d410e2e05fa03e7ba2b25ee9b39946223b478a47f8eecdac62e734`。

## 實際結果

本窄流程 PASS。07:46:18.385 UTC ready，07:51:15.612 UTC 受控終止；沒有 timeout 或磁碟 guard 停止。

| 操作 | 實際 UI 終態 |
|---|---|
| CCAPI 合成相機連線 | Connected，顯示 Canon EOS R6 Mark III |
| 啟動 Live View | 合成影像可見、約 15 FPS |
| 1× → 5× → 10× → 1× | 每步皆顯示相應倍率設定成功文字 |
| Near / Far | 分別顯示 Focus drive command accepted: Near / Far；未聲稱鏡頭位移已完成 |
| 停止 Live View | 黑畫面、Start Live View 入口恢復、Near/Far disabled |
| 斷線 | 返回連線表單，保留合成 URL |
| 明確 Connect 重試 | Connected，重開 Live View 再有合成影像與約 15 FPS |
| 收尾 | 最後停止 Live View、斷線、關閉 owned browser/fixture |

正常關閉收據立即抽樣曾見短暫 child PID；07:51:23 UTC 後續 ps 已確認五個 owned PID 全退出。未殺其他工作程序。

最低實測可用空間 4,400,005,120 bytes。保留 4 GiB 底線；64 MiB profile / 64 MiB 提前停止 cushion / 8 MiB fixture budget，以 50 ms 抽樣監控，並非 kernel 硬 quota 或完整峰值量測。

第一次原生嘗試已成功顯示 EOS 首頁，但因首啟明確條款步驟暫停，保留原 BLOCKED 收據。之後核同一版本/build 已接受條款證據，正常 UI Continue；技術/互動診斷與 crash 分享都確認未勾。未修改 prefs 或關閉 sandbox。

## 驗證界線

不代表 Playwright/Chromium 全矩陣、iOS/Android 原生、真相機/鏡頭完成、RTP codec、取消晚回覆競態、Touch AF/Click WB 座標，亦不代表原89列功能全部 runtime PASS。沒有產生新 APK、iOS 或 Windows 發布包。

機器收據、fixture/source hashes、完整程序終態與兩張持久截圖（重連 Live View／最後斷線）保存在本批驗收證據中；先前環境阻塞歷史未覆寫。
