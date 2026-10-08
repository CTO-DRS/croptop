# Contributing to CropTop

Thanks for helping. Bug reports, fixes, tests and features are all welcome.

## Before you start

- For anything bigger than a small fix, open an issue first so the approach can be agreed before you spend time on it.
- By contributing, you agree that your contribution is licensed under the GNU GPL v3.0 or later, the same as the project.
  Add a `Signed-off-by:` line to each commit (`git commit -s`) to certify the
  [Developer Certificate of Origin](https://developercertificate.org/).

## Making a change

1. Build and run the app (see `README.md`). The `fake` flavor is the quickest way to work on UI.
2. Keep each change focused; match the style of the surrounding code.
3. Add or update tests: JVM tests for Kotlin logic and UI state, native tests for engine code, device tests when the behavior
   depends on Android or the real engine.
4. Run the tests for the modules you touched (`./gradlew :<module>:testDebugUnitTest`) and `./gradlew spotlessApply`.
5. Describe what changed and how you tested it in the pull request.

## Guidelines

- The engine contract lives in `core/engine-api`. A change there needs the matching change in both `core/engine` (native) and
  `core/engine-fake`, and a bump of `ENGINE_API_VERSION`.
- User-visible text is plain and specific: say what happened and what the person can do next.
- AI models must have licenses that allow redistribution and commercial use, a pinned SHA-256, and an entry in
  `core/ai/MODELS.md`.
- Never commit secrets, signing keys or model files.

## Reporting bugs

Include the device or emulator, Android version, CropTop version (Settings ▸ About), the steps to reproduce, and what you
expected to happen. Crash reports and screenshots help.
