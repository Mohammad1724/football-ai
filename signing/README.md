# Signing

The app is signed with one **stable** signing key, so every build installs over the
previous one without uninstalling (which would delete the local database).

| | |
|---|---|
| Keystore | `signing/footballai-release.p12` (PKCS#12) — restored by CI, **not committed** |
| Key alias | `footballai` |
| Key | RSA 4096, self-signed, valid 30 years |
| Certificate SHA-256 | `7cd605244926555fa5e51be08601d89e908e6df474f10b592ecf71b35f73d668` |

## Where the key lives

The keystore and its password are **not in this repository**. They are stored as
GitHub Actions secrets:

| Secret | Content |
|---|---|
| `FOOTBALLAI_KEYSTORE_BASE64` | The `.p12` file, base64-encoded |
| `FOOTBALLAI_STORE_PASSWORD` | Store password (also the key password, per PKCS#12) |
| `FOOTBALLAI_KEY_ALIAS` | Key alias |

CI decodes the keystore into `signing/` at the start of the build and verifies its
SHA-256 checksum before building; the build then verifies that every APK carries
the certificate fingerprint above and fails if it ever differs.

For a local build, place the `.p12` at `signing/footballai-release.p12` yourself
and set `FOOTBALLAI_STORE_PASSWORD` (and optionally `FOOTBALLAI_KEY_ALIAS`) in your
environment. Without the keystore file, builds fall back to the debug key and will
not install over the stable-signed app.

## Changing the key

Changing it changes the signature, and Android will then demand a one-time uninstall
again. Everything installed before is signed with the certificate fingerprint above.

## Version codes

`versionCode` is `20 + GITHUB_RUN_NUMBER`, so every build published by CI is strictly
newer than the one before it. `versionName` follows the same number (`0.7.<run>`).
