# Camera Import Contract V1

[English](README.md)

此 artifact 是 Open EOS Control 與相簿／編修 App 之間不綁 transport 的邊界。Open EOS Control 擁有相機 session、能力偵測、媒體列舉、各種 representation 與傳輸完整性證據；消費端擁有 staging、內容雜湊、Catalog 原子提交、非破壞編修、匯出及長期相簿管理。

## 線上契約

- Wire version `1.0` 採嚴格驗證。遇到不支援的 major／minor、缺少必要欄位或未知欄位都必須 fail closed。
- 所有 ID 都是不透明且已去識別的值；需要時只在當次 session 有效。不得含 Canon URL、PTP opcode、USB endpoint、相機路徑、credential、序號、email 或 IP。
- `openRepresentation(media_id, representation, target_size?)` 接受 `representation-request.schema.json`，並回傳短生命週期的 native readable source；平台特定 handle 不得持久化。
- Receipt 只記錄匯入結果，不授權刪除或修改相機媒體。
- filename、byte length、拍攝時間與 media ID 只能用來找重複候選。精確重複必須依可信的完整強雜湊，或由消費端完整接收後計算 SHA-256。

## 檔案

- `media-descriptor.schema.json`：媒體身份、來源、群組提示與可取得的 representations。
- `representation-request.schema.json`：只要求已公告 representation 與可選 preview 尺寸上限的 fail-closed request。
- `transfer-event.schema.json`：可恢復傳輸進度與終態完整性證據。
- `import-receipt.schema.json`：原子匯入或確認精確重複後的消費端回執。
- `compatibility.json`：fail-closed 相容政策。
- `semantic-rules.json`：所有實作必須共同遵守的跨欄位與信任規則。
- `fixtures/valid`、`fixtures/invalid`：producer／consumer 可共同使用的符合性案例。
- `contract-lock.json`：所有契約來源檔案的 SHA-256 manifest。

JSON Schema 使用 Draft 2020-12。時間必須是含明確 offset 的 RFC 3339；`orientation` 使用 1 到 8 的 EXIF orientation 整數，不能取代 original 中保留的 metadata。

JSON Schema 負責驗證單份文件結構。Draft 2020-12 無法表示的跨欄位數值相等與順序規則會列在 `semantic-rules.json`，consumer 必須實作；repo 內的 Python validator 與嚴格 Kotlin codec 會用共同 fixtures 驗證這些規則。`preserved_representation_ids` 是消費端為已原子保存 blob 產生的 ID，不是相機來源 representation ID，也不提供任何修改來源的權限。

Kotlin JAR 以 JVM 17 為目標並優先服務 Android；嚴格 JSON codec 使用 Android 平台內建的 `org.json`。非 Android JVM consumer 需自行提供相容的 `org.json` 實作。
