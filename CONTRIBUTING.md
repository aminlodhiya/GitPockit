# Contributing to GitPockit

Thanks for helping out!

## Getting started
1. Fork and clone the repo.
2. Install JDK 17+ and the Android SDK (platform 36), or open the project in Android Studio.
3. Run `./gradlew :core:git:test :app:assembleDebug` to check everything builds.

## Guidelines
- Open an issue before large changes so we can agree on the approach.
- Keep Git logic in `core/git` behind the `GitEngine` interface; screens never call JGit directly.
- Every user-visible string goes in `strings.xml` (we will translate the app).
- Add tests for Git engine behaviour (`core/git/src/test`). They run against local bare repos, without network.
- Use the colors and components from `core/designsystem` so both themes stay consistent.
- Never log or persist credentials in plain text.

## Commit messages
Short imperative summary (`Add stash support`), with details in the body if needed.

## License
By contributing you agree that your contributions are licensed under GPL-3.0.
