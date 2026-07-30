# Android Development Signing

[繁體中文](#繁體中文) | [English](#english)

## 繁體中文

Open EOS Control 從 `main` 與 tag release 發布的 Android 開發 APK 使用同一個固定簽章，讓 Android 可以將後續開發預覽版當作更新安裝。

- Alias：`open-eos-control-development-v1`
- 憑證 SHA-256：`c2d4ad020b223e4d854f17f41aa6fcde5177b57dacc7be70b9f2f2a07f7cfdba`
- 第一個固定簽章版本：`0.1.5`

PKCS#12 私鑰與密碼只保存在 GitHub Actions Secrets 及 repo 之外的受限本機備份。不得將它們提交到 Git、印在 log、放入 artifact 或診斷報告。Workflow 只會將 keystore 還原至 `RUNNER_TEMP`，並在上傳前驗證 APK 只有一位 signer 且指紋相符。

Pull request 與一般本機建置在沒有完整提供四個 `OEC_ANDROID_SIGNING_*` 環境值時，繼續使用 Android SDK debug 憑證。這些 APK 是測試輸出，無法覆蓋更新公開的開發預覽版。

`0.1.4` 與更早版本使用 GitHub runner 臨時 debug key，因此安裝 `0.1.5` 時可能需要最後一次解除安裝。從 `0.1.5` 開始，使用此簽章的後續開發版可以直接更新。若私鑰遺失或在沒有 Android signing lineage 的情況下更換，使用者將再次需要重新安裝，所以 repo 外備份是發行基礎設施的一部分。

## English

Open EOS Control development APKs published from `main` and tagged releases use one stable signing identity so Android can install future development previews as updates.

- Alias: `open-eos-control-development-v1`
- Certificate SHA-256: `c2d4ad020b223e4d854f17f41aa6fcde5177b57dacc7be70b9f2f2a07f7cfdba`
- First stable-signed version: `0.1.5`

The private PKCS#12 keystore and passwords are stored only as GitHub Actions secrets and in a restricted local backup outside the repository. They must never be committed, printed in logs, included in artifacts, or placed in diagnostic reports. The workflows decode the keystore only into `RUNNER_TEMP`, use it for the build, and verify that the resulting APK has exactly one signer with the pinned public fingerprint before upload.

Pull-request and ordinary local builds continue to use the Android SDK debug certificate unless all four `OEC_ANDROID_SIGNING_*` environment values are explicitly supplied. Those APKs are test outputs and are not update-compatible with published development previews.

Versions `0.1.4` and earlier were signed by ephemeral GitHub runner debug keys and may require one uninstall before installing `0.1.5`. Starting with `0.1.5`, later development previews signed by this identity can update it in place. Losing or rotating this key without an Android signing lineage would require another reinstall, so the external backup is part of the release infrastructure.
