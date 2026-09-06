# Anvil

ARM64 Android miner for [XMRig](https://github.com/xmrig/xmrig) (MoneroOcean fork).  
Paste a wallet, start mining, stop.

GitHub repo stays **android-xmrig-monero** for search. The app on the phone is **Anvil**.

## Status

v0.1 — works on ARM64 Android 8+. Experimental. **Use at your own risk.**  
RandomX will heat the phone and can shorten battery life. There is **no warranty**.

- Package: `io.github.j3r0nim0.anvil`
- Default pool: `gulf.moneroocean.stream:20032` (TLS)
- Donate: **0%** (direct to MoneroOcean)
- Payouts go to **your** wallet. This app takes no cut.
- Charging-only is **on** by default. CPU pause default **65°C**. Battery pause **40°C**.
- Threads default **50%**.

## Requirements

- 64-bit ARM phone or tablet (arm64-v8a). No 32-bit, no Intel Chromebooks.
- Android 8.0+ (API 26)
- Sideload / unknown sources (not on Play)

## Install

GitHub Releases (when published) → download the APK → install. You do **not** compile.

Build it yourself:

```bash
# 1. Place the XMRig binary (see docs/BUILD-XMRIG.md) at:
#    app/src/main/jniLibs/arm64-v8a/libxmrig.so
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## What this is not

- Not an XMRig fork. We bundle an unmodified MoneroOcean build.
- Not Termux. Termux users should keep using Termux.
- Not P2Pool. Phones at a few hundred H/s need a vardiff pool.
- Not the 2018 CryptoNight APKs (`upost/MoneroMiner`, etc.). Those cannot mine Monero.

## License

[GPL-3.0-or-later](LICENSE) because XMRig is GPL-3. See [NOTICE](NOTICE).
