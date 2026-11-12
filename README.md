# GigaGet

Paste a link, get the file. One app for Windows, macOS, Linux and Android.

**Zero setup.** The installer already contains the download engine (yt-dlp + FFmpeg). The engine updates itself in the background, files go to `Downloads/GigaGet`, and your last quality choice is remembered. Nothing has to be configured.

## Get the apps (no local tools needed)

1. Create a GitHub repository and push this folder to it.
2. Open **Actions → Build GigaGet → Run workflow**.
3. When it finishes, download the artifacts:
   - `gigaget-android` → `GigaGet.apk`
   - `gigaget-x86_64-pc-windows-msvc` → `.exe` / `.msi`
   - `gigaget-x86_64-unknown-linux-gnu` → `.deb` / `.AppImage`
   - `gigaget-x86_64-apple-darwin` → `.dmg`

Push a tag like `v0.1.0` and the same workflow publishes them as a GitHub Release.

### Android signing
Without secrets the workflow signs with a throwaway key, which installs fine but a later build will not update over it (uninstall first). To keep one identity, add repository secrets `ANDROID_KEYSTORE_B64` (base64 of your `.keystore`, alias `gigaget` is not required) and `ANDROID_KEYSTORE_PASSWORD`.

## Layout

| Path | What it is |
| --- | --- |
| `ui/` | The interface (plain HTML/CSS/JS, phone-first, adapts to desktop). Open `ui/index.html` in a browser to preview it with a fake engine. |
| `src-tauri/` | Tauri 2 app shell and the desktop engine (`src/engine.rs`) that drives yt-dlp and FFmpeg. |
| `plugins/giga-engine/` | Android engine: a Tauri plugin with Kotlin code using youtubedl-android. |
| `.github/workflows/build.yml` | Builds every platform. |

The UI talks to one small API (`probe`, `start`, `cancel`, `info`, progress events) in `ui/engine.js`. Desktop answers it from Rust, Android from Kotlin.

## Develop locally

Needs Rust, Node 18+ and pnpm. Put yt-dlp and FFmpeg in `src-tauri/binaries/` named `gigaget-ytdlp-<target-triple>` and `gigaget-ffmpeg-<target-triple>` (the workflow shows how), then:

```
pnpm install
pnpm tauri dev
```

## Notes

- macOS and Windows builds are unsigned, so the first launch shows a system warning.
- The macOS build is Intel and runs on Apple Silicon through Rosetta.
- Android downloads run while the app is open. Background downloading is not built yet.
- Use GigaGet only for content you have the right to save, and respect each site's terms.
