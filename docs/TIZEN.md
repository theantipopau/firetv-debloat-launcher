# Samsung Tizen notes — 75TU8000 (2020 Crystal UHD)

Recon run 2026‑10‑29 against `192.168.1.45` (`UA75TU8000WXXY`, platform code `20_NIKEL_UHD_BASIC`,
Tizen 2020, 3840×2160, Wi‑Fi only). Everything below is measured on that set, not assumed from
other models. Tooling: [tools/tizen.py](../tools/tizen.py).

## The short version

The launcher in `launcher/` cannot run here. No shell, no package list, no overlays, no Home-key
hook. What we can do is a **signed Tizen web app used as a dashboard**, opened by the TV itself on
power‑on, plus network-level ad removal. Ads: largely fixable. Boot: mostly physics. App switching:
capped by the 2020 SoC.

## What the TV exposes

| Port / protocol | State | Notes |
|---|---|---|
| 8001 REST | open | **Only `/api/v2/` answers.** Other paths hang rather than 404 — ask it once, not in a loop. |
| 8002 websocket | open | Remote-control channel. **`wss://` only**; a plain `ws://` handshake is closed. |
| 8080 HTTP | open | Unidentified Samsung web service, `/` returns 404. Not needed so far. |
| 9197 DLNA | open | MediaRenderer. Reports `Tizen,Y2020,WebURIPlayable,…` — no Art Mode, not a Frame. |
| 26101 `sdb` | closed | No developer/shell access, so no installed-app list from outside, no screenshots, no file push. |
| SSDP / UPnP | silent | No `RemoteControlReceiver` advertisement, unlike 2018-era sets. |

From `/api/v2/`: `TokenAuthSupport true`, `remote_available true`, `remote_voiceControl true`,
`FrameTVSupport false`, `networkType wireless`. `firmwareVersion` comes back as the literal string
`Unknown` — read the real version from Support → About This TV when needed.

## The control channel, as it behaves in practice

- The TV accepts a socket, completes the TLS handshake and returns `101 Switching Protocols`
  immediately. It then pings every few seconds to keep the session alive, which is why a naive
  client hangs forever instead of failing: silence is not an error until you impose a deadline.
- Pairing issues a token (`ms.channel.connect` → `data.token`). `tools/tizen.py` caches it in
  `tools/.tizen_token` (git‑ignored).
- **Keys send fine.** `KEY_*` commands go through as expected.
- **`ed.installedApp.get` is ignored** — the client list in the connect event shows
  `"isHost": false` for every client we opened. Working assumption: this firmware only answers the
  app list for its host client, which is the first one to connect after a power‑on. Symptom to
  expect: keys work, app list times out. Untested until we connect once immediately after a reboot.
- In practice the set tolerates roughly one client at a time. A killed socket can leave the channel
  wedged (handshake fine, no frames) until the TV is power‑cycled. Keep connections short and close
  them cleanly.

```sh
export TIZEN_HOST=192.168.1.45     # or write the IP to tools/.tizen_host (it does this for you)
python tools/tizen.py info         # model, MAC, power state        (works)
python tools/tizen.py key KEY_HOME # one remote key                 (works)
python tools/tizen.py launch 3201907018784
python tools/tizen.py watch        # timestamps power/network changes
python tools/tizen.py wol          # magic packet; Wi-Fi WoL is unreliable on this era
```

## Consequences for the project

| Feature in this repo | Verdict on the TU8000 |
|---|---|
| Home-key service, cover screen, boot receiver | **Gone.** Nothing can intercept or draw over Samsung's UI. |
| Alert cards over a playing app | **Gone.** No `SYSTEM_ALERT_WINDOW` equivalent. Cards only while our app is on screen. |
| App dock / launching | Works via `ed.apps.launch`, **but app IDs must be harvested from the TV**. Do not guess them — a wrong ID silently does nothing. |
| App icons | `getAppsInfo` style icon paths are not readable from outside. Bundle our own icons or use text tiles. |
| Wallpaper slideshow, clock, greeting, info panel | Straight port. The set is designed at 1920×1080 and scaled; the 12 MB wallpaper set fits 1:1. Chromium ~69: no optional chaining, no `??`. |
| Screensaver | Cannot replace Samsung's. In-app idle screen only. This set has no Ambient Mode (QLED/Frame only), so no art-mode shortcut either. |
| Debloat script (`pm disable-user`) | **Impossible.** No shell, no package manager. |
| Sleep timer / nightly reboot / WoL | Sleep timer via `KEY_POWER` or `tizen.py wol`. This set is on **Wi‑Fi**, so WoL depends on "Power On with Mobile" and is often flaky. |

## Plan 1 — ads (biggest win, do this first)

Full walkthrough, copy-paste blocklist and rollback: **[TIZEN-ADS.md](TIZEN-ADS.md)**.

**Step 1: settings walk, individually, photographing each original state.** On 2020–2021 firmware
the consents live under `Settings → Terms & Privacy` (not the later "General & Privacy"), and the
boot behaviour under `Settings → General → Smart Features`.

- Autorun Samsung TV Plus → **off**
- Viewing Information Services (ACR) → **off**
- Interest-based Advertising → **off**
- Voice wake-up → **off**
- Remove Samsung TV Plus from the Home row and from the channel list

**Step 2: DNS, on the router / Pi-hole / AdGuard.** This is what actually removes the sponsored
strip, and it doubles as a boot‑time win because dead hosts fail instantly instead of timing out.
Start with `samsungads.com` and its subdomains, plus `rtb.adgrx.com` and `tvx.adgrx.com` — the
exchange behind the Home banner. `samsungacr.com` is a second tier, and `samsungtvplus.com` kills
TV Plus outright, so only add that if you do not watch it.

Then filter the query log by `192.168.1.45` for a week and add only what is clearly ad or telemetry
traffic. Expect the sponsored row to go **blank rather than vanish** — that is success. Do not block
`samsungcloudsolution.com` / `.net`: `lcprd1` is needed for the app store and `time` for time sync,
and blocking them makes the TV report itself offline. The tiered list, filter formats and rollback
are in [TIZEN-ADS.md](TIZEN-ADS.md).

**Step 3: Autorun Last App** so power‑on lands where you want. Verify it actually fires on this
model; a known complaint across years is it quietly falling back to the last source or HDMI.

What stays: the Smart Hub frame, cached layout slots, the boot logo, and ads inside third‑party apps.

## Plan 2 — boot time

Split it into three clocks, because only two are ours to change.

1. **Press → picture.** 2–4 s from standby; 20–40 s after a true cold boot. If the TV is on a
   switched socket or smart plug, that habit *is* the slow boot — standby on this set is under 1 W.
   Keep it in standby.
2. **Press → usable Home.** The slow one on a TU8000: network-bound Smart Hub on a weak SoC. DNS
   blocking helps (fast failures), Autorun Last App skips it entirely.
3. **Home → app launch.** A factory reset plus removing unused apps is the only lever; the SoC is the
   ceiling.

Measure rather than guess: run `python tools/tizen.py watch` in one window, then power the set off
and on, and it timestamps every reachability and `PowerState` change. Do it once with the remote
(standby) and once from the wall (cold boot) to see which one you have been living with. Firmware:
2020 sets stopped updating around 2023. Record the current version before changing anything; a newer
build is as likely to be slower as faster.

## Plan 3 — the dashboard (only after 1 and 2)

Realistic scope: a dashboard you land on, not a home screen you live in. Rough shape, smallest first.

1. **Static page** at 1920×1080, Chromium‑69‑safe JS, served from Home Assistant or any local web
   server: greeting, clock, wallpaper rotation, app tiles, HA panel, keep‑watching row. Test in a
   desktop browser first — it needs no TV.
2. **App IDs**: harvest them from the TV (needs host status, see above) and record them in the page.
   Include a fallback: if nothing launches, the tile still links out.
3. **Package as a Tizen web app** (`config.xml`, `index.html`), signed with a Samsung certificate
   from Tizen Studio, installed over the TV's developer mode (Host PC IP → Developer mode, then
   `sdb` from a machine on the same LAN). This is the only route to something that appears in the
   TV's own app list.
4. **Autorun Last App** pointed at it.
5. **Optional extras**, each needing its own check: `tizen.tvwindow` for an HDMI‑in tile (proven on
   other models, unverified on 2020), HA‑side sleep timer using `KEY_POWER`, doorbell/alert cards
   shown while the dashboard is open.

Nothing here is a replacement for Samsung's UI, and the honest alternative still stands: a Fire TV
Stick 4K Max in HDMI 1 is faster than anything the TU8000 will ever do on its own, with the TV
demoted to a panel.

## Open questions

- Does the app list answer for the host client after a reboot? (blocks step 2 above)
- Did `KEY_INFO` produce a banner on screen? (confirms keys are actioned, not just accepted)
- Is the TV on a switched socket? Does a Pi-hole/AdGuard/UniFi already exist to filter DNS?
- Does Autorun Last App hold after standby on this firmware?
