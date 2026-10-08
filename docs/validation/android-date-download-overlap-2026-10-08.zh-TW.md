# Android 日期對話框與原檔下載交界補證

## 目的與狀態

PR #219 的 iOS 拍後 JPEG 修正仍受先前 Android API 36 崩潰調查影響。此批只補測試，不宣稱產品修復、根因或實機驗收。

原失敗 head `e3f84fdeb4508f440ff75a6c1aeea11674da9aa6`、CI `37703284681` 的 artifact `11518284583` SHA-256 為 `ffcfddf20e082af307cb5a7b79a32358f79b96280ab47390af3cebe5b42c1e5f`。下載 gate 放行後，completion wait 尚未結束；檔案 bytes assertion 未執行。主執行緒在 `dispatchDraw → measureAndLayout` 崩潰。Instrumentation 在 `runOnMainSync` 等待時鐘 frame，不能據此說它正在另一執行緒同時 layout。IME 隱藏與 callback unregister 在崩潰前出現只是時間相關性。

後續 `884aaa8c` 全矩陣綠燈只驗證該次帶診斷版本，沒有修復此未明原因。

核對 [Google Maven 官方 1.9.3 source jar](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-android/1.9.3/ui-android-1.9.3-sources.jar) 的 `MeasureAndLayoutDelegate.performMeasureAndLayout`：`duringMeasureLayout` 在 `finally` 重設。因此普通先前 Throwable 本身不足以解釋旗標殘留；也不能只依錯誤文字推定 off-main 並行。仍需失敗時完整執行緒證據。

## 受控比較

- 原 `filteringAnActiveDownloadDoesNotChangeItsOwnerOrOriginalBytes` 保留原操作順序：下載停在 HTTP gate，套用排除該照片的日期範圍，確認下載 owner、可取消按鈕與唯一 original GET，再放行 gate。
- 新 control case 共用相同旅程，唯一放行前差異是先確認日期對話框不存在，並在 UI thread 觀察 activity root 的 IME insets 已不可見，再等 Compose idle。記錄首次 IME 可見值、觀察次數與耗時。這只代表觀察到的 UI 條件；不宣稱存在 visible→hidden 轉換、與原 case 的時序必然不同，或所有平台 callback / animation 已結束。觀察只使用原 15 秒 HTTP gate 剩餘預算；預算耗盡會明確失敗，不額外延長 gate。
- 兩案都保留 completion、完整原檔 bytes 與唯一 GET 斷言，並在下一個 Compose frame / idle 邊界再次驗證日期範圍、下載檔名、bytes 與 GET。原 case 不新增放行前的 dismissal wait。
- 既有有界 phase/Throwable/thread 診斷覆蓋兩案。沒有 sleep、關閉 autoAdvance、跳過失敗、依賴升級或修改 production source；沒有削弱 required gate。

## 驗證記錄

- 原始碼 diff whitespace：通過；既有診斷 wrapper 四項 unittest 通過。獨立靜態審查指出 gate 預算與 viewport 斷言問題，已改為原 gate 剩餘預算及精確日期範圍 state 比較。
- 最終測試 source SHA-256 `73b028d554531d8e0c475cb75f16c3d9a43a1fff340f5ceafa7065f5b6b225ec` 的 instrumentation Kotlin/Java 編譯於 2026-10-08 08:32:26 UTC 真正 exit 0，304.09 秒；248 個 Android source/config hash 前後一致。Kotlin task 實際執行，Java task 為成功／up-to-date。
- API 34 / 36 執行與完整 required CI：尚待本批 head，不套用上一輪綠燈。
- 雲端沒有 `/dev/kvm`，原 Android compile toolchain 路徑已不存在；編譯工具已恢復，裝置執行沿用原 GitHub CI。初次 compile 在依賴初始化時因審查修正而中止（exit 130）；另一次因預設 Android cache 目錄不可寫而失敗，均不是成功驗證。改用專案內 process-local cache 後，以前述最終 source hash 重新通過。
- 兩案通過也只表示該次未重現。若原 case 或 control 失敗，保留第一個 Throwable、phase、原檔與完整矩陣結果再選修復；不能以 control 綠燈替換原 case。

## Release Assessment

本補證 impact 為 `none`，沒有產品可散布行為變更。PR #219 整體既有 patch 評估不因本文件改變。最新公開版基線仍為 v0.12.0 Development Preview；此批不改版本、合併 main 或發版。實體 EOS、Android/iPhone 與弱網相機驗證仍待。


## 同源 CI 的獨立 iOS fixture 失敗與修正

`46e90049606ce74986839257b92825bffcf52c99` 的 [CI 37750631884](https://github.com/js051/open-eos-control/actions/runs/37750631884) 中，iOS Core job `113222809739` 於 08:35:15 UTC 以 245 tests / 1 failure 結束。`testLostPressResponsePreservesReleaseWithoutReplayingTheFullPressOnTheWire` 的 POST / truncatedResponse / pooled 變體，在原 line 99 的 peer-errors 斷言報 `incompleteRequest`。先前單次 full_press、release 重試、idle、close 不新增請求等斷言沒有報失敗。此為獨立 required-gate failure，不是 Android Compose 崩潰的根因。

Fixture 原 `recv <= 0` 一律拋 `incompleteRequest`，無法區分沒有任何新 request bytes 時的正常 idle EOF、半截 header/body，或真正 recv 錯誤。依 [Apple recv(2)](https://developer-mdn.apple.com/library/archive/documentation/System/Conceptual/ManPages_iPhoneOS/man2/recv.2.html)，有序關閉會回傳 0；fixture 現在只對這種零累積 bytes EOF 回傳 no-request。半截 request 仍失敗並記錄 byte count / header-complete / expected-body-length；負值僅重試 EINTR，其餘保留 recv errno；錯誤附合成 connection number，不印私密 request。

新增四個 XCTest 方法：完整 GET 後的 idle EOF、沒有 request bytes 的 EOF、半截 header/body EOF 仍失敗、invalid socket 的 EBADF 仍失敗。socketpair 控制在讀前 shutdown writer write-half，兩端 receive/send timeout 皆檢查設定為 3 秒。沒有修改 production HTTP transport、TCP reuse、原 no-replay / release assertions 或 required gate。

此修正經獨立來源審查；本地沒有 Swift/Xcode，未宣稱 XCTest 編譯／執行通過。原失敗缺 byte/errno 診斷，不能回溯宣稱它必然是 idle EOF；新 fixture 若再失敗，需按新增 framing evidence 繼續調查。修正尚待下一個 exact-head macOS CI；原矩陣已自然完成，沒有取消或重跑原 head。


### 46e9004 原矩陣終態

CI `37750631884` 已自然結束 `failure`，唯一失敗平台為前述 iOS Core，`ci-complete` 正確失敗。iOS App/UI、Android JVM/APK 及其他適用 gate 成功。

- API34 artifact `11538943564`，SHA-256 `4637150341d6984322ebad76d4b391d437c5cde3c981cd4626403fe4c447661d`：原始 XML 356 tests / 0 failure / 0 error / 0 skip。
- API36 artifact `11539867617`，SHA-256 `30b42c5230cb3cc076b93d428313c7029674ce8024b72f87d8bd246447a07cde`：原始 XML 同為 356 / 0 / 0 / 0。
- 兩 API 的原 overlap 與新 control 皆走到原檔 bytes、frame/idle 及最終斷言。control 首次 IME observation 已是 false，皆只觀察一次，API34 為 0ms、API36 為 12ms。因此本輪沒有建立 visible→hidden 時序對照，更不能說已重現／修復早先 Compose 崩潰。

保留原 e3f84fde 崩潰、884aaa8c 診斷綠燈及本輪結果為不同證據。下一 head 只修正嚴格的 iOS fixture EOF 分類，仍需完整 CI；不能將這份 Android 通過跨 head 當作未來 run 已成功。
