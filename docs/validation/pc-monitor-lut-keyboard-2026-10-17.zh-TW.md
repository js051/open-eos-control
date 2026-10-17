# PC LUT 控制消失後的鍵盤接續

原 G07/#62、G13/#122。Firefox baseline產品`7711a6dff2650f1d1569fc910c6a42c623c19326`已以原生picker載入自有identity.cube，Tab到移除、Enter後按鈕隱藏而焦點掉BODY；再按Enter無法接續載入。21:17:08 terminal，3圖與native焦點事件保留。

修正僅於renderMonitoringControls將目前focused remove/cancel隱藏時，仍同開啟dialog、沒有其他dialog或新焦點，且Load仍連接/可見/可用，才將焦點回Load。保留LUT資料、匯入queue、舊read retirement與錯誤語意。

production handler＋模擬DOM焦點eviction：基準54 pass／3 fail，固定58/58，涵蓋remove、cancel、read settlement與closed/new focus/detached/disabled/hidden/no rect/other dialog隔離。模擬DOM反例與真Firefox基準分開，不宣稱screen reader、GPU色彩或手機驗收。獨立窄審無blocker，另補同步blur期間新焦點不搶回。完整回歸與固定版Firefox另記。
