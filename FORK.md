# Just Player Subs

Fork of [Just Player](https://github.com/moneytoo/Player) that automatically finds, loads and syncs online
subtitles for streams (e.g. Stremio with Real-Debrid as external player). Installs next to the official app
(`com.brouken.player.subs`). Same license as Just Player (Unlicense).

## What it does

- Identifies the video from the stream URL: IMDb id in the URL (StremThru) or file name + Cinemeta.
- Searches the OpenSubtitles v3 Stremio addon (no key), optionally OpenSubtitles.com (own API key in settings).
- Picks the best subtitle in the preferred language, then the second language (settings).
- Draws the subtitle itself on the player's SubtitleView: no reload when it arrives, instant timing changes.
- Auto sync: reads every embedded subtitle start time from the MKV index (Cues) with two small range requests
  and aligns the online subtitle within ~1 s of playback start. Fallback: listens to an embedded track while
  playing.
- Frame rate measured from the index (24 vs 23.976, PAL), piecewise sync for other cuts, agreeing-window sync
  for dense references with drift correction; best of the top 3 candidates chosen by fit.
- Manual timing with the D-pad, per-file memory. Local files (content://, file://): OpenSubtitles hash and MKV
  index read from the file; a subtitle file next to the video wins.
- Faster stream start: OkHttp with Happy Eyeballs (IPv6/IPv4 race) and no sidecar `.srt` probes on streams.
- Modern controls (PlayerSkin): buttons bottom left, times next to a rounded accent time bar, accent color and
  time format as settings, remote-friendly focus; small notices top right.
- Started by Stremio: Stremio's loading screen stays visible until the first frame (black first for HDR/DV).
- Watch progress for Stremio (optional login): Stremio's TV app mishandles external player results
  (Stremio/stremio-bugs#2892), so from 90% a completion result is sent (Stremio marks watched / plays next) and
  below that the position is written to the account; resume reads the account.

## Using it

- Settings → "Online subtitles": languages, automatic search, prefer built-in, auto sync, OpenSubtitles.com;
  "Stremio": account login and progress sync; "Player look": modern controls, accent color, time format.
- In the player: long press the subtitle button → choose subtitle, adjust timing, auto sync.
- Logs: `adb logcat -s AutoSubs` (stream tokens are redacted).

## Code

- `subs-core/` (Kotlin, no Android dependencies, package `autosubs.core`): release name parsing, IMDb lookup,
  subtitle sources and ranking, auto sync, MKV index reader, frame rate correction, HTTP (OkHttp). Meant to be
  reused by a desktop version or a client app. Unit tests: `./gradlew :subs-core:test` (tests with real subtitles
  use local test data from `~/Desktop/justplayer/testdata`, or `-Dautosubs.testdata=<dir>`, and are skipped
  without it).
- `app/src/main/java/com/brouken/player/subs/`: the Android side (player integration and drawing, settings,
  Media3 subtitle parsing, OkHttp data source for playback).

Changes to upstream files are kept to a few lines (`PlayerActivity`, `SettingsActivity`, `app/build.gradle`,
`settings.gradle`, app name strings) so upstream updates merge easily:

```
git fetch upstream && git merge upstream/master
```

## Builds

`.github/workflows/autosubs-build.yml` builds a signed release APK on every push (pre-release
`build-<branch>`) and a release for tags `subs-v*`. Signing secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`.
