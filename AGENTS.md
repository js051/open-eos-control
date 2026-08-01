# Repository Security

- Never commit credentials, authorization headers, signing keys, certificates, `.env` files, or machine-local configuration.
- Treat camera serials, diagnostic reports, personal email addresses, user-home paths, and device identifiers as private data. Use obvious synthetic values in fixtures.
- Do not bypass Git hooks with `--no-verify`, weaken `.gitleaks.toml`, or add Gitleaks allowlist entries without explicit owner approval.
- Before pushing, allow the configured `pre-push` hook to scan every outgoing commit. A failed or unavailable scan must stop the push.
- Keep release signing material outside this repository. Public CI may build unsigned or debug artifacts only.

# Workspace Layout

- Treat `C:\dev` as a container for independent project roots only.
- Keep every Open EOS Control worktree, research file, helper tool, log, build environment, and temporary artifact under `C:\dev\open-eos-control`.
- Create additional Git worktrees under `C:\dev\open-eos-control\.codex\worktrees`; never create sibling `open-eos-control-*` directories directly under `C:\dev`.
