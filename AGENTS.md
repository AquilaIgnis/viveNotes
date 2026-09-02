# Project memory

Before planning, diagnosing, or modifying this project:

1. Read `memory/initial.md`.
2. Read the files in `memory/` relevant to the current task.
3. Treat these files as persistent project context across sessions.
4. Update the relevant memory file after material decisions or implementation changes.
5. Do not overwrite unrelated notes or assume outdated plans reflect completed work—verify against the codebase.
6. Do not modify docs/ directory unless asked to.

# Android development

    Use the `android-cli` skill whenever Android CLI capabilities would help, including:

    - Building or running the app
    - Working with emulators or physical devices
    - Using ADB, Logcat, screenshots, or UI inspection
    - Managing SDK components
    - Looking up Android documentation
    - Discovering additional Android skills

    Read and follow the skill at
    `$HOME/.codex/skills/android-cli/SKILL.md` or `$HOME/.claude/skills/android-cli/SKILL.md`
    before performing those operations.

# Release signing

The upload key's four values are read from a gitignored `.env` at the repo root; `.env.example`
documents the names. `app/build.gradle.kts` resolves each one from `-P`, then `.env`, then the
environment, so CI signs with no file on disk.

- Never put a password — or any other secret — in `local.properties` or `gradle.properties`. Both
  are tracked. That is the whole reason `.env` exists.
- Keep the keystore itself outside the working tree. `.gitignore` covers `.env`, not a stray `.jks`.
- No `.env` is a supported state: `release` then builds unsigned, under AGP's own
  `app-release-unsigned.apk` name. A `VIVE_KEYSTORE` pointing at a missing file is a configuration
  error, not a fallback to unsigned.
- A signed release is published as `vivenotes-<versionName>.apk` / `.aab` in the usual
  `app/build/outputs/` locations. Bump `appVersionName` in `app/build.gradle.kts`; `versionName` and
  both artifact names follow it.
- `-PtestRelease` keeps debug signing and AGP's default filenames on purpose — that variant exists
  to run instrumented tests through R8 and is installed on devices constantly. Leave it that way.

# UI design language

Use Material 3 Expressive as the default design language for all new and modified UI.

- Prefer official Material 3 Expressive components over custom widgets.
- Use Material 3 Expressive motion patterns for transitions, state changes, loading, selection, navigation, and component interactions.
- Keep shapes, typography, color, spacing, and motion consistent with the app’s Material 3 theme.
- Reuse existing themed components and motion tokens instead of introducing one-off styling or animation values.
- When no Expressive component exists, build with standard Material 3 Compose primitives while matching the Expressive visual and motion language.
- Preserve accessibility: respect reduced-motion preferences, maintain readable contrast, provide semantic labels, and keep touch targets appropriately sized.
- Do not introduce legacy Material 2 components.
- Avoid experimental APIs unless they are necessary and compatible with the project’s dependency versions
