# Removing Samsung's ads from a 2020 TU8000

For the 75TU8000 (Tizen 2020, `UA75TU8000WXXY`). Everything here is reversible and needs no
developer mode, no shell and no app — which is just as well, because this set has none of those
([TIZEN.md](TIZEN.md)).

Two halves: **settings** change what Samsung may personalise and collect; **DNS** is what actually
removes the sponsored strip, because that content is fetched at runtime.

## What each half really does

| Lever | What it stops | What it does not stop |
|---|---|---|
| Privacy consents (ACR, interest-based ads) | Data collection and ad personalisation | The ad slots themselves |
| Removing TV Plus from Home | Autoplaying ad-supported channels | Samsung's own banner slots |
| DNS blocking (**the real fix**) | The Home-screen ad exchange and Samsung's ad server | Ads inside YouTube, Plex, etc. |
| Autorun Smart Hub → off | Booting into the ad-laden home screen | Anything inside the apps you open |

Samsung's own notice is blunt about the consent toggles: withdrawing consent "will not reduce or
prevent the delivery of generic or contextual advertisements" and "will not reduce the number of ads
you see". Settings alone will disappoint you. Settings plus DNS will not.

## Part 1 — the settings walk (about 10 minutes)

Photograph each screen before you change it. Samsung resets some of these agreements on firmware
updates, so these photos are also your record of what "before" looked like.

### 1. Privacy consents

`Settings → Terms & Privacy` — that is the 2020–2021 path. (2022 and later moved it to
`Settings → All Settings → General & Privacy → Terms & Privacy`; if you follow a newer guide on this
set you will hunt for a menu that does not exist.) Untick:

- **Viewing Information Services** — Samsung's ACR. On UK firmware the checkbox reads "I consent to
  Viewing Information Services"; your region's wording will be close.
- **Interest-Based Advertising** — "I consent to Interest-Based Advertising".

While you are in the Smart Hub, its `Privacy Choices` item leads to the same two consents.

### 2. Stop booting into the ad screen

`Settings → General → Smart Features → Autorun Smart Hub` → **off**.

This is the one setting that helps both complaints at once: the TV then starts on the last input or
app rather than loading the sponsored home screen. Expect a shorter press-to-something-useful time
purely from skipping Smart Hub.

Also here, if present: **Autorun Last App** → on. Labels and availability drift by region; on some
2020 firmware it lives under `General → Smart Features → Start Screen Option`. Where both exist,
Last App is what decides you land in an app rather than the home screen. This is the same setting a
Tizen dashboard would depend on later, so note whether it exists on your set.

**Autorun Samsung TV Plus**: turn it off if your firmware exposes it (some regions put it under
`General → Smart Features`, others omit it entirely). If there is no such toggle, do not go looking
for a service menu — steps 3 and 4 cover it.

### 3. Remove TV Plus from the Home row

Home → highlight the **Samsung TV Plus** tile → press and hold OK → **Remove** (on some firmware:
Home → scroll to **Edit** → select the tile → Remove). You cannot uninstall it, but it no longer sits
in your face.

If TV Plus channels also pollute the guide: `Channel List → Edit Channels` → untick the TV Plus
channels, or hide the TV Plus source from the list.

### 4. Quiet the voice assistant

`Settings → General → Voice → Voice Wake-up` → **off** (where the set has Bixby). This stops the
always-listening wake word; the mic button on the remote still works.

### 5. Optional: sign out of the Samsung account

`Settings → General → Samsung Account → Sign out`. Less sync traffic, one fewer thing phoning home.
Some apps and the app store want an account, so do this last and be ready to sign back in.

After a firmware update, re-check steps 1, 2 and 4. Samsung re-asks for consents after major
updates.

## Part 2 — the DNS block (where the ads actually disappear)

### Make sure the TV is using your filter

Either is fine; the first is more reliable because the TV cannot drift off it:

1. **Router/DHCP**: give the TV a DHCP reservation and hand out Pi-hole/AdGuard Home as the DNS
   server for the LAN.
2. **On the TV**: `Settings → General → Network → Network Status → IP Settings → DNS setting →
   Enter manually`, and set the primary DNS to your filter.

The TV uses plain DNS, so this works; there is no DoH to bypass it. Make sure the TV is not on a
guest or isolated VLAN that cannot reach the filter.

### Tier 1 — start here, this is the Home-screen ad path

Safe on every report I could find, and enough on its own for the sponsored strip.

```
# Samsung ad servers and the exchange that fills the Home screen
samsungads.com
ads.samsungads.com
config.samsungads.com
events.samsungads.com
samsungadhub.com
ad.samsungadhub.com
rd.samsungadhub.com
rtb.adgrx.com
tvx.adgrx.com
```

`rtb.adgrx.com` and `tvx.adgrx.com` are the exchange behind the Smart Hub/Start Screen banners; one
Pi-hole user reports those two alone cleared the home screen with everything else still working.
Blocking `samsungads.com` and its subdomains covers the rest of Samsung's ad plumbing.

### Tier 2 — ACR uploads (no visible change, slightly less certain)

```
# Automatic Content Recognition upload endpoints
samsungacr.com
log-1.samsungacr.com
log-2.samsungacr.com
log-3.samsungacr.com
log-config.samsungacr.com
```

Turning off Viewing Information Services in Part 1 should already stop this; blocking it is belt and
braces. Add it after Tier 1 has run cleanly for a few days.

### Tier 3 — only if you do not watch Samsung TV Plus

```
samsungtvplus.com
```

This kills TV Plus outright: its channels, its guide entries and its app. If you use TV Plus at all,
skip this tier — the point of it is ad-free viewing, and there is no middle ground.

### Never block these

The blocklist that circulates most widely is aggressive enough to break the TV. Do not paste it
whole. In particular:

| Domain | Why not |
|---|---|
| `samsungcloudsolution.com`, `samsungcloudsolution.net` | **The big one.** `lcprd1.samsungcloudsolution.net` is needed for the app store, Smart Hub connectivity checks and even joining Wi-Fi; `time.samsungcloudsolution.net` is the time sync, and blocking it makes the TV report "no internet" when you open Apps until it retries. Multiple reports of a TV needing a cold boot and an hour to recover. |
| `samsungapps.com` | App installs and updates. |
| `samsungqbe.com` | Reported as required for the app store on some sets. Leave it. |
| `samsungotn.net` | Firmware updates. Block it only if you deliberately want to freeze firmware — see [TIZEN.md](TIZEN.md) on why that is a separate decision. |
| `samsung.com` | Account, setup, support. Block subdomains you have identified, never the parent. |
| `cloudfront.net`, `amazonaws.com`, `doubleclick.net` wholesale | Shared CDNs. Wholesale blocks break streaming apps; `static.doubleclick.net` specifically is usually safe, most of the rest is not. |

If you want more than Tier 1, the safe method is empiricism rather than someone else's list: filter
your Pi-hole query log by the TV's IP, watch for a week, and block only hosts whose names are
clearly advertising or telemetry. [DEBLOAT.md](DEBLOAT.md) describes the same process for Fire TV.

### Formats

Pi-hole (Domains → add, or append to a local blocklist file) and most routers take the bare list
above, one host per line.

AdGuard Home (Filters → Custom filtering rules):

```
||samsungads.com^
||samsungadhub.com^
||rtb.adgrx.com^
||tvx.adgrx.com^
```

Router host filters: some want a leading dot (`.samsungads.com`) to include subdomains, others want
no leading dot and match subdomains anyway. Check yours; a bare domain that only matches itself
leaves the ad subdomains alive.

### Verify

- From a PC using the same filter as DNS: `nslookup samsungads.com` should return `0.0.0.0` or
  `127.0.0.1`, not a public address.
- In the filter's query log, `192.168.1.45` should appear within a minute of waking the TV.
- On the TV, open Home. The sponsored strip should go **blank or grey rather than vanish** — that is
  the request failing, and it is success. If the whole Home screen fails to lay out, you have gone
  too far; start with the rollback below.

The TV caches DNS, so power-cycle it (standby → on) after changing filters rather than judging
immediately.

## Part 3 — rollback

Everything here undoes cleanly.

**DNS.** Delete the Tier 1/2/3 entries, or disable the blocklist. The TV may hold the cached answer
until it next reboots, so put it into standby and back on. If you set DNS manually on the TV, return
it to `Settings → General → Network → Network Status → IP Settings → DNS setting → Obtain
automatically`.

**Settings.** Re-tick Viewing Information Services and Interest-Based Advertising under
`Settings → Terms & Privacy`; turn Autorun Smart Hub back on; re-enable Voice Wake-up. Re-adding the
Samsung TV Plus tile is done from Home → Edit → add app.

**If apps break after a block.** Re-enable in the opposite order to the way you added them, and
reboot the TV between attempts. If the app store or Wi-Fi is unhappy, whitelist in this order:
`time.samsungcloudsolution.net`, then `lcprd1.samsungcloudsolution.net`, then the whole
`samsungcloudsolution.net`. The first two usually suffice; the third definitely works but lets
telemetry through.

**If the TV looks offline after a cold boot**, that is the time-sync symptom above: whitelist
`time.samsungcloudsolution.net`, then power-cycle. It can take one retry cycle to come back.

## What will still show ads

- The Smart Hub frame itself, cached layout slots, and the boot logo.
- Samsung's "Recommended"/featured slots that ship inside the firmware rather than being fetched.
- Every ad inside YouTube, Plex's free tier, and any other third-party app. Those are the app's
  inventory, not Samsung's, and DNS filtering at this level will not touch them.
- Anything served from the same CDN host as legitimate content — which is why the blocklist is
  short.

Realistic expectation: the sponsored strip and the TV Plus clutter go, boot lands somewhere useful,
and roughly the rest of what annoys you is gone. If the goal is a completely ad-free, fast UI, the
honest answer remains a Fire TV Stick in HDMI 1 with the TV as a panel — see [TIZEN.md](TIZEN.md).

## Sources

- [Samsung TV ACR and ad settings by software version](https://optoutof.com/tv/samsung/settings/) —
  the 2020–2021 `Settings → Terms & Privacy` path and the quoted notice wording.
- [Samsung Smart-TV blocklist gist and its comment thread](https://gist.github.com/wassname/b594c63222f9e4c83ea23c818440901b)
  — `samsungcloudsolution` breakage reports, `rtb/tvx.adgrx.com` finding, which blocks needed whitelisting.
- [Pi-hole on a noisy Samsung TV](https://mattcasmith.net/2020/02/15/pi-hole-samsung-smart-tv/) — the
  query-log-first method.
