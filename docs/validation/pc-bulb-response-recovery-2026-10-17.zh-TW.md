# PC Bulb 回應不完整與恢復讀取等待（2026-10-17）

對應原89列 G04／#65 與 G13／#122。基準 e19dab024d0ea980c426d893cb30c36c1d77c08a。

## 已確證與修正

1. Bulb Start 收到無法解析或缺少必要 error 欄位的502時，production api 回報 RESPONSE_BODY_UNAVAILABLE，原 caller 未保留停止責任。後續 cached idle status 使下一次操作再送 Start。最小修正把這個未知結果納入 sticky stop-only 責任，普通 idle read 不能清除它，明確 Stop 成功才可清除。
2. 失敗後的恢復 GET /status 也可能卡在 fetch 或 body，讓 busy 永遠隱藏 Stop。只對這個只讀恢復加15秒 Promise.race／AbortController；時間到保留警告並重新開放明確 Stop。忽略 Abort 的晚回覆不改狀態，換 session 不轉移警告或清除新操作 busy。沒有在這批對 mutation Start／Stop 加不安全 timeout。

新增9個 production-function host 案：四種502不完整＋一個明確拒絕控制；恢復 fetch/body 各含原session／replacement兩案。502基準4 fail／1 pass→5 pass；恢復原session基準2 fail→最終4 pass。capture-review 最終409 pass，既有 bulb-recovery contracts 通過。

## 真 Firefox 的有界證據

受驗 source 244da0169de76b591530db8df61efa12a9133806；app.js SHA256 f1806207933b70ef4b896bf7c11b3a22d9b01c5f399aac442ba4465147964b5e。之後 gphoto Python 整合沒有改這份 JS。

沿用 dot 雲端既有 Firefox 157.0.1，80% page zoom／CSS1485×953，正式 HTML/JS＋明顯合成 camera/fetch；無相機、USB或真曝光。19:48:14啟動，19:49:48停止，PID38162已確認退出。

- [截斷502後](evidence/pc-bulb-recovery-2026-10-17/01-malformed-502-stop-duty.png)：黃警告、Retry camera stop 可按。合成 press=true，沒有錯當停止。
- [明確Stop](evidence/pc-bulb-recovery-2026-10-17/02-explicit-stop.png)：記錄 start1／stop1，合成 press=false，才顯示已確認停止。
- [恢復讀取卡住15秒後](evidence/pc-bulb-recovery-2026-10-17/03-read-timeout-stop-duty.png)：start2／stop1，警告保留，Stop及Disconnect皆enabled。下一次明確Stop完成，沒有重送Start。
- [下一缺口的基準](evidence/pc-bulb-recovery-2026-10-17/04-pending-start-baseline.png)：第三次Start本身永久pending，Stop／Disconnect仍disabled，label仍Start。這張是失敗基準，不能算修正通過。

[evidence目錄](evidence/pc-bulb-recovery-2026-10-17/) 保存 fixture、原始事件、來源hash與terminal收據。fixture的pressed值是合成值，不是相機結果。曾因自設磁碟門檻而未啟動；依既有短流程實際cache大小調整本地保守門檻後才啟動，未變更安全設定、安裝或清除不可重建資料。

## 尚未解決與下一步

Pending Start 不能只加timeout後允許Stop：沒有request owner時，Stop可能先到，而晚到Start之後才press。下一批須由Bridge admission／取消記錄與PC exact-owner receipt共同防止此順序，保留舊手機/provider介面；本批不宣稱已解決。

未執行Android/iOS native、真相機曝光／光學釋放、Windows／WSL或新安裝包；未合main／發版。相關gphoto停止責任另見 [gphoto驗證](gphoto-bulb-stop-ownership-2026-10-17.zh-TW.md)。

## 本批共同來源整合檢查

產品source 384d7e7b（前述PC兩修正＋gphoto saved Bulb release）實際執行：完整PC module命令3738 passed／0 failed／0 skipped，53個node:test報表，另含該命令中的直接assert腳本；Python選定gphoto／recording／maintenance／shutdown／API為411案，static UI15案，合計426 passed。Python既有Starlette deprecation warning一項，未更換依賴。syntax與diff whitespace通過。這些是host與合成HTTP／CLI證據，不是平台原生或真相機驗收。
