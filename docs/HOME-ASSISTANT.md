# Home Assistant integration (optional)

The launcher works on its own. With Home Assistant (HA) you also get an info panel, "keep watching" cards from Plex,
alert cards over any app, a sleep timer and a nightly reboot. Nothing on the TV holds a token: the TV only reads two small
JSON files that HA writes into its public `www` folder, and HA talks to the TV through its built-in
**Android Debug Bridge** integration.

```
HA automation --writes--> /config/www/tvhome.json -----> launcher polls every 15 s   (info panel + Home-button cover)
HA script     --writes--> /config/www/tvhome_keep.json -> launcher polls every 5 min (keep-watching cards)
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
5. In the launcher config set `ha_json_url` to `http://<ha-ip>:8123/local/tvhome.json` and `keep_json_url` to
   `http://<ha-ip>:8123/local/tvhome_keep.json`, rebuild and reinstall.

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

## 4. Keep watching (Plex)
Add to `secrets.yaml` (never commit it):
```
plex_token: <your Plex token>
plex_url: http://<plex-server-ip>:32400
ha_base_url: http://<ha-ip>:8123
```
[How to find a Plex token](https://support.plex.tv/articles/204059436-finding-an-authentication-token-x-plex-token/).
`tvhome_keep.py` picks the newest part-watched TV episode (library names via env `PLEX_LIBS`, default `TV Shows`) and a
part-watched movie; failing that it shows a "Live TV" card that opens TiviMate. Netflix does not expose watch history, so an
optional **manual** card is available: put `Title|Season line|netflix-title-id` into `input_text.tv_netflix_card`.

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
- Cards not updating: run `shell_command.tvhome_keep` from Developer tools > Actions and read the response.
- Alerts do nothing: the TV must be on and ADB connected (`media_player` not `unavailable`); check the overlay permission.
  The broadcast must come from the TV's shell (see section 5) — an app on the TV cannot send cards.
