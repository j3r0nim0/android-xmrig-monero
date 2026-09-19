# Anvil TODO

## Done (v0.2.0)

- [x] Rename launcher / display name to **Anvil**
- [x] Operator UX 1–17 (Wi-Fi only, quiet hours, no-internet grace, MO verify, worker id, QR, paste/copy, subaddress tip, harm sheet, 100% warning, live settings, re-benchmark, battery meter, pause banner, notif actions, About, GitHub update check)
- [x] Mine / Wallet / Settings tabs
- [x] Wallet Save / Change Address (VoltMiner flow)

## Next — copy later (20–25)

These were the leftover VoltMiner miner features, without the game.

### 20. Hashrate 10s and 60s, accepted/rejected bigger

**Now:** Mine shows one number (`hashrate10s`). Accepted/rejected are two small cards that only appear while running. `hashrate60s` is already in `MiningState` and unused.

**Change:**
- Hero number = 60s if > 0, else 10s (same as VoltMiner — 60s is the number people compare).
- Sub-line: `10s  xxx H/s · 60s  yyy H/s` so the jumpiness of 10s is visible.
- Keep accepted/rejected on Mine even when idle (last session, or `0`). Bigger type, same row as now.

### 21. Session totals on-device

**Now:** accepted / rejected / uptime reset when XMRig stops. Nothing survives a kill.

**Not this:** VoltMiner’s Firestore mining-day streak. No cloud, no clan gate.

**Change:** SharedPreferences, this device only.
- Per session: hashes (10s × time is fine), accepted, rejected, duration, ended-at.
- Lifetime: sessions, hashes, accepted. Show under the log or on Settings — one quiet block, not a dashboard.
- Wipe with uninstall (same as wallet). No export.

### 22. Share a stats image — not clan rank

**Now:** nothing to share.

**Not this:** VoltMiner flex card (clan name, rank, flag, download QR).

**Change:** one PNG from Mine (share icon while running or after a session):
- Anvil, current or last H/s (60s), worker `AX-…`, **0% fee**, pool `gulf.moneroocean.stream` TLS
- Optional truncated wallet (`4…xxxx`)
- Link: GitHub Releases, not voltminer.net
- System share sheet only. No in-app social.

### 23. `monero:` / share-sheet intent

**Now:** `monero:` VIEW filter is on `MainActivity`; `maybeHandleMoneroIntent` parses and Save-commits. Untested against a real wallet app. No `ACTION_SEND`.

**Change:**
- Keep `monero:` / `monero:<addr>`. Open Wallet in edit mode with the address filled; user still taps Save (don’t silent-overwrite a saved wallet).
- Add `ACTION_SEND` `text/plain` so Cake / Monerujo “share address” can land in Anvil.
- Same parse as QR (`Wallet.parse`). Invalid → Wallet tab + error, no toast-only fail.

### 24. Start-diff as an advanced field

**Now:** `MiningService.START_DIFFICULTY = 30000` is a compile constant. XMRig already sends `wallet+30000` to MoneroOcean (VarDiff start). `0` = pool auto. No UI. VoltMiner hardcodes this too.

**Change:** Settings → Advanced (collapsed).
- Number field, default **30000**. Empty / 0 = pool auto (`wallet` with no `+N`).
- Persist in Prefs. Changing it while mining restarts XMRig (same as threads — confirm).
- One-line hint: “Higher = fewer small shares, slower first accept. Leave 30000 unless you know.”
- Not on Mine. Not a slider.

### 25. Custom pool host / port / TLS

**Now:** host/port/TLS are constants: `gulf.moneroocean.stream:20032` TLS on. `XMRigManager` already takes `poolUrl` + `poolTls`. UI cannot change them. Donate stays 0% in the config template regardless of pool.

**Change:** Settings → Advanced, under start-diff.
- Host, port, TLS switch. Defaults = current MoneroOcean TLS. A **Reset to MoneroOcean** control.
- Persist in Prefs. Changing pool while mining restarts XMRig (confirm).
- “Verify on MoneroOcean” only if host is still `*.moneroocean.stream`. Otherwise hide or swap for “open pool URL” if we have one.
- Worker id (`AX-…`) still goes as XMRig `rig-id` / pass. Don’t invent pool-specific usernames.
- Keep `"donate-level": 0` even off MoneroOcean — Anvil’s cut stays 0. (XMRig-dev donate is a different flag; leave it 0.)
- TLS default **on**. Turning it off needs a one-time warning (cleartext wallet to the pool).
- Don’t build a pool picker / nicehash / failover list. One host.

## Later (not 20–25)

- 18. Short onboarding (0% cut, heat, set wallet). Skip clan slides.
