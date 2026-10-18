# Bridge active 相機的精確重複 admission

日期：2026-10-18 UTC。這是 local-close-reservation 修正之後的獨立小批次；不重新交付前批 staged 檔案。

## 範圍與行為

新增一個 private preflight helper 與一個呼叫點。在既有 admission lock 內，先維持原本的 engine resolution 與跨引擎檢查，再於 `local_engines.open` 前檢查：

- 明確提供 cameraId，且 resolved engine + cameraId 精確命中現有 reservation，立即回覆原有 `CAMERA_BUSY`/409。
- 僅限解析後為 libgphoto2：其 `GPhoto2Engine.open` 明確接受 descriptor.port 作 cameraId alias，因此也檢查已註冊同引擎 descriptor 的完全相同 port，並要求 canonical reservation 與 owner 相符。不為這個檢查加入 discovery。
- 不將 EDSDK 的 port 解讀為相機 alias，不推測未提供 cameraId 時的 default 選擇。
- AUTO 原本的 health/discover resolution 保持不變，可能在 preflight 之前執行。此修正阻止已知相機的第二次 open，不宣稱阻止 active 狀態下 AUTO 的所有探測。
- 原 `_register` 的 definitive identity 檢查保留，仍負責 provider 回傳身分、其他 alias/default 與實際 reservation。

## Red → Green

真實 FastAPI 路由搭配合成 provider：明確 engine／AUTO × libgphoto2／EDSDK 的四個 cases，原本第二次 POST 雖回覆 409，provider.open 累計已呼叫兩次。新增 preflight 後，累計僅一次，原 active session 不被關閉。

真實 GPhoto2Engine 搭配既有 FakeRunner：以第一次回應的 raw port 進行重複 POST，明確 libgphoto2 與 AUTO 的兩個 cases 原本也呼叫 open 兩次；修正後累計一次。AUTO 既有的一次 discovery 保留，明確 engine 的第二次請求則不新增 discovery。

相容測試保留不同明確 ID、同引擎多相機與 provider default 選擇；也特別令 EDSDK port 文字等於另一個選定 cameraId，確認不會錯誤套用 libgphoto2 的 alias 規則。

```text
PYTHONPATH=bridge python -m pytest \
  bridge/tests/test_local_duplicate_admission.py \
  bridge/tests/test_local_close_reservation.py \
  bridge/tests/test_edsdk.py -q -p no:cacheprovider
```

最終結果：29 passed（本批 8、前批 close reservation 9、EDSDK 12）。修改檔案的 Ruff 與 `git diff --check` 通過。無安裝、SDK、實體相機或遠端 CI；既有 Starlette/httpx deprecation warning 未改動。

## 交付與界線

生產碼 delta 僅 helper + call，patch 相對前批 frozen sessions.py 產生。前批 staged 原件仍保留。本批未 commit/push、未改版本、未發版。產品影響屬向後相容 patch。

未變更正常 close 失敗後的 recovery，也沒有新增 generic close retry。未宣稱 default/未知 alias 可以在 provider 回傳前識別；這些仍由原 `_register` 處理。mock/protocol 證據不代表實體裝置驗證。

## 合併驗證 2026-10-18 04:04 UTC

已併入 rejected-owner cleanup 與 normal retirement guard，保留兩者原有 preflight。
合成 rejected-owner／normal retirement 交互案例改用另一個明確 requested camera ID，
避免被本次正確的 active duplicate guard 提早拒絕。

整合初次 patch 因既有註解 context 不同未套入，該輪實際結果為 6 failed、105 passed：
四個 canonical ID 與兩個 gphoto raw-port duplicate 均重現第二次 provider.open。
套用精確 helper/call 後，相同六個 test modules 結果為 **111 passed**、一個既有
Starlette/httpx warning（8.79 秒），Ruff 與 diff check 通過。
模組為 test_local_duplicate_admission、test_edsdk_failed_cleanup、
test_local_close_reservation、test_edsdk、test_bulb_request_ownership、
test_session_shutdown，共六個 modules。
