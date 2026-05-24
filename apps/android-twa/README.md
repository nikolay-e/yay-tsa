# Yay-Tsa Android (TWA)

Trusted Web Activity wrapping the production PWA (`https://yay-tsa.com`). Generated with Bubblewrap; built directly with Gradle in CI.

## Signing — one canonical key everywhere

Android refuses to upgrade an installed app whose signature differs (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). To guarantee in-place upgrades for users, **every** build — CI and local — must use the same release key.

Canonical key: **`CN=Yay-Tsa`**, SHA-256 `47:41:65:82:97:9C:B6:A6:F2:C7:B7:42:CF:02:0E:B1:8D:C7:1C:8F:49:3A:0E:6F:EF:07:0D:E3:43:9C:7C:54`, alias `android`. Stored in three places that must stay in sync:

| Where             | What                                                                                                                                          |
| ----------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| macOS Keychain    | `yaytsa-android-keystore-b64` (base64 keystore) + `yaytsa-android-keystore-password`                                                          |
| GitHub secrets    | `ANDROID_KEYSTORE_B64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` (=`android`), `ANDROID_KEY_PASSWORD`                                 |
| `assetlinks.json` | the cert SHA-256, injected at deploy from Helm `ANDROID_CERT_SHA256` (see `apps/web/docker/entrypoint.sh`) — enables verified full-screen TWA |

`app/build.gradle` signs `release` from the `KEYSTORE_FILE` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` env vars and falls back to debug signing only when `KEYSTORE_FILE` is unset. **Never** commit a keystore (`*.keystore`/`*.jks` are gitignored). Do not regenerate the key — doing so breaks upgrades for everyone already on the app.

## Build + install locally

```bash
./dev-install.sh            # build with the canonical key + install on the connected device/emulator
./dev-install.sh --build-only
```

It pulls the keystore from Keychain, builds a release APK byte-for-byte signature-compatible with production, and installs in place. If a device still has a _legacy_ install signed with a different key (an old debug build, or one from before the keystore was standardized), the script uninstalls it once and reinstalls — the only situation that ever needs an uninstall.

CI builds the identical APK in the `Build Android APK` job and publishes it to `https://yay-tsa.com/downloads/yay-tsa.apk`.
