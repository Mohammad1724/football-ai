# Signing

`footballai-release.p12` is the **stable** signing key for this app.

| | |
|---|---|
| Keystore | `signing/footballai-release.p12` (PKCS#12) |
| Store password | `footballai-signing-2026` |
| Key alias | `footballai` |
| Key password | same as the store password (PKCS#12 rule) |
| Key | RSA 4096, self-signed, valid 30 years |
| Certificate SHA-256 | `7cd605244926555fa5e51be08601d89e908e6df474f10b592ecf71b35f73d668` |

## Why the key is in the repository

Before this key, every CI run signed the APK with a debug keystore that Gradle created
on that runner. Each download therefore had a *different* signature, and Android
refuses to install a package over an app signed by another key: the only way to update
was to uninstall first, which deletes the local database.

A stable, committed key fixes that for a phone-only workflow: no secret has to be pasted
into a build, and the same certificate appears in every build.

## What you accept by doing this

Anyone who can read this repository can produce an APK that Android will accept as an
update to the installed app. That is fine for a personal build side-loaded from GitHub
Actions; it is **not** acceptable for the Play Store, which requires a private key.
If that ever matters, the same key can be moved into GitHub Secrets —
`app/build.gradle.kts` already reads `FOOTBALLAI_STORE_PASSWORD` and
`FOOTBALLAI_KEY_ALIAS` from the environment first.

## Changing the key

Changing it changes the signature, and Android will then demand a one-time uninstall
again. Everything installed before is signed with the certificate fingerprint above; the
CI build verifies that fingerprint on every run and fails if it ever differs.

## Version codes

`versionCode` is `20 + GITHUB_RUN_NUMBER`, so every build published by CI is strictly
newer than the one before it. `versionName` follows the same number (`0.7.<run>`).
