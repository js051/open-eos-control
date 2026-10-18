# 原未完列的公開契約核對（2026-10-18）

這是官方文件與既有程式的有限核對，沒有執行真相機命令、取得 SDK 套件、接受條款、安裝工具或變更權限。公開 API 的存在、程式實作、原生編譯及真機驗收是不同證據；本文件不宣稱三平台對齊完成。

## #129：iOS USB/PTP

Apple 的 [ImageCaptureCore](https://developer.apple.com/documentation/imagecapturecore) 確有原生 iOS/iPadOS 相機入口，不限 macOS／Catalyst。completion-based [PTP command](https://developer.apple.com/documentation/imagecapturecore/iccameradevice/requestsendptpcommand(_:outdata:completion:)) 與 [PTP event handler](https://developer.apple.com/documentation/imagecapturecore/iccameradevice/ptpeventhandler) 自 iOS 13 提供；明確 control/contents authorization API 自 14 提供；browser type mask 自 15.2 提供。EOS 的 iOS 17 target 足夠滿足這些文件版本要求，仍未建立原生編譯／裝置成功證據。

目前 app 僅有 CCAPI／Bridge session；新增直連路徑須有獨立 adapter，不能把 PTP 偽裝成 HTTP 成功。Apple framework session 也不能直接套用 Android 原始 USB session 的所有權假設。選定的 camera object、session generation、每次請求與事件須保持精確關聯；拔除、取消、晚到完成不能更新另一台相機或新 session。

新的外接相機路徑需要 truthful localized `NSCameraUsageDescription`；這不是現有網路 app 的缺陷，也不應預先啟用 dormant permission。macOS USB sandbox entitlement 不能當成 iOS entitlement。授權 prompt 順序、封包／transaction ID、雙 completion buffer 語義、取消與 close 是否真正完成，需要 SDK／裝置驗證；timeout 不等於相機操作取消。

最小程式邊界是 isolated port／純狀態機與 fake tests，之後才有原生 discovery → 精確選定 → 明確授權 → open/ready → read-only DeviceInfo → close。此初始邊界不包含 AF、Bulb、REC、刪除或其他寫入，也不直接開啟產品入口。

## #126：EDSDK

Canon [官方頁](https://industrial.imaging.canon.de/integration/sdk/) 已公開連結 EDSDK 13.20.11 API reference，包含函式、生命週期、事件及錯誤。先前「沒有公開 API 契約」的描述已更正。官方支援 Linux x64，但文件列出的 x64 環境是 Ubuntu 20.04，不能據此宣稱 Debian 雲端相容。公開文件版本也不等於 [SDK 清單](https://asia.canon/en/campaign/developerresources/sdk) 上較新的 13.20.21。

剩餘工作是合法匹配的 headers/runtime、適用授權與 provider 分發模式、native ABI、實作及裝置驗收。公開文件本身不授予再分發權；既有 SDK-neutral loader/fake 也不是 Canon SDK 實作。詳見 [provider 文件](../edsdk-provider.md)。

## #78：direct CCAPI upload

[Canon CAP 公開介紹](https://asia.canon/en/campaign/developerresources/camera/cap) 描述相機向裝置傳輸；[release notes](https://asia.canon/en/campaign/developerresources/camera/cap/camera-control-api-release-note) 的建目錄、檔名設定、XMP 修改，不建立 binary file upload 契約。

本次沒有取得 authoritative method/path、upload 專屬 advertised capability、body framing/limits、commit receipt/readback。direct CCAPI 因此保持 unavailable；既有 gphoto upload 是另一條路徑。這不等於斷言所有授權文件或機種都沒有上傳功能。

## #54/#55：gphoto 即時睡眠／清潔

[libgphoto2 官方 config 實作](https://github.com/gphoto/libgphoto2/blob/92171a382f185f617e95cb9acecf2d43688b19ab/camlibs/ptp2/config.c) 的 Canon `autopoweroff` 是 integer 設定，不能推定立即睡眠；generic `powerdown` 是另一種關機動作，不能推定具備可確認的睡眠／喚醒契約。`cleansensor` 是 Nikon 開關機清潔政策，不能用作 Canon clean-now。

Canon RequestSensorCleaning 的命名常數不提供完整參數、前置條件、完成／恢復保證。沒有證據支持以 raw opcode、mirror lock、UI lock 或 USB reset 替代。既有 unsupported 保持；要實作需先取得可廣告且 typed 的操作、admission、outcome 與 recovery 契約。

## #56、#71/#72/#114/#115 的邊界

- #56：public C API 的逐檔 protection setter 不等於現有 gphoto CLI 有 writer；rating metadata 路徑缺 descriptor／型別範圍與逐 setter 結果保證；camera-wide autorotation 不是逐檔 rotation；下載後清 archive flag 不是雙向 archive API。維持現有 unsupported，不新增 native helper 或以相機全域設定冒充逐檔。
- #71/#72/#114/#115：[上游 PR #1299](https://github.com/gphoto/libgphoto2/pull/1299) 新增 raw `eostouchafposition=TYPE,X,Y`，故不能說完全沒有 CLI 座標 writer。但 TYPE、座標範圍／原點、裁切方向與即時 frame geometry／readback 尚不足以承接 normalized UI。Click WB 命名 opcode 亦未建立 typed CLI 座標與結果契約。不得只因命令名稱存在便開啟 capability。

## 驗收與發布

本文件僅校正研究邊界。沒有新硬體、Android/iOS native、Xcode、SDK ABI 或發布包驗收。Release Assessment：none；不變更版本、不建立發布、不把仍缺失的真機證據改為通過。
