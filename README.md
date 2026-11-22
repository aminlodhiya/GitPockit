# GitPockit

**Git in your pocket. Your repos, always in sync.**

GitPockit is a free, open-source Git client for Android. Keep any folder on your phone — notes, an Obsidian vault, dotfiles, docs, code — in sync with a Git repository, and use the full power of Git from your phone.

> Status: **early development (v0.2, Phase 1)**. Core Git features work; automatic background sync comes next. Expect breaking changes.

## Features

**Available now**
- Repositories anywhere on your phone: clone into, import, or create a repo in any folder (e.g. an Obsidian vault), grouped into containers
- Credentials stored encrypted with the Android Keystore: HTTPS personal access tokens, SSH keys generated on the device (Ed25519 / RSA 4096) or imported; host keys are trusted on first use and pinned
- Changes: stage / unstage single files or everything, discard changes, commit, amend
- Diff viewer with line numbers for working-tree, staged and committed changes
- History with commit details and per-file diffs
- Branches: create, switch (including remote branches), rename, delete
- Remotes: add, edit URL, rename, delete, fetch, choose which one to sync with
- Pull, push, fetch and one-tap **Sync** (commit → pull → push), ahead/behind and conflict indicators
- Per-repo commit author and credential
- Premium dark theme (default), simple light theme, AMOLED black, optional Material You colors

**On the roadmap**
- Automatic sync: on a schedule, when files change, when a chosen app opens or closes, from a Quick Settings tile, home-screen widget, or automation apps (Tasker, Automate) via intents
- Offline-first: commits always happen locally, pushes queue up and run when you're back online
- Conflict resolver, per-repo conflict strategies, sync log
- Branches, tags, remotes, stash, rebase, cherry-pick, revert, reset, blame, reflog, submodules, Git LFS
- Hunk- and line-level staging, commit graph, diffs, file explorer and code editor
- GitHub / GitLab / Gitea sign-in, issues, pull requests and CI runs
- Signed commits, biometric lock, backup/restore
- Opt-in AI helpers with your own API key (hidden by default)

## Building

Requirements: JDK 17+, Android SDK (platform 36, build-tools 36).

```bash
./gradlew :core:git:test        # Git engine tests (pure JVM, no device needed)
./gradlew :core:data:testDebugUnitTest  # database & credential tests (Robolectric)
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/
```

## Project layout

| Module | Purpose |
|---|---|
| `app` | Android app: navigation, screens, DI |
| `core/designsystem` | Themes (dark / light / AMOLED), typography, shared components |
| `core/git` | `GitEngine` API and its JGit implementation (pure Kotlin/JVM) |
| `core/data` | Room database (repos, containers, credentials), repositories, storage helpers |
| `core/security` | Android Keystore AES-GCM encryption for secrets |

The app talks only to the `GitEngine` interface, so the Git backend can be swapped without touching features.

## Tech

Kotlin · Jetpack Compose · Material 3 · Hilt · Coroutines/Flow · DataStore · [JGit](https://www.eclipse.org/jgit/) · [JSch (mwiede fork)](https://github.com/mwiede/jsch)

## Contributing

Contributions are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md).

## License

GitPockit is free software, licensed under the [GNU General Public License v3.0](LICENSE).
