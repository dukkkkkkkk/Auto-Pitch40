# Auto-Pitch40

ZenithProxy plugin (Pitch40 elytra flight bot). Java, built with `./gradlew build`.

## Releasing

- Every update pushed to `main` bumps `plugin_version` in `gradle.properties` by 0.0.1 (1.0.2 -> 1.0.3), in the same push.
- Pushing to `main` runs `.github/workflows/publish.yml`, which builds the jar and publishes a GitHub release tagged with `plugin_version`. Check that run succeeds after pushing.
- Commits are authored as `dukkkkkkkk <319730967+dukkkkkkkk@users.noreply.github.com>` (no-reply address; the repo is public).
