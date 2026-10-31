# Putting TV Home on the TU8000

A signed Tizen widget, installed over the LAN. It appears in the TV's own app list as **TV Home**,
and `Autorun Last App` can open it on power-on. It does not replace Samsung's UI — nothing can on
this set ([TIZEN.md](../docs/TIZEN.md)).

Tested here: the staging, manifest validation, icon, URL injection and page rendering. **Not tested
here:** signing, `sdb` and the install itself — those need your Samsung account, Tizen Studio, and
developer mode on the set. Steps 3–5 are the untested ones, written from Samsung's documented flow.

The TV is `192.168.1.45`; port `26101` was measured **closed** before developer mode, which is why
step 2 comes before step 5.

## 0. What the package contains

`python tools/package-tizen.py --check` stages only what a widget may ship:

```
config.xml     manifest: package TVHOME0001, app id TVHOME0001.TVHome, tv-samsung profile
index.html     the screen
style.css      layout
dashboard.js   behaviour: clock, snapshot, dock, sheets
config.js      snapshot URL, wallpapers, app names
icon.png       512x512 clock ring, amber dot (same accent as the screen)
wallpapers/    09, 01, 05 — bundled, because a widget cannot read outside its own folder
```

No `README`, no sample snapshot, no `.project`/`.settings` — Tizen rejects packages carrying IDE
metadata ("unsigned file found"), and the TV must never fall back to demo values.

## 1. Tizen Studio with the TV extensions

Install Tizen Studio from <https://developer.tizen.org/>, then in **Package Manager → Extension SDK**
add:

- **TV Extensions** — provides the `tv-samsung` profile in `config.xml`
- **Samsung Certificate Extension** — provides the Samsung certificate type

Check the CLI (adjust the path to where you installed it):

```sh
"C:\tizen-studio\tools\ide\bin\tizen.bat" version
"C:\tizen-studio\tools\sdb.exe" version
```

The packaging script finds them on `PATH` or under `TIZEN_STUDIO`. If yours is somewhere else:

```sh
set TIZEN_STUDIO=C:\tizen-studio
```

## 2. Developer mode on the TV

1. Home → **Apps** — the full-screen app store, not the Home rail. On 2020 sets it is the
   `Apps` entry in the left-hand menu. The code only works there.
2. Type **1 2 3 4 5**. A Developer mode dialog opens.
3. Developer mode **On**, and set **Host PC IP** to this PC's LAN address. On this machine it is
   `192.168.1.5` (`ipconfig` → IPv4). The TV and the PC must be on the same subnet.
4. The TV restarts. Developer mode is not active until it has.

**If nothing happens, it is almost always the remote.** The Fire TV remote talks to the TV over
CEC and has no digit keys, so 12345 never reaches it. Use the Samsung remote that came with the
set — or send the five digits over the network, with the TV already sitting on the Apps screen:

```sh
python tools/tizen.py devmode
```

That opens one remote-control channel and clicks `KEY_1` … `KEY_5`; the dialog appears just as it
would from the keypad. (The token from the earlier pairing is reused, so it will not ask again.)
If you run it while watching a channel instead of Apps, the digits are read as a channel number
and nothing useful happens — be on Apps first.

Verify the port that was closed during recon is now open:

```sh
"C:\tizen-studio\tools\sdb.exe" connect 192.168.1.45:26101
"C:\tizen-studio\tools\sdb.exe" devices
# 192.168.1.45:26101   device   TV
```

If it times out: developer mode is off, the Host PC IP is wrong, or the TV has not rebooted since.

## 3. Certificate — the only step that needs your Samsung login

Tizen Studio → **Tools → Certificate Manager** → **+**.

1. Profile name: `tvhome` (that is the `--profile` value below).
2. Device type: **Samsung**, not Tizen.
3. **Author certificate** → create new → name, organisation, email, password → sign in with your
   Samsung account.
4. **Distributor certificate** → **Public** privilege. Connect the TV in **Device Manager** first
   (Tools → Device Manager → Remote Device Manager, IP + `26101`) so the DUID fills itself in; if it
   does not, copy the DUID from the device's detail pane.
5. Sign in again. Files land in `C:\Users\<you>\SamsungCertificate\tvhome\` — they are yours alone;
   nothing here needs them committed.

Then, still in **Device Manager**, right-click the TV → **Permit to install apps**. This is the step
people skip; without it every install fails certificate validation even though the cert is correct.

## 4. Build and sign

```sh
python tools/package-tizen.py --snapshot-url http://192.168.1.20:8123/local/tvhome.json --profile tvhome
```

`192.168.1.20` is a stand-in for your HA box; use its real address. Run from the repo root.

What it does, in order:

- copies the widget files to `build/tizen/` (the repo copy is untouched);
- rewrites **only the shipped** `config.js` so `snapshotUrl` points at Home Assistant;
- validates the manifest, package id, content/icon paths, wallpapers and privileges;
- runs `tizen package -t wgt -s tvhome -- build/tizen`;
- leaves the signed artefact at `build/TVHome.wgt`.

Use `--check` to do everything except signing, on a machine without Tizen Studio:
`python tools/package-tizen.py --check`.

The manual equivalent, if you prefer Tizen Studio's UI: create a **Tizen Web Project** with the TV
profile, replace its `config.xml`, `index.html`, `style.css`, `dashboard.js`, `config.js`, `icon.png`
and `wallpapers/` with the staged ones, then **Build Signed Package**. The CLI route is less
error-prone — the GUI has been known to rewrite `config.xml`.

## 5. Install and launch

```sh
python tools/package-tizen.py --snapshot-url http://192.168.1.20:8123/local/tvhome.json --profile tvhome --install 192.168.1.45 --run
```

Or by hand:

```sh
"C:\tizen-studio\tools\sdb.exe" connect 192.168.1.45:26101
"C:\tizen-studio\tools\ide\bin\tizen.bat" install -n build\TVHome.wgt -s 192.168.1.45:26101 -- build\tizen
"C:\tizen-studio\tools\ide\bin\tizen.bat" run -p TVHOME0001.TVHome -s 192.168.1.45:26101
```

It now sits in **Apps → TV Home**. `sdb`-installed apps do not autostart by themselves.

## 6. Let Home Assistant answer the widget

The widget runs from a `file://` origin, which Home Assistant treats as `null`. Two things must be
true or the screen will say **no data**:

1. **CORS.** In `configuration.yaml`:

   ```yaml
   http:
     cors_allowed_origins:
       - "*"
   ```

   Restart HA. (The snapshot is a LAN file; anyone who can reach port 8123 can already read it.)

2. **A URL the TV can reach.** Use the HA machine's IP — `http://192.168.1.20:8123/...`, not
   `homeassistant.local` (the TV's mDNS is unreliable). If HA is behind a self-signed HTTPS
   certificate, use plain `http://` on the LAN instead: the widget will reject the certificate.

The packaged page never shows the sample: that file is not in the widget. It says **no data** until
the fetch succeeds, then stops showing the chip.

## 7. Open it on power-on

Settings → General → **Smart Features** → **Autorun Last App** → On. On 2020 firmware this is the
path; confirm it on the set. Test with standby (remote power) rather than a cold boot — a known
behaviour across years is falling back to the Home screen or the last source.

Alternative: the TV's own remote key `KEY_HOME`, then pick **TV Home** — `python tools/tizen.py key
KEY_HOME` does that from a shell.

## Rollback

```sh
python tools/package-tizen.py --uninstall 192.168.1.45
# or
"C:\tizen-studio\tools\ide\bin\tizen.bat" uninstall -p TVHOME0001 -s 192.168.1.45:26101
```

Then turn Developer mode off (Apps → 12345) and reboot. Nothing else on the set was changed by this
install; the certificate lives on the PC, and deleting the `tvhome` profile in Certificate Manager
removes it. If a bad build is autostarting and you cannot get to Apps, power-cycle the TV — Samsung's
boot path lands on Home before any app can take over.

## Troubleshooting

| symptom | cause | fix |
|---|---|---|
| `sdb connect` times out | developer mode off, wrong Host PC IP, or no reboot since enabling | redo step 2; port `26101` must answer |
| install: `-3 invalid certificate chain` | Tizen/public certificate used, or "Permit to install apps" not run | recreate the profile as **Samsung**; run Permit again |
| install: `-4 invalid signature` | distributor certificate does not include this TV's DUID | connect the TV in Device Manager, delete and recreate the distributor certificate |
| packaging: `profile not found` | TV Extensions not installed | Package Manager → Extension SDK → TV Extensions |
| Package Manager: `Cannot connect the repository server` | the default mirror `usa.sdk-dl.tizen.org` answers 403 | set `Repository:` in `C:	izen-studio\.infoepository.info` to `https://download.tizen.org/sdk/tizenstudio` |
| Package Manager: `Directory creation failed` / `Installation has been failed` | it needs admin rights | run `package-manager-cli.exe` (or the GUI) as administrator |
| 12345 on Apps does nothing | the remote has no number pad, or Apps was not the focused screen | `python tools/tizen.py devmode` sends the digits while the TV sits on Apps |
| app installs but shows a blank screen | page error, not a certificate error | open `build/tizen/index.html` over http in a browser first; the same page must render |
| screen shows `no data` | CORS, wrong IP, or self-signed HTTPS | step 6; check the exact URL in a PC browser on the same LAN |
| dock tiles do nothing | app not installed, or its name differs from `names` in `config.js` | add the name the TV reports; the tile says when it cannot find one |

## What was verified

- `tizen/config.xml` parses; package id is 10 characters; app id, profile, all three privileges,
  `<content>`, `<icon>` and all three bundled wallpapers check out.
- Staging contains 9 files, 1.8 MB; no sample snapshot, no IDE metadata, no `../` paths.
- `--snapshot-url` rewrites the shipped `config.js` and nothing else; an `ftp://` value is refused.
- The staged build renders at 1920×1080 in Chromium with the dock and clock, and honestly reports
  **no data** when the snapshot is absent — the bundled widget cannot show demo values.
- On the 75TU8000 (Tizen 5.5, 2026-10-31): developer mode via `tools/tizen.py devmode` and a
  held-power restart, a Samsung `tvhome` profile, then `package-tizen.py --install --run` signed,
  installed and launched `TVHOME0001.TVHome`. The widget read Home Assistant's `tvhome.json`
  without any `cors_allowed_origins` change: the `<access origin="*">` whitelist was enough.
