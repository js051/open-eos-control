# PC 上傳晚收據與明確預覽接續

原 G10/#78、G08/#75、G13/#122。產品 source `0654a99736a83fdd939a2cff9d74881f087076ca`；與 fixture 預備時 `5b5cc7af` 的 bridge tree相同。

真雲端 Firefox、原生 file picker選取自有25-byte合成檔e.jpg，production介面送出一次上傳。取消等待後顯示結果不明、不宣稱回滾；使用者明確開啟既有SYNTHETIC.JPG預覽後，fixture延後釋放同write的201匹配收據。結果轉confirmed；新listing故意回空，預覽仍保持圖像與同標題。最後僅明確Escape才關窗，收據保留。

[四張圖片、native事件與terminal收據](evidence/pc-upload-preview-continuation-2026-10-17/assessment.json)；21:08:36 terminal。第一次長路徑輸入timeout的嘗試沒有觀察到dispatch，保持inconclusive，未算通過或產品bug。

本次是原接續修正的缺少GUI證據補齊，沒有新產品修改，也沒有重跑未變的綠燈測試作為新修正。network fixture刻意在Abort後仍回late receipt，驗證UI ownership，不表示真Fetch或相機有此取消行為；不驗證direct CCAPI upload、真相機、Android/iOS或原89項全過。
