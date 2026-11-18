# Diffuse Music Live Wallpaper (Play Store Kotlin Reconstruction)

Editable Kotlin Android Gradle project reconstructed from the Play Store release `com.depthware.lwp.diffuse.v2` (`1.1.0.0 (3433)-PUB`, `versionCode = 3433`).

## Project Structure

- `app/src/main/assets/` — Play Store GLSL shaders (`blur.*`, `color.*`, `fade.*`, `flutedGlass.*`, `font.*`, `kawaseBlur.*`, `noise.*`), IBM Plex Mono bitmap font, noise LUT (`rand.png`), and sample album art.
- `app/src/main/res/` — Play Store drawables, animations, fonts, mipmaps (launcher icons + built-in gradient/texture palettes), localized strings (`values`, `values-es`, `values-pt-rBR`, `values-zh-rCN`), themes, XML configs, navigation graph, and ViewBinding layouts (`layout/`, `layout-land/`, `layout-sw600dp/`).
- `app/src/main/java/com/depthware/lwp/diffuse/`:
  - `application/DiffuseApplication.kt` — App startup, Logger setup, SharedPreferences restore, RxJava observers, notification channels, and WorkManager regulator scheduling.
  - `engine/` — `GdxConfig.kt` and `DiffusePlayer.kt` (4-pass libGDX fluid + Kawase blur + fluted glass + debug profiler renderer).
  - `service/` — `LiveWallpaperService.kt`, `NotificationPeekService.kt`, and `ScreenRecordForegroundService.kt`.
  - `manager/` — `ViewModelManager.kt`, `GdxPlayerManager.kt`, `AudioVisualizerManager.kt`, `MusicPaletteManager.kt`, `NotificationChannelManager.kt`, `NotificationRegulatorManager.kt`, `ReviewManager.kt`, `SliceNotifier.kt`, `ToastManager.kt`, `ExternalNavigator.kt`.
  - `viewModel/` — `UniversalViewModel.kt` (`SharedPreferences`-backed `MutableLiveData` store), `MainFragmentViewModel.kt`, `ScreenRecordViewModel.kt`, `ServiceLifecycleDispatcher.kt`.
  - `activity/` & `fragment/` & `view/` — `LiveWallpaperActivity`, `MainFragment`, `SelectDefaultPaletteActivity` + palette tab fragments, `LaunchMusicAppsActivity`, `FeedbackActivity`, `SaveImageActivity`, `ScreenRecordActivity`, and dialog/custom views.
  - `recorder/` — Grafika-derived EGL/MediaCodec internal audio + video recorder (`TextureMovieEncoder`, `VideoEncoderCore`, `EglCore`, etc.).
  - `share/` — `AlbumShareTask.kt` (composes shared wallpaper images with optional album overlay).
  - `work/NotificationRegulatorWorker.kt` & `provider/SettingsSliceProvider.kt` — background playback monitor and Android Slice provider.

## Key Differences from Raw Play Store APK

1. **Kotlin + ViewBinding**: All app-owned classes are cleanly reconstructed in idiomatic Kotlin with ViewBinding instead of generated DataBinding adapters.
2. **Telemetry & Licensing Stubs Removed**: Sentry crash reporting and Google Play License Verification (`LVLLibrary`) were stripped so local debug/feature builds run offline without Play licensing failures or telemetry uploads.
3. **Renderer & Visualizer Fixes (from `report.md`)**:
   - `DiffusePlayer.setAlbumToBlur()` binds `fboB.colorBufferTexture.bind(1)` (color-buffer texture rather than raw FBO ID) to texture unit `1`.
   - `AudioVisualizerManager.frequencyToBin()` converts `samplingRate` from milliHertz (`samplingRate / 1000.0f`) before computing FFT bin indices.
   - `NotificationPeekService.REQUIRE_NON_CLEARABLE` is exposed as a boolean flag (`false` by default) so modern dismissible media notifications with `Notification.EXTRA_MEDIA_SESSION` are recognized automatically.

## Common Feature Extension Points

- **New Fluid / Post-Processing Shaders**: Add `.vert`/`.frag` shaders in `app/src/main/assets/`, compile them in `DiffusePlayer.create()`, expose a mode string in `UniversalViewModel` / `GdxPlayerManager` / `DiffusePlayer.setPostProcessingMode()`, and add a control in `layout_main_frag_config.xml` + `MainFragment.kt`.
- **Built-in Palettes**: Add textures under `res/mipmap-nodpi/` and register `MusicPaletteEmbed` items in `MusicPaletteManager.kt`.
- **Audio Reactivity Bands**: Adjust FFT frequency band boundaries (`BAND_START_HZ` / `BAND_END_HZ`) in `AudioVisualizerManager.kt` and the band weights in `DiffusePlayer.setAudio()`.

## Building & CI Workflow

Set `ANDROID_HOME` (or `sdk.dir` in `local.properties`) to an Android SDK installation with **Android SDK Platform 35** (`compileSdk = 35`) and **JDK 21**, then open `diffuse-kotlin/` in Android Studio or run:

```powershell
.\scripts\build.ps1 assembleDebug
.\scripts\build.ps1 assembleRelease
```

### Release Signing

Release builds look for signing credentials in this order:
1. `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` environment variables (used in CI)
2. Local `keystore.properties` (ignored by git; see `keystore.properties.example`)
3. Fallback to the default debug keystore if neither is present

### GitHub Actions Workflows

- `.github/workflows/build.yml` — Runs on `push`, `pull_request`, and manual `workflow_dispatch`: executes `testDebugUnitTest`, `lintDebug`, `assembleDebug`, and `assembleRelease`, and uploads APKs and reports as artifacts.
- `.github/workflows/release.yml` — Runs on `v*` tags or manual `workflow_dispatch`: signs and publishes a GitHub Release APK.

To enable release signing in GitHub Actions, set these repository secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`), where `KEYSTORE_BASE64` is produced on Windows via:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("app/diffuse-release.jks"))
```
