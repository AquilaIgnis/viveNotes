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
