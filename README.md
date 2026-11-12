<div align="center">

<img src="app-icon.png" width="110" alt="GigaGet logo">

# GigaGet

**Paste a link. Tap. Got it.**<br>
A fast, free video and audio downloader for **Android, Windows, macOS and Linux**.<br>
Nothing to install, nothing to configure.

[![Latest release](https://img.shields.io/github/v/release/nurulislamtask/GigaGet?style=for-the-badge&color=c8ff3c&labelColor=10150a)](https://github.com/nurulislamtask/GigaGet/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/nurulislamtask/GigaGet/total?style=for-the-badge&color=6a2cff&labelColor=10150a)](https://github.com/nurulislamtask/GigaGet/releases)
[![Build](https://img.shields.io/github/actions/workflow/status/nurulislamtask/GigaGet/build.yml?style=for-the-badge&labelColor=10150a)](https://github.com/nurulislamtask/GigaGet/actions)

**[⬇ Download the latest version](https://github.com/nurulislamtask/GigaGet/releases/latest)**

</div>

---

## Why GigaGet

Most downloaders make you hunt for the right tool, install extras and fiddle with settings. GigaGet is built around one idea: **install it once and it just works.**

| | |
| --- | --- |
| **Everything built in** | The download engine (yt-dlp) and FFmpeg ship inside the app. There is nothing else to install. |
| **Stays up to date** | Websites change all the time. GigaGet refreshes its engine quietly in the background so downloads keep working. |
| **Zero settings** | Files are saved to `Downloads/GigaGet`. Your last quality choice, history and theme are remembered. |
| **One tap from your clipboard** | Copy a link anywhere, open GigaGet, and a banner offers to download it. |
| **Same app everywhere** | One interface for phone and computer. Built phone-first, it grows into a sidebar layout on desktop. |
| **Your choice of quality** | Best, 4K, 1440p, 1080p, 720p, 480p, 360p, or audio-only MP3. Only the options a video really has are shown. |
| **Works on 1,800+ sites** | Powered by [yt-dlp](https://github.com/yt-dlp/yt-dlp), the most widely used open-source extractor. See [supported sites](#supported-sites). |
| **Choose where files go** | Saved to `Downloads/GigaGet` by default. On computers pick any folder, on Android choose Downloads or Movies and Music. |
| **Always current** | When a new version is released, GigaGet shows an update screen that stays until you update, so you never run an outdated, broken build. |
| **Private** | No account, no ads, no tracking. Your files and history stay on your device. |

## Download

Get the file for your device from the **[latest release](https://github.com/nurulislamtask/GigaGet/releases/latest)**.

| Device | File | How to install |
| --- | --- | --- |
| **Android** (7.0+) | `GigaGet.apk` | Open the file on your phone and allow *Install unknown apps* when asked. |
| **Windows** 10 / 11 | `GigaGet_…_x64-setup.exe` or `.msi` | Run it. If SmartScreen appears, choose **More info → Run anyway**. |
| **macOS** | `GigaGet_…_x64.dmg` | Drag to Applications. First launch: right-click the app, then **Open**. |
| **Linux** | `.deb`, `.rpm` or `.AppImage` | Install with your package manager, or make the AppImage executable and run it. |

> **Why the warnings?** GigaGet is a young open-source project and the installers are not yet signed with a paid certificate. The warnings are normal for unsigned apps and appear once.

## Supported sites

GigaGet uses yt-dlp, which understands **1,800+ websites**. Popular ones include:

| | | | |
| --- | --- | --- | --- |
| YouTube | Facebook | Instagram | TikTok |
| X (Twitter) | Reddit | Vimeo | Dailymotion |
| Twitch | SoundCloud | Bandcamp | Bilibili |
| Pinterest | Tumblr | Rumble | Odysee |
| Streamable | VK | Imgur | Direct file links |

The full list is in the [yt-dlp supported sites](https://github.com/yt-dlp/yt-dlp/blob/master/supportedsites.md) page. Availability depends on each website and can change. Private content that needs a login and DRM-protected streaming (Netflix and similar) are not supported.

## How it works

1. **Copy** a link to a video or audio.
2. **Paste** it into GigaGet, or tap the banner that appears.
3. **Pick** a quality and tap **Download**.

Progress, speed and time left are shown live. Interrupted downloads can be retried, and finished files open with one tap.

## Updates

GigaGet checks for new versions by itself. When one is out, an **Update required** screen appears and stays until you install it, so every user is always on a build that works with the latest sites. Your history and settings are kept. If you are offline the app keeps working, and the update screen appears as soon as a newer version is known.

## About

GigaGet is made and maintained by [@nurulislamtask](https://github.com/nurulislamtask).

It started from a simple wish: one downloader that looks and works the same on a phone and on a computer, and that you never have to set up. Ideas, bug reports and questions are welcome in [Issues](https://github.com/nurulislamtask/GigaGet/issues).

## Project status

GigaGet is at an early stage (**v0.x**). It already downloads single videos and audio from supported sites on every platform.

Planned: playlists and channels, background downloading on Android, share-to-GigaGet from other apps, subtitles, installers signed with a paid certificate.

## For developers

```
ui/                        Interface (HTML, CSS, JavaScript)
src-tauri/                 App shell (Tauri 2) and desktop engine (Rust)
plugins/giga-engine/       Android engine (Kotlin, youtubedl-android)
.github/workflows/         Builds every platform and publishes a release
```

The interface talks to one small API (`probe`, `start`, `cancel`, `info`, plus progress events) defined in `ui/engine.js`. The desktop apps answer it from Rust and the Android app from Kotlin, so the interface is shared.

**Build everything:** open **Actions → Build GigaGet → Run workflow**. A successful run publishes a new release with every installer.

**Run locally:** install Rust, Node 18+ and pnpm, place yt-dlp and FFmpeg in `src-tauri/binaries/` as `gigaget-ytdlp-<target>` and `gigaget-ffmpeg-<target>`, then run `pnpm install && pnpm tauri dev`. Open `ui/index.html` in a browser to preview the interface with a demo engine.

**Versions:** every build is stamped `0.1.<run number>`, published as a GitHub Release with the same tag, and the in-app update screen compares against the latest release.

**Android signing:** all builds are signed with the shared project key in `android-signing/`, so a new version installs over the old one. To use a private key instead, add the repository secrets `ANDROID_KEYSTORE_B64` and `ANDROID_KEYSTORE_PASSWORD`; they take priority.

## Responsible use

GigaGet is for saving content you own or have permission to save. You are responsible for following copyright law and each website's terms of service.

## Credits

Built with [Tauri](https://tauri.app), [yt-dlp](https://github.com/yt-dlp/yt-dlp), [FFmpeg](https://ffmpeg.org) and [youtubedl-android](https://github.com/JunkFood02/youtubedl-android).
