# Cobalt Downloads for Morphe

A custom Morphe patch bundle that replaces YouTube's in-app **Download** action
with a direct request to a private cobalt instance. For high-quality YouTube
videos, the patch downloads cobalt's separate video and audio tunnels in a
foreground service, then performs a copy-only remux into a finalized MP4 in the
normal Downloads folder.

This project uses code and fingerprints adapted from
[MorpheApp/morphe-patches](https://github.com/MorpheApp/morphe-patches).

## Current behavior

1. Tap **Download** on a regular YouTube video.
2. The injected extension posts the video URL to
   `https://cobalt.skulldogged.dev/api/` on a background thread.
3. A cobalt `local-processing` merge response is downloaded with native
   progress reporting.
4. Android copies the AV1 video and Opus audio samples into a finalized,
   seekable MP4 without transcoding them.
5. Direct cobalt `tunnel` and `redirect` responses still fall back to Android's
   system `DownloadManager`.

The first milestone intentionally uses fixed request settings:

- video and audio (`downloadMode: auto`)
- up to 1440p
- AV1 video (with cobalt's VP9 fallback) and Opus audio in MP4
- pretty filenames
- local processing preferred

Picker responses and local-processing operations other than a two-stream merge
are reported as unsupported. Only HTTPS download URLs are accepted. The native
AV1/Opus MP4 remux path requires Android 14 or newer.

## Available patches

<!-- PATCHES_START EXPANDED -->
> **[v1.1.4](https://github.com/skulldogged/cobalt-morphe/releases/tag/v1.1.4)**&nbsp;&nbsp;•&nbsp;&nbsp;`main`&nbsp;&nbsp;•&nbsp;&nbsp;1 patches total
<details open>
<summary>📦 YouTube&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 🧪&nbsp;21.26.360 | 🧪&nbsp;21.25.523 | 🧪&nbsp;21.24.360 | 🧪&nbsp;21.05.265 | 20.51.39 | 20.31.42 | 20.21.37 |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Cobalt downloads](#cobalt-downloads) | Replaces YouTube's native download action with a direct cobalt download. |  |

</details>

<!-- PATCHES_END -->

## Build

Morphe's Gradle plugin is hosted in GitHub Packages, so the build needs a GitHub
token that can read the Morphe registry:

```powershell
$env:GITHUB_ACTOR = gh api user --jq .login
$env:GITHUB_TOKEN = gh auth token
.\gradlew.bat buildAndroid
```

The bundle is written to:

```text
patches/build/libs/patches-<version>.mpp
```

For local testing, apply that bundle with Morphe CLI. Once this repository has
a semantic-release GitHub release, add
`https://github.com/skulldogged/cobalt-morphe` as a custom source in Morphe
Manager and select **Cobalt downloads**. The patch is not selected by default.

Use a YouTube version listed in `patches-list.json`. The compatibility list is
kept aligned with the upstream download-button fingerprint.

## Design

- `CobaltDownloadsPatch.kt` injects application-context initialization and the
  download-button early return.
- `CobaltClient.java` owns the cobalt JSON request and response parsing.
- `CobaltDownloader.java` starts the foreground download flow and prevents
  accidental duplicate jobs.
- `CobaltDownloadService.java` owns tunnel downloads, progress notifications,
  temporary storage, the Media3 AV1/VP9 + Opus MP4 remux, and direct-response
  `DownloadManager` fallback.

Merged media is processed inside a foreground service running in YouTube's
process. Encoded samples are copied rather than decoded or re-encoded, and the
temporary component files are removed when the job finishes.

## License

Licensed under [GPL-3.0](LICENSE). See [NOTICE](NOTICE) for the required Morphe
attribution and additional terms inherited from the template and adapted code.
