# libgphoto2 半按與獨立 AF 停止責任（2026-10-17）

對應原89列 G05／#66、#69、#118。基準 aeab265e，沿用前批已修好的 Bulb 停止責任，不新增相機操作。

## 可重現缺口

- 半按 Press Half 已套用但回應遺失，舊程式 pressed 仍 false，finally 完全不送 Release Half。
- 獨立 autofocus 的 cancel 失敗，舊程式沒有保存可重試的 stop。兩種情況 status 仍呈現 shutterReleaseUnconfirmed=false，後續既有 /bulb/stop 不送對應停止命令。
- 以上均由 production GPhoto2Engine＋合成 FakeRunner 故障注入重現，不是實體對焦／曝光結果。

## 修正

在首次 press／AF drive 前保存 immutable stop path/value及原feature。即使起始回應失敗仍嘗試一次停止；停止失敗時保留 SHUTTER_RELEASE_UNCONFIRMED，既有 Stop與close只重送原停止，不重新探索或重送press。

半按／AF未知結果不冒稱Bulb曝光：bulbExposureActive仍false，錯誤feature分別為SHUTTER_HALF_PRESS／AUTOFOCUS，訊息說明快門或AF可能仍作用。事件相機I/O暫停，新寫入沿用既有stop-only interlock；已承認的Bulb也不能被另一短控制覆寫停止責任。成功停止之後的status讀取失敗不重送已收到回應的停止命令。未知起始的清理不補造observed成功操作。

## 驗證

新增 bridge/tests/test_gphoto_short_control_recovery.py 共30案：最終基準23 failed／7 passed，修正30 passed。初版ASGI測試把半按route誤寫為/shutter/half造成2個404，已依正式/shutter/half-press修正測試，沒有改API路由。

包含兩種控制的lost press、cleanup失敗、lost stop ACK、能力descriptor變動、close／API DELETE、replacement隔離、事件暫停、相鄰寫入gate、hold例外、status tail及正常明確重試。相鄰既有Bulb／gphoto／REC／maintenance／shutdown／API連同新增案例，共441 passed，既有Starlette deprecation warning一項。

使用既有驗證環境，未安裝／升級依賴。未跑原生gphoto CLI、真機／USB、Android／iOS UI；沒有真AF停止或機型相容性宣稱。PC pending Start的owned-request順序修正另批進行，本文件不宣稱涵蓋它。
