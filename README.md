<a href='#acidburnmonkey'> <img src="repo/viveNotes.png"  height="100" alt="react" />

# Vive Notes

A handwritten note-taking app built for students, combining the natural feel of pen and paper with the power
of digital documents.

All features are completely free, including cross-device sync. Your notes stay private: no data leaves your device,
and all AI features run entirely on-device.

[Demo Video](https://www.youtube.com/watch?v=UMixtcPbUgo)

# Download

<table>
  <thead>
    <tr>
      <th>F-Droid</th>
      <th>Google Play</th>
      <th>APK</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td align="center">
        <a href="https://vivenotes.net/#download">
          <img src="repo/fdroid.png" height="80" alt="Get it on F-Droid">
        </a>
      </td>
      <td align="center">
        <a href="#">
          <img src="repo/Google_Play_Store.svg" height="80" width="200" alt="Get it on Google Play">
        </a>
      </td>
      <td align="center">
        <a href="https://github.com/AquilaIgnis/viveNotes/releases/latest">
          <img src="repo/apk.png" height="80" alt="Download APK">
        </a>
      </td>
    </tr>
    <tr>
      <td align="center"><b>Available</b></td>
      <td align="center"><b>Coming Soon!</b></td>
      <td align="center">Available</td>
    </tr>
  </tbody>
</table>

# Features

- Local storage and AI processing on device.
- Fuzzy notebook search across text, tables, handwriting, and text found inside images.
- On-device handwriting and formula recognition, including math solving, evaluation, and graphing tools [docs](docs/calculator.md).
- Highly performant Ink rendering (7ms on over 10k strokes).
- Free-form page infinite canvas.
- Rich-text editing.
- Inline and free-form LaTeX equations with native rendering.
- Handwriting with configurable pens, pressure, smoothing.
- On-device image OCR.
- Custom paper: ruled, multiple grid sizes, dotted, hexagonal.
- Automatic database snapshots, and revision restoration.
- Open source export file format .vive , [docs](docs/viveFormat.md).
- Hardware keyboard shortcuts and configurable stylus buttons mappings.

# Gallery

| <a href='#acidburnmonkey'> <img src="repo/g5.jpeg"  height="500" /> </a> |
| ------------------------------------------------------------------------ |
| <a href='#acidburnmonkey'> <img src="repo/g1.png"  height="500" /> </a>  |
| <a href='#acidburnmonkey'> <img src="repo/g2.png"  height="500" /> </a>  |

| <a href='#acidburnmonkey'> <img src="repo/g3.jpeg" height="150"/> </a> | <a href='#acidburnmonkey'> <img src="repo/g4.jpeg"  height="200"/> </a> |
| ---------------------------------------------------------------------- | ----------------------------------------------------------------------- |
| <a href='#acidburnmonkey'> <img src="repo/g7.jpeg" height="450"/> </a> | <a href='#acidburnmonkey'> <img src="repo/g8.jpeg" height="450"/>       |

# Self Host server

[Sync Server](https://github.com/AquilaIgnis/viveCServer)

# Road Map

- [x] Fdroid release
- [ ] Play Store
- [ ] Linux & windows port
- [ ] Apple
- [ ] Web Client

# Donate To Project

<a href="https://www.buymeacoffee.com/acidburn" target="_blank"><img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy Me a Coffee" style="height: 60px !important;width: 217px !important;" ></a>

# Dev

## Build prerequisites

| Item                                    | What this project uses                                                     |
| --------------------------------------- | -------------------------------------------------------------------------- |
| JDK                                     | 21 in CI                                                                   |
| Android SDK platform                    | Android 37.0 (`compileSdk` 37)                                             |
| Android SDK Build Tools                 | A compatible version selected by AGP; 37.0.0 is a known-good choice        |
| Python                                  | `python3.13` executable                                                    |
| Android NDK                             | 28.2.13676358, selected by AGP 9.2.1                                       |
| Platform Tools and a device or emulator | Android API 35 or newer; arm64-v8a or x86_64 for debug and `-PtestRelease` |

The Gradle wrapper downloads **Gradle 9.7.0**; the build pins **Android Gradle Plugin 9.2.1** and **Kotlin 2.2.10** in `gradle/libs.versions.toml`.
Gradle invokes `python3.13` directly; the exact Python 3.13.14 pin in the root `pyproject.toml` is for a separate Python environment.

Install the SDK packages with Android Studio's SDK Manager or the Android SDK command-line tools:

```bash
sdkmanager 'platforms;android-37' 'build-tools;37.0.0' 'ndk;28.2.13676358'
```

## Build and install

For a first build, use the debug variant. An Android device is needed only for `installDebug`:

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

An unsigned release can be built without signing credentials:

```bash
./gradlew :app:assembleRelease
```

To sign and install a release, put the upload-key values named in `env.example` in a gitignored `.env` at the repo root, and keep the keystore outside the working tree.
Then run `./gradlew :app:installRelease`. The normal release APK includes arm64-v8a only; use debug or `-PtestRelease` for an x86_64 emulator.

`local.properties` may also hold the optional public Google Web client ID and cloud URL settings described in `app/build.gradle.kts`. They are not required to compile the app.

## Run tests

Local tests need no device:

```bash
./gradlew :app:testDebugUnitTest
```

The release instrumented suite tests the R8-minified app on a connected API 35+ device or emulator.

It installs as `com.vivenotes.testrelease`, so it neither collides with nor uninstalls a signed
`com.vivenotes`.

```bash
./gradlew connectedAndroidTest -PtestRelease
```

The debug instrumented suite uses the same device requirements:

Reports are written under `app/build/reports/androidTests/connected/`.

```bash
./gradlew connectedDebugAndroidTest
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.vivenotes.ui.editor.PageViewTest
```

# Acknowledgments

- [SymPy](https://www.sympy.org/en/index.html) , powers the math engine
- [Chaquopy](https://chaquo.com/chaquopy/), also the math engine
- [PaddlePaddle](https://github.com/PADDLEPADDLE/PADDLEOCR) , using their local models
- Claude & Codex
