# PC local preview 的最後影格時間

原 G07/#123、G02 diagnostics。基準 ab607b0a24da2a6c92487ec252e43b386acb37e4 的 production handleLocalVideoFrame 在沒有 requestVideoFrameCallback、使用 timer fallback 時，固定 counter、0 與 unknown 仍每次刷新 lastFrameAt。這把輪詢／重繪時刻誤報成來源有新影格。

Production handler＋實際 local-video helper 的基準 2 pass／5 fail。固定只有 native presented callback 或有效 counter 增加才更新 lastFrameAt；未知 sample 不清除獨立最後已知 counter，Start／Stop 重設它。新影格在 render throttling 期間仍保留其觀察時刻。FPS window、pixel render cadence 與監看設定重繪不改。

完整 modules 3835 pass、static UI 15/15、syntax/diff clean。新 11 個 freshness cases 加既有 ownership 與一個 lifecycle reset case，獨立窄審無 blocker。[原始驗證輸出](evidence/pc-local-frame-timestamp-2026-10-17/)保留。

這是 production-handler host 反例與修正，沒有聲稱 cloud Firefox 實際缺少 requestVideoFrameCallback，也沒有使用實際 UVC／HDMI、GPU 呈現或手機驗收。unknown→same counter 是 helper 邊界測試，非已觀測硬體暫失。lastFrameAt 只反映軟體可觀察的新影格證據，不能證明感測器曝光時刻。
