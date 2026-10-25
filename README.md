# tokaRewards

Nukkit 獎勵與每日簽到插件。使用 UUID 識別玩家，金額由 tokaEconomy 入帳；不以積分或 Plan 活躍度授予權限。

## 功能與狀態

| 功能 | 說明 |
| --- | --- |
| 每日簽到、自動簽到、連續天數 | 預設停用，由 daily.enabled 開啟 |
| 跨月、跨年換日 | 依 daily.timezone 計算，預設 Asia/Taipei |
| 投票查詢與金額加成 | 選用；須自行設定 API key，不消耗投票領取狀態 |
| 可重試的獎勵入帳 | 相同 operation ID 去重，未入帳保留待領 |
| 季節活動 | 預留通用 grant API；尚無活動排程管理 |
| Plan | 選用，auto 自動接入，可指定開放欄位 |

## 安裝與設定

需要 Java 21 與相容的 Nukkit 核心。使用 tokaEconomy 的 depositOnce API；沒有可用經濟服務時獎勵會保留待領。tokaStorage 與 tokaI18n 已嵌入，無需另裝共用套件插件。

1. 將 output 的 JAR 放入 plugins，啟動產生設定。
2. 檢查貨幣代碼、時區與獎勵金額。儲存預設 sqlite，也支援 json、yaml；不能只改 backend 就當作完成資料搬遷。
3. 設定 daily.enabled 為 true 開啟簽到，再執行 /reward reload。本插件不讀取其他插件的舊資料格式。

私人 API key 僅放 VPS 設定，不要提交 GitHub。三種儲存的 I/O 在背景序列執行，快取與工作佇列都有上限；達上限拒絕新增，不丟棄舊待領紀錄。

## 指令與權限

| 指令 | 權限 |
| --- | --- |
| /reward help | 顯示目前身分可用的指令 |
| /reward status、claim、auto <on\|off> | tokaRewards.use，預設一般玩家 |
| /reward retry、reload | tokaRewards.admin，預設 OP |
| /reward grant <UUID> <namespace> <operation> <currency> <amount> | tokaRewards.admin |
| /reward plan status | tokaRewards.admin |
| /reward plan mode <auto\|enable\|disable> | tokaRewards.admin |
| /reward plan fields <all\|none\|欄位...> | tokaRewards.admin |

Plan 預設 mode: auto、fields: all；enable 在 Plan 未就緒時提示，disable 一定不發布。欄位為 streak、total、last-day、automatic、pending，不包含憑證或私人座標。`automatic` 只表示玩家是否啟用登入時自動領取；一般簽到仍是玩家按下視窗按鈕。`total` 顯示為「累計簽到次數」。Plan 自己的玩家頁面閱覽權限仍由 Plan 管理。

## 簽到視窗

玩家開啟簽到視窗時，上方顯示最後簽到日、連續天數與累計次數。若投票查詢已啟用且有加成設定，再多一行依本月票數算出的加成：已達門檻顯示「本月已投票幾次，簽到金額加成幾倍」，未達門檻顯示還差幾票。

- 已投票（或查不到投票紀錄、投票查詢未啟用）：只有一顆「簽到」按鈕，按下即簽到。
- 還沒投票：多一段提醒與投票網址（有設定時），並有兩顆按鈕。「簽到」直接簽到；「前往投票後，再來簽到」開啟投票與簽到資訊頁：如何投票、今天的票與本月票數、加成規則、簽到數字，以及登入時自動簽到的開關。

## API 與安全重試

Main 提供 importProfilesJson、profileJson、grant、planStatusJson、configurePlan。匯入只允許 daily 停用時執行，已有資料必須完全一致，衝突不覆寫。匯入格式與 public 方法請見 Main.java、RewardsService.java；回傳 CompletableFuture 的操作為背景工作，操作遊戲世界請切回主執行緒。

同一獎勵必須沿用相同 namespace、operation ID、貨幣及金額；不要在重試時產生新 ID。發獎前先持久化待領紀錄，tokaEconomy 確認入帳後才標為完成。中途重啟會重試原 ID，避免重複入帳。經濟收據隨帳戶保留，不可降版至不支援收據的版本。

## 編譯與驗證

執行 build.ps1。產物在本專案 output 與 ../plugin-modernization/output；這些產物不進版控。測試涵蓋跨月跨年、同日重複領取、三種後端、重啟重試、入帳後標記失敗及匯入衝突。

