# Prebuilt `libxmrig.so`

The binary is gitignored (7+ MB). Copy it in before a local build:

```bash
mkdir -p app/src/main/jniLibs/arm64-v8a
cp prebuilt/libxmrig.so app/src/main/jniLibs/arm64-v8a/libxmrig.so
```

Rebuild from source with [BUILD-XMRIG.md](BUILD-XMRIG.md) (NDK, TLS on, `v6.25.0-mo1`).
Do not drop in a random internet XMRig — Android needs the NDK build (`filesDir` is noexec; we exec from `nativeLibraryDir`).
