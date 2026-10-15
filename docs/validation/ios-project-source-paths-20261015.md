# iOS XcodeGen 外部來源路徑修正

日期：2026-10-15。此批建立於已封存的 `8648979da8dd64957afa34208b475206e62ae8a3`，與素材類型／JPEG metadata 守門批次分開。

## 既有失敗與根因判讀

既有 iOS App／UI job `116270730682` 的 log 顯示 XcodeGen **2.46.0**。PR head 為 `9ed86312c93eedadc390102bebcd4da188806f90`；runner 實際 checkout 的合成 merge 為 `f3afed8`。

XcodeGen 生成及 App build 已經前進，05:58:29 UTC 的測試編譯卻找不到：

`$CHECKOUT/ios/open-eos-control/ios/OpenEOSCore/Tests/OpenEOSCoreTests/URLSessionPendingBulbStopTests.swift`

正確來源是 `$CHECKOUT/ios/OpenEOSCore/Tests/OpenEOSCoreTests/URLSessionPendingBulbStopTests.swift`，檔案實際存在。這是生成的 source reference 多出祖先路徑，並非缺少測試檔或需要把測試排除。

`project.yml` 啟用了全域 `createIntermediateGroups: true`，且同時引用 `../../contracts/...` 及 `../OpenEOSCore/...` 外部單檔。依該版 [SourceGenerator 實作](https://raw.githubusercontent.com/yonaskolb/XcodeGen/2.46.0/Sources/XcodeGenKit/SourceGenerator.swift) 的祖先群組快取、遞迴掛接及相對路徑重算流程，交錯的外部祖先群組是與症狀相符的根因判讀。這是 log 加來源分析，尚非本機原生重現。

## 最小修正

- 保留全域值及所有內部 App／Tests／UITests 群組。
- 僅對五個契約 JSON 與一個共用 Swift 測試 source 加上 `createIntermediateGroups: false`；此覆寫由 [2.46.0 Target Source 規格](https://raw.githubusercontent.com/yonaskolb/XcodeGen/2.46.0/Docs/ProjectSpec.md#target-source) 支援。
- 保留六個原始相對路徑、五個 resources phase、Swift 推定 Compile Sources phase、所有 target／scheme、Fixtures 排除及 UI MP4 資源。沒有複製測試、設為 optional、刪除測試或改動 workflow。
- 局部覆寫使外部檔案的父群組直接使用完整 project-relative 路徑，避開外部祖先遞迴；本機 package 的 source-root reference 不會重建這條祖先鏈。

## 本地驗證與限制

新增 `scripts/validation/tests/test_ios_project_source_paths.py` 四個標準庫來源回歸，檢查六個外部 source 的精確集合、覆寫、必選納入、phase、所有十一個來源的存在／範圍／唯一性，以及原有 App／UI／共用 Swift 測試接線。

- 修正前：四個來源測試中的六個 subtest 如預期失敗，分別缺少六個覆寫。
- 修正後：四個來源測試通過。
- `scripts/ci/tests`、`scripts/validation/tests`、`scripts/contracts/tests` 合併：**105 passed**；一個既有 `test_export_ios_ui_evidence.py::test_tree` 回傳 dict 的 pytest warning。
- bridge 工作目錄的 Ruff 及 `git diff --check`：通過。
- 獨立來源設計審查：支持六個局部覆寫，未發現阻擋項。這不代替生成的 PBX project 驗證。
- **NOT_RUN**：XcodeGen 生成、Swift／Xcode 編譯、XCTest、iOS App／UI 與實機。此環境沒有 `swift`、`swiftc`、`xcodegen`、`xcodebuild`；未安裝工具，也沒有新建或重跑 CI。

上述先紅後綠只證明來源接線規則，不能稱為原生 build 已修復。下一次已授權的正常 CI 必須確認生成路徑正確、App／UI 真正通過；此前狀態為 **implemented**。

## Release Assessment

- 最新已核實發布基準：**v0.12.0 Development Preview**（2026-10-15 03:54:34 UTC 核實）；0.13.0 是 repo 開發版本。
- 本獨立變更影響：**none**，僅修正測試專案生成及來源回歸，沒有可分發產品行為變動。
- 尚待證據：修正後 XcodeGen／原生 App／UI 的實際結果；不宣稱新增相機或傳輸相容性。
