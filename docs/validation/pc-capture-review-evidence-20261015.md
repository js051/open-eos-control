# PC 拍後唯讀查找：觀察與讀取證據

## 實作範圍

- 基底：3c11e937369dd5952f6393d70812cc1e04825830（已封存素材日期群）。
- 拍照 ACK／確認停止錄影之後，清單查找保留獨立的 observation 與 immutable read evidence。
- observation 為 found／no-candidate-observed／unread；證據包含本輪 successfulReads、failedReads、lastCompletedRead、candidateId。
- 曾成功讀取但最後失敗時，英文／繁中均同時顯示「完成的查找未觀察到新素材」及「最後讀取失敗、目前可見情況未確認」。過去成功不得遮掩新一輪全部失敗。
- HTTP 成功缺少合法 items 陣列，或包含沒有有效 ID／名稱的項目，均屬讀取失敗；不得被轉成成功空清單。
- 保持前次素材、已知 ID snapshot、種類判定、相機順序、四次讀取與 250／750／1500 ms 間隔。重試只讀，不重送 shutter、REC、AF。
- 取消、替換 owner／同 ID 新 session、AbortError 均不得發布半輪或過期證據；搜尋屬暫態，取消後回復前一終態。新拍攝尚無完成讀取時不憑空宣稱讀取失敗。

## 共同契約

contracts/capture-review-evidence/v1/review-evidence-vectors.json：25,745 bytes；SHA256 aab83ace169997c2f9788ea45f2863513f0a0d99e3211143c0e60475eb7ec7b1。

106 組固定終態向量（16 成功／失敗排列 × 3 種無候選清單 × 2 種意圖，另含找到與無效回應邊界）、2 組跨輪重試、4 組 retirement 規格。測試以固定 JSON 比對實際 production functions，不由被測函式推導預期值。

## 驗證層級

- 實際 Node production-function focused tests：323 項通過；共同向量包含於其中，不重複加總。
- 完整 module suite、553 pytest、實際 CI 範圍 ruff、sdist／wheel 均本地通過。精確執行時間、完整計數、退出碼及原始碼／產物 hashes 另封存於本群累積交付收據。
- wheel 內 app.js、media-library.js、media-transfer-history.js、index.html 與工作樹逐位元比對。
- 測試是記憶體／本地 synthetic，沒有向相機發送 HTTP 或實際拍攝。瀏覽器整合、WebGL、原生畫面／平台與實機仍未由本群補驗。
- 現有 PR 仍 draft，尚未推送本群；不得稱為 exact-head CI ready。

## Release Assessment

- 已查核發布基準：v0.12.0 Development Preview（2026-10-15 03:54 UTC 查核）。本修正相容既有功能，impact 為 patch；整條累積功能分支相對基準仍屬 minor。
- 本群不改 0.13.0 開發版本，不合併或發版。
- 待驗：累積分支原生／瀏覽器 exact-head CI、原生 malformed adapter、GPU／RTP、錄影模式切換與真機；保留所有既有必要檢查。
