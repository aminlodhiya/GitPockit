# Releasing GitPockit

Releases are built and published by GitHub Actions (`.github/workflows/release.yml`) when a
version tag is pushed. Each release contains two signed APKs:

| File | Contents |
|---|---|
| `GitPockit-<version>-standard.apk` | Everything except "sync when another app opens/closes". No accessibility service, so it installs without Google Play Protect's "app blocked" warning. **Recommended.** |
| `GitPockit-<version>-full.apk` | Adds the app open/close trigger (accessibility service). Play Protect may block it when installed from a browser/file manager in some countries; install via `adb` or pause Play Protect. |

Both use the same application id and signing key, so users can switch editions as a normal update.

## One-time setup: signing key and secrets

The release key is **not** in the repository. Keep it backed up somewhere safe (password manager,
encrypted backup): every future update must be signed with the same key, or users would have to
uninstall and reinstall.

Add these **repository secrets** (GitHub → Settings → Secrets and variables → Actions → New repository secret):

| Secret | Value |
|---|---|
| `GITPOCKIT_KEYSTORE_BASE64` | `base64 -w0 path/to/gitpockit-release.jks` |
| `GITPOCKIT_KEYSTORE_PASSWORD` | the keystore password |
| `GITPOCKIT_KEY_ALIAS` | the key alias (`gitpockit`) |
| `GITPOCKIT_KEY_PASSWORD` | the key password |

For local signed builds, put the same values in a `keystore.properties` file in the project root
(it's git-ignored):

```properties
storeFile=/absolute/path/to/gitpockit-release.jks
storePassword=…
keyAlias=gitpockit
keyPassword=…
```

Without a key, `assemble*Release` still works but produces unsigned APKs.

## Making a release

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts` and commit.
2. Tag and push: `git tag v0.7.0 && git push origin v0.7.0`
3. The workflow checks the tag matches `versionName`, runs the tests, builds and verifies both
   signed APKs, and publishes them with a `SHA256SUMS.txt` on the GitHub Releases page.
