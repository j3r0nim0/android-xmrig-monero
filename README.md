# Anvil

Android app that runs [XMRig](https://github.com/xmrig/xmrig) (MoneroOcean fork) on ARM64.  
Paste a wallet, start mining.

## Status

v0.1 — ARM64 Android 8+. Experimental. **Use at your own risk.**  
RandomX will heat the phone and can shorten battery life. There is **no warranty**.

- Package: `io.github.j3r0nim0.anvil`
- Default pool: `gulf.moneroocean.stream:20032` (TLS)
- Donate: **0%** while on MoneroOcean
- Payouts go to **your** wallet at [moneroocean.stream](https://moneroocean.stream). This app takes no cut.
- Charging-only is **on** by default. CPU pause default **65°C**. Battery pause **40°C**.
- Threads default **50%**.
- First start **benchmarks** several algorithms (a few minutes, phone gets warm). Later starts at the same thread % skip it.
- **Pause** keeps XMRig running (no re-benchmark). **Stop** (long-press) kills the process; algo-perf stays on disk.

## Requirements

- 64-bit ARM phone or tablet (arm64-v8a). No 32-bit, no Intel Chromebooks.
- Android 8.0+ (API 26)
- Sideload / unknown sources (not on Play)

## Install

[GitHub Releases](https://github.com/j3r0nim0/android-xmrig-monero/releases) → download the APK → install. You do **not** compile.

For auto-updates, add this repo in [Obtainium](https://github.com/ImranR98/Obtainium).

Build it yourself:

```bash
# 1. Place the XMRig binary (see docs/BUILD-XMRIG.md) at:
#    app/src/main/jniLibs/arm64-v8a/libxmrig.so
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## What this is not

- Not another XMRig fork. Bundles an unmodified MoneroOcean XMRig binary. 0% donate while mining MoneroOcean (the fork’s own waiver). Config does not add a second cut.
- Not a Termux build script. If you already run XMRig in Termux, keep doing that.
- Not P2Pool. Phones at a few hundred H/s need a vardiff pool.
- Not the 2018 CryptoNight APKs (`upost/MoneroMiner`, etc.). Those cannot mine Monero.

## License

[GPL-3.0-or-later](LICENSE) because XMRig is GPL-3. See [NOTICE](NOTICE).
