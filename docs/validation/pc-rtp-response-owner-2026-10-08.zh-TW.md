# PC RTP 狀態與音訊啟動的連線歸屬

## 既有產品反例

從 accepted main `65fbabf00c78924228ec7cbfe22d733ef806094f` 執行未修改的 production API、連線、Live View、音訊及availability函式，以mock外部I/O控制完成順序，證實兩條正常UI可到達的路徑：

1. 延遲 RTP status 在切換來源或重新連線後仍覆寫目前整份status，含重用wire ID。舊recording=true在下次availability render會錯誤禁用新連線的Photo。
2. AudioContext.resume 尚未完成時，Live Stop無法關閉仍只存在local變數的context。晚到resume會復活enabled；若使用者已重新Start或重新連線並Start，會未經新Unmute就送出新run/session的audio GET。只有Stop或切換multipart時，反例是enabled/context殘留，沒有audio GET，不能據此宣稱聲音實際播放。

八項production-function反例／正向控制已保存。全部使用合成session、HTTP與AudioContext，沒有真相機、音訊裝置或網路操作。

## 修正範圍

- RTP status 捕捉session object、live generation及interaction/refresh generation。回應只可更新仍屬同owner且目前仍為相機RTP的狀態；可選讀取失敗不阻斷Live View。
- Unmute在resume之前保留audio generation，pending context立即交給既有Stop清理。Stop可在promise永不完成時即關閉它。
- resume成功、錯誤與finally都尊重原attempt歸屬，不重啟新run的polling、不覆寫新attempt的busy/error/context。
- 使用既有單調generation與session object識別，無wire ID唯一性假設；沒有全域互動鎖或自動重試。

## 驗證與限制

最終44項source regressions對未修改accepted source為3個正向controls通過、41個ownership失敗、0取消；修正後44/44通過。全部11個Bridge module scripts、24份JavaScript syntax、backend pytest及Bridge/validation Ruff通過，均在1CPU/640MiB聚合RSS/60秒界線内。涵蓋same-owner正常status及unmute/mute、停止／來源切換／disconnect、重用session ID、restart、晚到resolve/reject及新unmute尚pending時的舊finally。

既有browser fixture新增兩個DOM旅程：pending Unmute→正常Stop並確認context已close→Start→交付舊resume成功／拒絕；確認沒有新的audio GET及錯誤enabled狀態。它們使用原FakeAudioContext，驗證的是產品UI與生命週期，不是實際聲音。獨立source review已通過，尚待remote CI，不能稱為runtime pass。既有liveGeneration也涵蓋拍攝／AF暫停及恢復polling，因此這些操作會保守取消尚pending的Unmute；已播放音訊不因本guard新增全域lock。

本批從accepted65fbabf準備獨立draft交付；PR224另有已全綠的媒體刪除fix等待ready/merge批准。本批不依賴、也未合入該未接受修正。PR223普通App驗收、PR219未知Compose根因與本批分開。

## Release Assessment

- 最新發布基底：v0.12.0 Development Preview。
- 本批impact：patch，修正停止／重連後舊音訊意圖與狀態回應污染新連線。
- 全部release delta仍須包括其他已接受功能；不能僅按此patch决定下版號。
- exact-head必要CI、main acceptance、實體相機/真音訊驗證仍pending。
- Frozen v0.13 HOLD保留；不修改版本、tag、簽章或release資產。
