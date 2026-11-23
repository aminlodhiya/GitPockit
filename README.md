# GitPockit

**Git in your pocket. Your repos, always in sync.**

GitPockit is a free, open-source Git client for Android. Keep any folder on your phone — notes, an Obsidian vault, dotfiles, docs, code — in sync with a Git repository, and use the full power of Git from your phone.

> Status: **early development (v0.6, Phase 3c-1)**. Core Git, automatic background sync, advanced Git operations, history tools and in-app file editing work. Expect breaking changes.

## Features

**Available now**
- Repositories anywhere on your phone: clone into, import, or create a repo in any folder (e.g. an Obsidian vault), grouped into containers
- Credentials stored encrypted with the Android Keystore: HTTPS personal access tokens, SSH keys generated on the device (Ed25519 / RSA 4096) or imported; host keys are trusted on first use and pinned
- Changes: stage / unstage single files or everything, discard changes, commit, amend
- Diff viewer with line numbers for working-tree, staged and committed changes
- History with a **commit graph** (lanes, branch & tag labels, all-branches view), **search** by message/author/hash, commit details and per-file diffs
- **File history** (follows renames), **blame**, **compare** two branches/commits
- **Stage or unstage individual lines or hunks** from the diff view
- **File explorer** with Git status badges: create, rename, delete, ignore (`.gitignore` or this device only), open with other apps
- **Code editor** with syntax highlighting for ~40 file types, line numbers, find & replace, undo/redo, word wrap
- **Markdown preview**, **image viewer** (zoom; SVG and animated GIF), editors for `.gitignore` and `.git/info/exclude`
- Branches: create, switch (including remote branches), rename, delete, **merge** and **rebase**
- **Stash** (save with message, include new files, apply, pop, drop), **tags** (lightweight/annotated, push, delete locally or on the remote, check out)
- Commit actions: **cherry-pick**, **revert**, **reset** (soft / mixed / hard), check out a commit
- **Reflog** to recover from mistakes; **pull with rebase** option per repository
- One conflict resolver for merges, rebases, cherry-picks and reverts (continue or abort)
- Remotes: add, edit URL, rename, delete, fetch, choose which one to sync with
- Pull, push, fetch and one-tap **Sync** (commit → pull → push), ahead/behind and conflict indicators
- Per-repo commit author and credential
- **Automatic sync**: every N minutes/hours or daily at exact times (e.g. 09:00 and 21:30), when files change, when a chosen app opens or closes (e.g. Obsidian), from a Quick Settings tile, a home-screen widget, or automation apps (Tasker/Automate broadcast `dev.gitpockit.action.SYNC`)
- **Offline-first**: changes are always committed locally; pushes wait for the network (or Wi-Fi) and run automatically when it's back, even after a reboot
- **Conflict handling** per repo: ask me (with an in-app resolver: keep mine / keep remote / keep both / edit), or resolve automatically
- Sync log per repository, notifications for conflicts and failures, customizable auto-commit message (`{device}`, `{date}`, `{time}`, `{count}`, `{files}`)
- Premium dark theme (default), simple light theme, AMOLED black, optional Material You colors

**On the roadmap**
- Submodules, Git LFS
- GitHub / GitLab / Gitea sign-in, issues, pull requests and CI runs
- Signed commits, biometric lock, backup/restore
- Opt-in AI helpers with your own API key (hidden by default)

## Building

Requirements: JDK 17+, Android SDK (platform 36, build-tools 36).

```bash
./gradlew :core:git:test        # Git engine tests (pure JVM, no device needed)
./gradlew :core:data:testDebugUnitTest  # database, migration & credential tests (Robolectric)
./gradlew :sync:testDebugUnitTest       # sync engine tests against real Git repos
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
| `core/files` | Safe file operations confined to a repository (explorer/editor backend) |
| `sync` | Sync engine (commit → pull → resolve → push), per-repo operation lock |

The app talks only to the `GitEngine` interface, so the Git backend can be swapped without touching features.

## Tech

Kotlin · Jetpack Compose · Material 3 · Hilt · Coroutines/Flow · Room · WorkManager · DataStore · sora-editor · Coil · [JGit](https://www.eclipse.org/jgit/) · [JSch (mwiede fork)](https://github.com/mwiede/jsch)

## Contributing

Contributions are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md).

## Third-party content

Syntax highlighting grammars come from [VS Code](https://github.com/microsoft/vscode) (MIT) and
[sora-editor](https://github.com/Rosemoe/sora-editor); see `app/src/main/assets/textmate/NOTICE.md`.
The editor is [sora-editor](https://github.com/Rosemoe/sora-editor) (LGPL-2.1).

## License

GitPockit is free software, licensed under the [GNU General Public License v3.0](LICENSE).
