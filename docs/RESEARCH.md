# Implementation research

Checked on 2026-07-12 before implementing the first milestone.

## Morphe

- Patch source: `MorpheApp/morphe-patches` at commit
  `ebd1d868a8ee8f5e796cae2434a315a8f9461efa`.
- Template source: `MorpheApp/morphe-patches-template` at commit
  `762d7ab96954b3afd76d63492882fb9a99f68e21`.
- The upstream `Downloads` patch hooks `OfflineVideoEndpointFingerprint` at
  method entry. Parameter `p3` is the video ID. Its extension returns `true` to
  consume the action or `false` to continue YouTube's native downloader.
- The application-context fingerprint is the same one used by Morphe's shared
  YouTube extension hook: the matched method contains both
  `Application.onCreate` and `Application creation`.
- Supported versions and Google signing-certificate hashes in this bundle were
  copied from upstream's current YouTube compatibility declaration.

## cobalt

- Official API contract: `POST /` with JSON `Accept` and `Content-Type` headers.
- The user-facing root `https://cobalt.skulldogged.dev/` is the web frontend.
  Its API is mounted at `https://cobalt.skulldogged.dev/api/`.
- Live `GET /api/` reported cobalt `11.7.1`, YouTube support, branch
  `meowing.de`, and commit `1e2a1799c14f749129d87c65ba2c0cbf01e778ce`.
- A live request using the extension's exact request options returned a
  `tunnel` response with an HTTPS URL and cobalt-generated MP4 filename.
- The initial client accepts `tunnel` and `redirect`; it deliberately rejects
  `picker`, `local-processing`, unknown statuses, and non-HTTPS result URLs.

## Verification boundary

`buildAndroid` and `generatePatchesList` pass, and the generated MPP contains
the compiled patch plus `extensions/cobalt-downloads.mpe`. End-to-end patching
and an on-device button tap still require an original compatible YouTube APK;
the only local YouTube APK discovered during this pass was already Morphe
patched, so it was not a valid clean patch input.
