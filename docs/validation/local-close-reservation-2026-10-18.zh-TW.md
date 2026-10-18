# Bridge 本機 USB close 期間的 admission 保留

日期：2026-10-18 UTC。起點：`7d5f994a`。狀態：implemented，尚未推送、執行遠端 CI 或發布。

## 問題與修正

`DELETE /v1/session/{id}` 與 `close_all()` 會先移除 session 註冊，再執行可能仍在進行的實體 close。原本跨引擎檢查只讀 `_sessions`，因此保留下來的 camera reservation 沒有阻止另一個本機引擎提早開啟。

修正使用現有 admission lock 排序「已開始的 open/discovery」與「公布 retirement」：

- 已取得 admission 的 open 必須完成註冊，retirement 才能公布。
- retirement 公布後保留 camera reservation；新的本機 open、AUTO 選機探測及 `/v1/cameras` 在 provider callback 之前回覆 `CAMERA_BUSY`。
- close 的實體清理與每個 session 的 retirement lock 等待期間不持有 admission lock 或 manager metadata lock。
- delete 的順序為 session retirement → admission → manager metadata；shutdown 為 admission → manager metadata，離開後才等待 session retirement。
- 保留原有 active 同引擎多相機、明確 camera ID、AUTO/default 選機、原 session 的 owned Stop，以及獨立 CCAPI 建立與刪除。
- retirement gate 比對 reservation 的 token 與 engine/camera key；獨立 CCAPI 重用 token 不會解除舊本機 reservation。

未新增公開 API、provider protocol、清理重試或輪詢。

## Red 證據

在 `7d5f994a` 使用真實 FastAPI 路由、合成 provider 與 event 控制 close：

1. libgphoto2 的 DELETE 仍在等待 close 時，EDSDK POST 回覆 201，且 `edsdk.open` 已執行一次。
2. delete/shutdown × libgphoto2/edsdk 的四個 regression cases 都因得到 201 而非 409 失敗。
3. 明確要求相同引擎與相同 camera ID 時，雖回覆 409，仍先執行一次 `provider.open`。
4. AUTO 指定另一個引擎的 camera ID 時，兩個引擎的 health/discover 各執行一次，隨後 open 並回覆 201。
5. 最初僅以 token 是否存在判斷 retirement 的修正，會被 CCAPI 重用該 token 穿透；合成重用測試再次得到 201，現已修正為 token 與 camera key 同時匹配。

## 最終本地驗證

既有驗證環境，無安裝、SDK 或相機連線：

```text
PYTHONPATH=bridge python -m pytest \
  bridge/tests/test_local_close_reservation.py \
  bridge/tests/test_bulb_request_ownership.py \
  bridge/tests/test_session_shutdown.py \
  bridge/tests/test_edsdk.py -q -p no:cacheprovider
```

結果：86 passed。新的 9 個 cases 涵蓋雙向引擎、delete/shutdown、相同 camera、AUTO、direct discovery、token 重用、原 session owned Stop、CCAPI 獨立性、多相機、admission/retirement 前後順序，以及 shutdown 與正在等待的 DELETE 不互鎖。

修改檔案的 Ruff 檢查及 `git diff --check` 通過。保留既有 Starlette/httpx deprecation warning，未安裝或替換套件。這是 protocol/mock fixture 證據，不是實體相機驗證，也不是全專案或遠端 CI 通過。

## 明確非目標與剩餘邊界

- 正常 close 拋錯之後的 quarantine/recovery 尚未變更；manager 現有 finally 仍釋放 reservation。
- 不能把「再次呼叫 close 未拋錯」當作實體 release 證據：`GPhoto2Session.close()` 可能先設定 `_closed = True`，再拋出先前保存的 release error；再次呼叫會在 `_closed` 判斷直接返回。
- 尚未處理仍 active 的相同 camera 重複 open 在 `_register` 拒絕前的 provider side effect。此修正只封鎖 retirement 已公布或與 admission 交錯的 close 期間。
- 不宣稱 native close 有時間上界；這次只保留清理完成前的 admission 隔離。
- 拒絕的 EDSDK open 所留下的 pending-cleanup quarantine 為獨立修正；整合時仍需保留兩邊的 admission/cleanup guard。

產品影響：既有行為的向後相容修正，若納入發布屬 patch。未變更版本、main、tag、release、安全檢查或驗收要求；實體裝置狀態仍為未驗證。
