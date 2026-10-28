# Home Assistant integration (optional)

The launcher works on its own. With Home Assistant (HA) you also get an info panel, "keep watching" cards from Plex,
alert cards over any app, a sleep timer and a nightly reboot. Nothing on the TV holds a token: the TV only reads two small
JSON files that HA writes into its public `www` folder, and HA talks to the TV through its built-in
**Android Debug Bridge** integration.

```
HA automation --writes--> /config/www/tvhome.json -----> launcher polls every 15 s   (info panel + Home-button cover)
HA script     --writes--> /config/www/tvhome_keep.json -> launcher polls every 5 min (keep-watching cards)
HA script     --writes--> /config/www/tvhome_new.json  -> same poll          (rotating live-now / new-on-Plex tile)
HA scripts    --adb broadcast--> TV ----------------------> alert card over whatever is playing
```

## 1. Connect the Fire TV to HA
Settings > Devices & services > Add > **Android Debug Bridge**: host = the TV's IP, device class = Fire TV, port 5555.
Give the TV a fixed IP (DHCP reservation outside the DHCP pool). Accept the "Allow USB debugging" prompt on the TV.
This gives `media_player.<name>` which the scripts below use. If the TV's IP changes, remove and re-add the entry.

## 2. Install the package
1. Copy `homeassistant/tvhome.yaml` to `/config/packages/tvhome.yaml` and make sure `configuration.yaml` has
   `homeassistant:` / `packages: !include_dir_named packages`.
2. Copy `homeassistant/tvhome_keep.py` to `/config/tvhome/tvhome_keep.py`.
3. Edit the lines marked `CHANGE` in `tvhome.yaml` (your Fire TV entities, your weather / power sensors).
4. Developer tools > YAML > Check configuration, then restart HA once (a new `shell_command` needs a restart).
5. In the launcher config set `ha_json_url` to `http://<ha-ip>:8123/local/tvhome.json`, `keep_json_url` to
   `http://<ha-ip>:8123/local/tvhome_keep.json` and (optionally) `new_json_url` to
   `http://<ha-ip>:8123/local/tvhome_new.json`, then rebuild and reinstall.

## 3. The info panel (`tvhome.json`)
Keys (all optional; an empty or missing value hides that row):

| key | example | shown as |
|---|---|---|
| `temp`, `feels` | `22`, `21` | big temperature, "feels" |
| `condition` | `partly cloudy` | weather emoji and text |
| `rain` | `Dry right now - 0.0 mm today` | rain line |
| `solar`, `battery`, `house` | `3.2`, `72`, `1.4` | kW, %, kW |
| `inside`, `ac` | `24.5`, `cool` | indoor temp, air-con state |
| `bins` | `General bins - out tonight` | bin line |
| `next` | `Next: 6:30pm Training` | next calendar event |

The same file feeds the cover screen shown right after you press Home.
Update speed is limited by your sensors (cloud inverter integrations often update every 5 minutes; local Modbus/MQTT is much faster).

## 4. Keep watching, live now and new on Plex
Add to `secrets.yaml` (never commit it):
```
plex_token: <your Plex token>
plex_url: http://<plex-server-ip>:32400
ha_base_url: http://<ha-ip>:8123
epg_urls: https://<provider>/epg.xml,https://i.mjh.nz/au/Brisbane/epg.xml    # optional, see "Live now"
```
Set both to an IP, not a hostname: `ha_base_url` becomes the address inside every image URL the TV downloads,
and Fire OS does not always resolve `homeassistant.local`. The script falls back to `http://localhost:32400` for Plex
and `http://homeassistant.local:8123` for HA when a key is missing, and prints a warning when it does.

[How to find a Plex token](https://support.plex.tv/articles/204059436-finding-an-authentication-token-x-plex-token/).
`tvhome_keep.py` picks the newest part-watched TV episode (library names via env `PLEX_LIBS`, default `TV Shows`) and a
part-watched movie; failing that it shows a "Live TV" card that opens TiviMate. Netflix does not expose watch history, so an
optional **manual** card is available: put `Title|Season line|netflix-title-id` into `input_text.tv_netflix_card`.

The same run writes `tvhome_new.json`: five recent film releases plus five episodes that aired in the last 60 days, one
card per series. It is deliberately not just "recently added" — a 1994 film imported this week or an old series being
backfilled stays out, because the tile is meant to show what is genuinely new. With `new_json_url` set, the launcher puts
a wide **NEW ON PLEX** banner at the head of the keep-watching row and crossfades through that list every seven seconds;
it holds still while focused, so OK always opens what you can see. It spans two card widths, so the row shows one resume
card beside it (five when the banner is off). Point it at
`http://<ha-ip>:8123/local/tvhome_new.json`. Optional env: `PLEX_NEW_LIBS` (comma-separated section names; default every
movie/show section), `PLEX_NEW_MOVIES` and `PLEX_NEW_EPISODES`. The list only downloads artwork again when a title is
added or removed, so a quiet library costs a couple of API calls per run.

### Live now
Put the XMLTV guides (the same URLs TiviMate uses) in `epg_urls`, comma separated, and the head of the tile becomes
what is on right now: live sport first, then one film. Each card gets a red-dot **LIVE ON TIVIMATE** chip and a
"channel · until 21:35" caption; OK opens TiviMate, at whatever channel it was last on — Android does not let another
app deep-link into TiviMate's player. A card carries its end time, so the TV drops it once the programme has finished
rather than waiting for the next ten-minute refresh. If the guide has no still for a programme, the channel logo is
shown contained on the card instead of being cropped to fill it.

What counts: sport categories (cricket, rugby, football/AFL, golf, racing, the US leagues) with a live tag, a "v" fixture
or a named sport, plus films in a Movie category. Replays, classics, highlights, countdowns and studio shows are
dropped, as are 24/7 filler channels, shopping and the empty "Stan Sport Event 12" placeholders. The same match on a
simulcast channel appears once. `LIVE_COUNT` sets how many live cards are mixed in (default `4`).

The guides are cached in `/config/.tvhome_epg/` and downloaded again only when the server says they changed; the iptv4u
guide is 11 MB, so that matters. Point `EPG_URLS` at a local file to try a guide before committing to it.

| setting | default | what it does |
|---|---|---|
| `PLEX_LIBS` | `TV Shows` | sections the keep-watching card may pick an episode from |
| `PLEX_COUNT` | `1` | how many episode cards to build |
| `PLEX_NEW_LIBS` | all movie/show sections | sections that feed the new-on-Plex tile |
| `PLEX_NEW_MOVIES` | `5` | film cards (released this year or last only) |
| `PLEX_NEW_EPISODES` | `5` | episode cards, one per series |
| `PLEX_NEW_DAYS` | `60` | an episode counts as new if it aired inside this many days |
| `LIVE_COUNT` | `4` | live cards mixed in ahead of the new-on-Plex ones |
| `EPG_URLS` | `epg_urls` secret | guide URLs, if you would rather set them in the shell command |

## 5. Alerts over any app
```
service: script.tv_alert
data: {title: "Doorbell pushed", text: "Someone is at the front door", kind: doorbell, seconds: 10}
```
`kind`: `doorbell` (red), `motion` (amber), `warn`, `info`. It shows on every Fire TV that is on, top right, with a countdown bar.
The launcher needs the "display over other apps" permission (the install script grants it:
`adb shell appops set com.mihir.tvhome SYSTEM_ALERT_WINDOW allow`). Use it for doorbells, bins, finished washing, timers.
Only the TV's shell (ADB, which is what the command above uses) and system apps can send a card: the receiver in
`launcher/AndroidManifest.xml` requires `android.permission.DUMP`, which an ordinary installed app does not hold, so nothing
on the TV can spoof a card. If you have a sender that is not the shell and stops working, remove that attribute and rebuild.
Snapshots are downloaded with a size cap and shrunk to a sane resolution, and cards are rate-limited, so a stuck
integration cannot flood the screen or exhaust the TV's memory.
Brand badges (`brand: google`) need logo files you add yourself under `launcher/profiles/<name>/assets/brands/<brand>.png`.

## 6. Other helpers
- `script.tv_sleep_timer` (`tv`, `minutes`): turns a TV off after N minutes; running it again restarts the timer.
- Nightly reboot at 4am keeps a Stick snappy.
- Anything that sends an ADB command can use the TV: `androidtv.adb_command` with `am start -n <package>/<activity>` etc.

## Troubleshooting
- Panel empty: open `http://<ha-ip>:8123/local/tvhome.json` in a browser. 404 means the automation or shell command is not running
  (check the HA log for `write_tvhome`). Templates must use `base64_encode` (not `b64encode`), and templated shell commands
  need `sh -c '...'` to use pipes.
- Cards not updating: run `shell_command.tvhome_keep` from Developer tools > Actions and read the response. A line
  starting `warning: no plex_url` or `warning: no ha_base_url` means that key is missing from `secrets.yaml`.
- Cards show titles but no artwork (blank gradient, or the new-on-Plex banner with no still): the image URLs in the JSON
  point at a host the TV cannot reach. Check `ha_base_url` — it must be `http://<lan-ip>:8123`, not `homeassistant.local`.
- Alerts do nothing: the TV must be on and ADB connected (`media_player` not `unavailable`); check the overlay permission.
  The broadcast must come from the TV's shell (see section 5) — an app on the TV cannot send cards.
