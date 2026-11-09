# 1.6 plan

Status: proposed, 9 November 2026. Follows 1.5.5.

## Live TV on the Samsung, from your own M3U and XMLTV

**What:** the Tizen dashboard plays your IPTV channels itself, from your M3U playlist and XMLTV guide, with
no Fire TV or TiviMate in the path. A channel row or "Live now" card opens a full-screen player. The guide
gives now and next for each channel.

**Why:** today the Samsung's "Live now" card only switches the TV to the Fire TV input. Live TV on the
Samsung should not depend on a second device being on.

**How:**

- **Playlist:** fetch the M3U, parse `#EXTINF` lines (name, logo, group, stream URL). Cache it in
  `localStorage` and refresh daily.
- **Playback:** Samsung's `webapis.avplay` plays HLS and most H.264/HEVC streams. Back leaves the player;
  channel up/down zaps.
- **Guide:** fetch the XMLTV and read only the next few hours for the channels in the playlist. XMLTV files
  are large and the 2020 TV is slow, so HA trims the file first (a small script next to `tvhome_keep.py`,
  which already reads the same guides) and the TV reads the trimmed copy.
- **Cross-origin reads:** the widget's `config.xml` needs an `<access origin>` entry for the playlist and
  guide hosts.
- **Secrets:** playlist URLs often carry the provider login. They are baked in at packaging time by
  `tools/package-tizen.py`, as the snapshot URL already is, and never committed.

**Open questions, to settle with a spike on the Samsung first:**

- **Codecs:** whether your provider's streams are all ones AVPlay accepts. Raw MPEG-2 or unusual audio may
  not play.
- **Connection limits:** many providers allow one or two simultaneous streams per account. A second player
  on top of TiviMate could knock one of them off.
- **Not coming:** TiviMate's catch-up, recording and favourites sync. The Fire TV stays the place for those.

**Go / no-go:** at least the channels you actually watch play cleanly on the Samsung. If not, keep the
Fire TV input switch and note why in the README.

**Checks:**

- A channel plays within a few seconds and Back returns to the dashboard.
- The guide's now/next matches TiviMate.
- Idle mode and the screensaver still start while a channel is *not* playing, and never start during playback.
- The package size and memory stay sensible with a large playlist.
