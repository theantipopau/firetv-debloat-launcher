# The Tizen home screen

The layout from the reference shot, running as a web page: clock, weather/energy/climate
stack, the now-playing card, `All apps` / `Settings`, and one glass dock with our apps,
Live TV and Samsung TV Plus. 1920×1080, scaled to fit, Chromium 69-safe (Tizen 5.5).

Runs in any browser, which is the point — design and test it on a laptop, then package it.

```sh
python -m http.server 8791          # from the repo root
open http://127.0.0.1:8791/tizen/
```

The wallpapers live in `tizen/wallpapers/`, not the launcher's set: a widget cannot reach outside
its own folder, so the page is self-contained before packaging.

## Data: it reads your `tvhome.json`

There is no second data format. The page reads the file `homeassistant/tvhome.yaml` already
writes every 15 seconds ([HOME-ASSISTANT.md](../docs/HOME-ASSISTANT.md)):

```js
snapshotUrl: "http://192.168.1.10:8123/local/tvhome.json"   // config.js
```

Until that is set it reads `data/tvhome.sample.json` and says **sample data** in the corner. If
the URL cannot be read it says **no data** and draws only the clock and the dock. Nothing on the
screen is invented: every element is a key from the snapshot, and an empty key removes its own
element, exactly like the Fire TV panel. The packaged widget does not ship the sample, so on the
TV that corner says **no data** until the URL is set.

| on screen | `tvhome.json` | how it reads | hides when |
|---|---|---|---|
| weather row | `temp`, `condition`, `inside` | `24° Partly cloudy · 21.6° inside` | all three empty |
| battery pill | `battery` | `Battery 72%`, green → amber at 45% → red at 20% | empty |
| solar pill | `solar` | `Solar 3.2 kW` | empty |
| load pill | `house` | `Using 1.4 kW` | empty |
| bins line (amber) | `bins` | as sent | empty |
| next line | `next`, `next_at` | `Next: 6:30pm Training · in 2 h` | `next` empty |
| rain line | `rain` | as sent | empty, or starts "No rain" / "Dry" |
| presence | `people`, `people_names` | `Matt & Caitlyn are home` | `people` empty |
| solar day | `energy_today`, `battery_left` | `18.4 kWh today · 9 h 12 m left` | both empty |
| sun | `sunrise_at`, `sunset_at` | `Sunrise 5:03 AM · Sunset 6:07 PM` | both empty |
| wind | `wind`, `wind_unit`, `humidity` | `14 km/h · 62% humidity` | both empty |
| now-playing card | `media_title`, `media_artist`, `media_art` | the card | `media_title` empty |
| now-playing card | `plex` | `Chuck · S01E02 · Plex` | both empty |

`maxLines` (default 3) is the only taste decision: lines fill in the order bins → next → rain →
presence → solar day → sun → wind, and the rest stay off the screen. Raise it if your snapshot
is quiet; the stack grows downwards towards the dock.

`ages` works as it does on the Fire TV: a value whose sensor is older than 15 minutes dims to
45% instead of pretending to be live.

`feels` and `ac` arrive in the snapshot but are not drawn — the feed carries no set-point, so
"air con cooling" would be a fragment, and `feels` would only crowd the temperature line.

### Getting the snapshot into the page (CORS)

Pick one:

1. **Serve the page from Home Assistant** — same origin, no CORS at all. Copy `tizen/` to
   `/config/www/tizen/`, set `snapshotUrl: "../tvhome.json"`, then open
   `http://<ha-ip>:8123/local/tizen/`.
2. **Let HA answer other origins** — in `configuration.yaml`:

   ```yaml
   http:
     cors_allowed_origins:
       - "*"          # or the exact origin the page runs on
   ```

   restart HA, and `http://<ha-ip>:8123/local/tvhome.json` can be read from anywhere on the LAN.
   A packaged Tizen widget has its own origin (often `null`), which is why most people end up with
   `"*"`; the snapshot itself is already readable by anyone who can reach port 8123.

Use the HA machine's IP, not `homeassistant.local` — the TV does not always resolve `.local`.

## Apps: found on the TV, not pasted in

Tizen app ids are region- and firmware-specific (Netflix is `3201907018784` here, something else
elsewhere), so copying a list off the internet is a trap. On the Tizen runtime the page asks the
TV for its installed apps (`tizen.application.getAppsInfo`), matches each dock tile by the `names`
in `config.js`, and caches the id it finds. A failed launch clears the cache and the next tap
looks again.

**A tile only appears when the TV reports the app.** No match, no tile — the dock is an inventory,
not a wish list, and the same filter applies to the `All apps` sheet. A cached id is dropped when
the TV stops reporting it, so uninstalling an app removes its tile. `Fire TV` and `Live TV` are the
exception: they are remote-key sources, not apps, so they always stay.

Two deliberate fallbacks: in a plain browser (no Tizen API) and when `getAppsInfo` fails, every
configured tile is shown, because the page cannot know any better. An empty app list is treated
the same way rather than blanking the dock. `id:` still pins an app if you want to bypass name
matching.

## Sources: Fire TV, Live TV and TV Plus

The dock opens with the sources, then the apps. A web page cannot switch the TV's input or open
the tuner, so **Fire TV** and **Live TV** press a remote key instead: `KEY_HDMI1` (the port the
Fire TV is on; change it in `config.js`) and `KEY_TV` for the aerial. The tile posts
`?key=KEY_...` to `controlUrl`, and Home Assistant presses the key through its Samsung TV
integration. The TV's own viewer then takes over, so channels, the guide and HDMI-CEC behave
exactly as they do from the Samsung remote.

In HA, with the Samsung Smart TV integration set up (it creates `remote.<your_tv>`):

```yaml
automation:
  - alias: TV Home - press a source key
    trigger:
      - platform: webhook
        webhook_id: tvhome_key_x7Rq2      # make up your own; it is the only secret
        allowed_methods: [POST]
        local_only: true
    condition:
      # Only the keys the dock sends, so the webhook cannot power the set off.
      - condition: template
        value_template: "{{ trigger.query.key in ['KEY_HDMI1','KEY_HDMI2','KEY_HDMI3','KEY_TV'] }}"
    action:
      - service: remote.send_command
        target: { entity_id: remote.samsung_tv }
        data: { command: "{{ trigger.query.key }}" }
```

and in `config.js`: `controlUrl: "http://<ha-ip>:8123/api/webhook/tvhome_key_x7Rq2"`. Without HA,
anything that forwards the key to [`tools/tizen.py`](../tools/tizen.py) works
(`python tools/tizen.py key KEY_HDMI1`).

TV Plus is a normal app, so it is resolved by name like the rest (`"TV Plus"` /
`"Samsung TV Plus"`) and hides itself if this set does not have it.

The catch-up apps are matched the same way: ABC iview, SBS On Demand, 9Now. TiviMate is Android
only, so there is no TiviMate tile to add here — on this TV the live sources are the tuner
(`Live TV`) and Samsung TV Plus. Any other live app in the Tizen store can be added to `apps`
by name and will appear only where it is installed.

## Packaging

This folder is already a Tizen widget: `config.xml` (package `TVHOME0001`, app id
`TVHOME0001.TVHome`, `tv-samsung` profile), `icon.png`, and the wallpapers beside the page. Only
these files ship; the README and the sample snapshot do not.

```sh
python tools/package-tizen.py --check        # stage + validate, no Tizen Studio needed
python tools/package-tizen.py --snapshot-url http://192.168.1.20:8123/local/tvhome.json --profile tvhome
```

The script stages `build/tizen/`, rewrites the snapshot URL in the copy (the repo keeps the sample
fallback), validates the manifest, signs with Tizen Studio, and leaves `build/TVHome.wgt`. Add
`--install 192.168.1.45 --run` to put it on the TV.

Signing needs Tizen Studio with the TV extensions, a Samsung certificate, and developer mode on the
set; the full walkthrough, the TV's developer-mode steps, the HA CORS settings and the rollback are
in **[INSTALL.md](INSTALL.md)**.

## What it deliberately does not do

No system UI, no overlay over other apps, no home-key capture — the TV gives us none of that
([TIZEN.md](../docs/TIZEN.md)). This is a dashboard you land on, opened by Autorun Last App.
