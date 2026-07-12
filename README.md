# Cobalt Downloads for Morphe

A custom Morphe patch bundle that replaces YouTube's in-app **Download** action
with a direct request to a private cobalt instance. The returned file is queued
with Android's system `DownloadManager`, so the transfer continues outside the
YouTube process and appears in the normal Downloads folder and notifications.

This project uses code and fingerprints adapted from
[MorpheApp/morphe-patches](https://github.com/MorpheApp/morphe-patches).

## Current behavior

1. Tap **Download** on a regular YouTube video.
2. The injected extension posts the video URL to
   `https://cobalt.skulldogged.dev/api/` on a background thread.
3. A cobalt `tunnel` or `redirect` response is immediately queued with Android
   `DownloadManager`.
4. YouTube shows a short success or failure toast.

The first milestone intentionally uses fixed request settings:

- video and audio (`downloadMode: auto`)
- up to 1080p
- H.264 in MP4
- pretty filenames
- local processing disabled

Picker and local-processing responses are reported as unsupported instead of
silently choosing or attempting an on-device transcode. Only HTTPS download
URLs are accepted.

## Available patches

<!-- PATCHES_START EXPANDED -->

The release workflow generates the supported-version table here.

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
- `CobaltDownloader.java` owns threading, user feedback, filename sanitizing,
  URL validation, and `DownloadManager` enqueueing.

No media bytes pass through the YouTube process. The injected code only obtains
the short-lived result URL and hands it to Android's download service.

## License

Licensed under [GPL-3.0](LICENSE). See [NOTICE](NOTICE) for the required Morphe
attribution and additional terms inherited from the template and adapted code.
