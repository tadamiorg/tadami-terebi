# CLAUDE.md

## Project role

**tadami-terebi** is the Android TV **Cast Connect receiver** app for the Tadami
ecosystem.

- Package: `com.sf.tadami.terebi` (debug variant appends `.debug`)
- Cast App ID: `DA2F4B1A`

## Sibling project (sender)

The phone app lives at `/home/sfoucheur/dev/utils/TadamiOrg/tadami`. Its Cast Connect
integration is on branch **`feature/tv-cast`** — see its `TADAMI_TV_CAST.md` for the
full integration notes.

## Branch / push rules

- **tadami-terebi** (this repo): commit and push on **`main`**.
- **tadami** (phone): always stay on **`feature/tv-cast`** — never switch its branch.

## Sender contract

What the phone sends in `EpisodeActivity.loadRemoteMedia()` (the receiver must honor
all of it):

- `contentUrl` — either a direct stream URL, or the phone-local Http4k proxy URL
  `http://<phoneIp>:8000?url=<enc>&headers=<enc-json>` for header-bound streams.
  The receiver plays `contentUrl` **as-is**; header injection stays on the phone.
- Subtitle `MediaTrack`s.
- `MediaMetadata`: title, episode, thumbnail.
- Resume position via `currentTime`.
- `customData`: `availableSources`, `selectedSource`, `episodeId`, `seen`, ...

Phone-side remote controls (play/pause, seek, ±, +85s, subtitle change,
episode/source switch via re-`load`) must all drive the receiver.

## Testing notes

- Cast Connect requires a **physical** Android TV / Google TV registered in the
  Google Cast Developer Console (app registration for the package + test-device
  serial registration, then TV reboot). The standard ATV emulator cannot exercise
  Cast Connect.
- Receiver id mismatch caveat: the phone **debug** build uses `85AB1CC3`
  (`app/src/debug/res/values/config.xml`), while **release** uses `DA2F4B1A`.
  For end-to-end tests, align the triple {sender receiver App ID, console-registered
  ATV package, installed ATV package}.
