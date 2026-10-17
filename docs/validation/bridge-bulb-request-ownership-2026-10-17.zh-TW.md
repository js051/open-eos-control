# Bridge Bulb request ownership 修正驗證

日期：2026-10-17 UTC。基底：`244da016`。

## 問題與範圍

既有 PC Bulb Start 等待可能不結束；只替 PC 加 timeout，接著使用舊 Stop
路由，仍有 HTTP Stop 超車尚未入場 Start 的風險。單純 provider session lock
只能序列化已抵達的工作，無法阻止晚到 Start 在 Stop 成功後才按下快門。

本批只修正 Bridge 的 request admission／ordering。新增 owned Start／Stop
路由與每個已註冊 session 物件的 owner guard；保留舊無 payload 路由、原
CameraStatus 格式，以及 EDSDK 等 provider 的零參數 Start／Stop 方法。
沒有修改 PC `app.js`、gphoto2 provider、REC 或 held AF 契約。

PC caller 的有界等待、Stop 可達性及瀏覽器交錯驗證屬於配對的 PC 批次；本文件
不宣稱它們已由這個 server commit 完成。

## 修正保證

- Stop 先到或 Start 尚在執行鎖前排隊：保存取消 owner，晚到 Start 不執行 provider。
- Start 已 dispatch：同 owner Stop 必須等待 Start，再呼叫原 provider Stop。
- owner 最多 dispatch 一次；遺失或不明 Start／Stop 回應不會自動重送 Start。
- 初次 released 必須在 provider Stop 返回後取得兩個 literal false：
  `bulbExposureActive` 與 `shutterReleaseUnconfirmed`。
- provider Stop 成功後的 status tail 若失敗，保留同 owner，必須 explicit Stop
  重試；普通 GET status 不能解除 owned request duty。
- 舊 completed owner 與未知 owner Stop 不會碰新 owner 或 legacy 操作。
- 每 session 最多 256 owner；從不淘汰取消／完成記錄。滿額後拒絕所有新 Start，
  但既有 owner Stop／重試仍可用，因此最舊取消 owner 不會復活。
- legacy 與 owned Bulb 共用 execution serialization；新 owned Start 不能接管
  未解決的 legacy 操作，legacy Start 也不能繞過 active owned duty。
- delete、shutdown、local maintenance retirement 在 provider cleanup 前先封鎖
  queued Start，且 cleanup 完成前保留相機 reservation。
- local maintenance retirement 若被 provider 拒絕，原 session 的 Stop 恢復可用；
  已排隊的舊 Start 仍被 retirement revision／tombstone 阻止。
- 不把 provider I/O 或執行鎖等待放在 SessionManager 全域 lock 裡。

新 PC 必須使用 additive `/bulb/start/owned`、`/bulb/stop/owned`，驗證 ownerId
與 outcome。舊 Bridge 沒有 owned 路由時必須顯示相容性問題，不能 fallback 舊
Start。`cancelled-before-start` 只證明該 owner 不會 dispatch，不能顯示實體停止。

## Red／fixed

先新增回歸測試，再執行未修正基底：

```
tests/test_bulb_request_ownership.py::test_stop_before_start_prevents_late_provider_press
FAILED: expected owned Stop receipt, received HTTP 404 {"detail":"Not Found"}
```

修正後執行：

```
python -m pytest -o addopts='' -q \
  tests/test_bulb_request_ownership.py tests/test_session_shutdown.py \
  tests/test_edsdk.py tests/test_api.py tests/test_maintenance_lifecycle.py \
  tests/test_shutter_autofocus.py tests/test_recording_lifecycle.py \
  tests/test_ccapi_bulb_recovery.py
```

結果：**425 passed**，沒有 deselected。先前保守排除本機 HTTP peer 的批次為
387 passed／7 deselected；確認本機 synthetic peer 屬於既有驗證範圍後，已完整
補跑 REC peer 與既有 CCAPI Bulb recovery，另增加兩個 owned route＋CCAPI
production transport 的本機 peer cases。全部使用合成 provider／transport、
in-process ASGI 或 loopback peer，沒有外部網路或真相機依賴。

原有 FastAPI／Starlette TestClient deprecation 與媒體 GET／HEAD duplicate
OpenAPI operation ID 兩項 warning 保留，未安裝或升級套件，也未修改無關路由。

新增 54 個 owner tests 覆蓋停止超車、已 dispatch 排序、重複／併發 Stop、Start replay、
15 秒執行鎖 deadline 的縮短測試、literal Boolean proof、lost ACK／status tail、
256 owner 上限、零參數 legacy provider、invalid/auth rejection、同 ID replacement、
三種 retirement、失敗 maintenance-retire、shutdown 交錯，以及 cleanup reservation。
本機 CCAPI peer 另證明取消 owner 沒有送出 full_press；release 已完成但 status tail
失敗後，即使普通 GET 回報 idle，仍需同 owner explicit Stop retry，而相機 peer
command trace 始終只有一次 full_press 與一次 release。

所有變動 Python 檔案的 Ruff 檢查與 `git diff --check` 通過。

## 限制與交付狀態

這是原有 Bulb 操作的 backward-compatible patch 修正，不新增相機功能／transport。
版本未變更；未推送、未建立 PR、未執行 CI、未合併或發布。

上述證據是 deterministic protocol／fixture 驗證，沒有實體相機、USB 或 EDSDK
硬體驗證。execution lock 等待上限並非相機停止保證；若 provider 永不返回，Stop
只能保持未確認並允許重試。gphoto2 保存 release 的修正由另一批處理，須在整合
後重新跑相關交叉測試。
