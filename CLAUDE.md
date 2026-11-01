# GitHub Discovery

Android app (Kotlin and Compose) for discovering GitHub repos and starring them into Lists. Read-only apart from star and list operations.

- Release tier: **small** (`v1.0`, `v1.1`, … per the release-versioning habit). Attach `app-release.apk` to each GitHub release.
- Build: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew assembleRelease`. The default JAVA_HOME in this shell points to a JDK path that doesn't exist.
- API behaviour and gotchas: `docs/github-api-notes.md`. Read it before changing search, sort or lists.
- Code: `api/` (Http, DeviceFlow, GitHubApi GraphQL client, Query/Filters builder) and `ui/` (AppViewModel holds all state, plus Compose screens).
- Testing without a device: run AVD `hebrish34` headless (`emulator -avd hebrish34 -no-window`). Install the debug APK, then inject a token into `shared_prefs/github_discovery.xml` (keys `token`, `login`) via `run-as`.
