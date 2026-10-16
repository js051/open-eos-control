# REC 命令接受與狀態讀取的恢復界線

## 已證實的問題

原 P1 完整拍攝流程中，Bridge CCAPI 已成功送出 REC Start／Stop，之後 status 的網路或 JSON 失敗仍被當成一般命令錯誤。以真 ASGI、urllib 和獨立 TCP peer 執行：Start ACK 後 batterylist 斷線回502 CCAPI_UNREACHABLE；再次明確 Start 請求仍送出 Start。這不是 Bridge 自動重試，也不是實體相機故障證據。三個新 backend 控制在修前2失敗／1成功，修後3成功。

## 最小公開契約

僅在 CCAPI 命令成功接受後，後續 status 的 BridgeError 改為 additive RECORDING_STATUS_READBACK_FAILED，HTTP502、VIDEO_RECORDING feature、ccapi engine。命令拒絕或 ACK 回應遺失保留原錯誤，不能宣稱命令已接受。舊客戶端仍可按一般错误處理；新客戶端只辨識精確 code，不比對訊息文字。

canonical iOS／Android direct CCAPI 的 optional GET 普通錯誤會被既有 fallback 吸收，與 Bridge 不同；不能把同一網路錯誤假說套到所有平台。取消仍須保持原取消語義。gphoto2 的正常 status 可回 false／unknown，PC 不再一律顯示 Recording started；本批沒有把未證實的 gphoto2 readback throw 加入新契約。

## PC 使用流程與所有權

- ACK/readback失敗或命令成功返回unknown recording時，獨立保存本次 session／pending物件和命令方向，保留原已發布 status，不自行把缺失回覆補成 boolean。
- 暫時只提供明確 Stop，保留 Stop 的能力／溫度豁免；禁止切回 Photo、清潔／休眠及開始前景交付。REC指示不把未知狀態畫成已確認錄影。
- 使用既有 Refresh 只讀重查。本次後端回覆具有 boolean status、相同 session物件／互動世代／pending物件／Refresh請求物件才解除；舊 Refresh 不清較新的 pending，也不覆寫新status。null或失敗維持明確警告。
- Stop ACK後保留原已見素材邊界；只讀重查返回false才接回既有影片查找。Start pending讀回false不當成Stop，也不啟動影片查找。
- 沒有自動REC重播、背景恢復、新endpoint或額外掃描框架。沒有保證相機實際停止；後端回覆可用與獨立實體REC觀測是不同證據。

Bridge canonical CCAPI 的 recording 既有欄位來自成功命令 ACK 後的方向快取，不是另讀相機 REC旗標。本批不改此相容契約；Refresh解除的是缺失的後端狀態回覆，不能宣稱獨立驗明相機錄影或停止。真TCP peer控制也只驗此快取契約。

REC進行中不准入Refresh，避免在命令中途取得舊status；方向在命令前固定。並行Refresh採最後請求所有權，舊回覆不得在較新回覆後覆寫。重复明確Stop保留第一次Stop的已見ID邊界，Start pending讀回false顯示未確認提示。

## 驗證狀態

受控Node直接執行production函式，涵蓋Start false/null/缺欄、ACK後Stop-only、相同／替換session舊Refresh、pending token、只讀恢復、原knownIDs影片查找、未知與失敗保持、功能撤回仍可Stop、未ACK錯誤不錯分。正常既有27模組保留；fixture只補新production helper依賴，未刪原assertions。

backend六個新增真HTTP控制加原快門／Bulb恢復29項共35項在20.007秒通過。包括network/invalidJSON、成功Start/Stop、命令拒絕、ACK遺失且不重播。Node完整模組最新終態見本批收據；新增雙語browser source沿既有真Bridge/TCP peer驗ACK→明確Stop→只讀Refresh，不替換相機response。browser尚未執行，需正常exact-head CI。

完整真相機、持續REC、程序終止時的相機端責任、外部provider及三端合併runtime仍未驗；依使用者指示最後集中App／實機驗收。維持原開發草稿，未合併main或發版。

## Release Assessment

發布基準v0.12.0 Development Preview；本群為向後相容的P1可靠性patch。原草稿完整媒體新能力仍屬minor，不能以此降低整批影響。release HOLD及真機證據界線不變。
