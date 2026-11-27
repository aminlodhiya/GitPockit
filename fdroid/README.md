# Publishing on F-Droid

`dev.gitpockit.yml` is the build recipe F-Droid uses. The store texts, icon, screenshots and
per-version changelogs come from `fastlane/metadata/android/en-US/` on the default branch.

## First submission
1. Create a GitLab account and fork https://gitlab.com/fdroid/fdroiddata.
2. In your fork, add `metadata/dev.gitpockit.yml` (copy of the file here), on a new branch.
3. Open a merge request to fdroiddata. Pick the "App inclusion" template and tick its checklist.
4. The CI pipeline on the merge request builds the app. Reviewers may ask for changes; answer them in the merge request.

## Later releases
Nothing to do in fdroiddata. With `UpdateCheckMode: Tags` and `AutoUpdateMode: Version`, F-Droid
picks up new `v*` tags by itself (usually within a few days). For each release:
- bump `versionCode` and `versionName` in `app/build.gradle.kts`,
- add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`,
- tag `v<versionName>` and push.

## Notes
- F-Droid builds the **Standard** edition (no accessibility service) and signs it with its own key.
  An app installed from GitHub Releases can't be updated from F-Droid (and the other way round)
  without uninstalling first.
- GitHub sign-in in F-Droid builds: set `gitpockit.githubClientId=<your OAuth app client id>` in
  `gradle.properties` (the client id is public). Without it, sign-in works with a personal access token.
