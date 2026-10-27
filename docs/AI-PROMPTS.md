# Prompts for an AI coding agent (Claude Code, Codex, Cursor...)

Copy a prompt into your agent, in a folder where this repo is checked out. They assume the agent can run shell commands.
Rules that apply to every prompt (they are repeated in each one so it survives a fresh chat):
- Never print, store or commit tokens, passwords or API keys. Credentials go in Home Assistant's `secrets.yaml` on the HA host.
- Ask before anything destructive or hard to undo (deleting config entries, restarting HA, disabling packages).
- Everything must be reversible; keep a note of what was changed.

---

## 1. Install the launcher on my Fire TV Stick 4K / Fire TV Cube
```
Read FIRETV.md and docs/DEBLOAT.md in this repo. I have a Fire TV (<Stick 4K Max | Cube>) at IP <TV_IP> with ADB debugging on.
1. Check adb is installed; if not, tell me how to install platform-tools (do not download anything without asking).
2. Build the APK (python launcher/build_win.py) or use launcher/out/tvhome.apk if it exists.
3. Run tools/firetv-install.sh <TV_IP>, tell me to accept the on-TV debugging prompt if it appears.
4. Tell me to press Home on the remote and confirm the launcher opens; if the Home service did not bind, re-run the accessibility
   commands from FIRETV.md.
5. Do NOT run tools/firetv-tune.sh until I confirm. Never disable packages other than the ones listed in docs/DEBLOAT.md.
Never print tokens or passwords.
```

## 2. Customise: name, dock order, wallpapers (no Home Assistant needed)
```
In this repo (see FIRETV.md "Make it yours") create a private profile launcher/profiles/<my-name>/config.xml copied from
launcher/res/values/config.xml with: family_name "<Smith Family>", device_name "<Living room>", and dock_apps in this order:
<list of apps>. Find package names with `adb -s <TV_IP>:5555 shell pm list packages -3`. Only include apps that are installed.
Add my wallpapers: copy the .jpg files from <folder> into launcher/assets/wallpapers (resize anything larger than 3840 wide) and
add credits to launcher/WALLPAPER-CREDITS.md. Build with `python launcher/build_win.py <my-name>` and install with
tools/firetv-install.sh. Do not commit launcher/profiles/<my-name>/.
```

## 3. Link Home Assistant: info panel + keep watching + alerts
```
I run Home Assistant at <HA_URL> and have the tv-home launcher on a Fire TV at <TV_IP>. Read docs/HOME-ASSISTANT.md and
homeassistant/tvhome.yaml. Do this with me step by step and ask before each restart:
1. Add the Android Debug Bridge integration for <TV_IP> (Fire TV, port 5555) using the HA UI or REST config flow; tell me if
   the TV needs the on-screen "Allow USB debugging" prompt. Note the resulting media_player entity id.
2. Install homeassistant/tvhome.yaml as /config/packages/tvhome.yaml and homeassistant/tvhome_keep.py as
   /config/tvhome/tvhome_keep.py. Replace every line marked CHANGE with my real entities. List the sensors you pick for
   weather, solar, battery, house power, indoor temperature and ask me to confirm them.
3. Run a configuration check, ask me to approve a restart, then verify http://<HA_URL>/local/tvhome.json updates every 15 seconds
   and contains sensible values.
4. Set ha_json_url and keep_json_url in my launcher profile, rebuild, reinstall.
5. Test script.tv_alert with a harmless message on the TV.
Plex token: I will add plex_token / plex_url / ha_base_url to secrets.yaml myself. Never ask me to paste secrets into chat and never
print them.
```

## 4. Doorbell / camera / bin-night alerts
```
Using script.tv_alert from homeassistant/tvhome.yaml (see docs/HOME-ASSISTANT.md section 5), create automations in my Home Assistant:
- my doorbell entity <entity> -> kind doorbell, title "Doorbell pushed", text "Someone is at the front door", 10 seconds
- person detection on <camera entity> -> kind motion, "Person at the <place>"
- bins: the evening before collection -> kind info, "Bins out tonight"
Only TVs that are on should show it (the script already checks). Show me the YAML before saving, then reload automations.
```

## 5. Fire TV Cube specifics
```
My Fire TV Cube is at <TV_IP> (wired). Follow prompt 1, then check: the Inputs pill lists my HDMI input(s) (adb shell dumpsys tv_input),
the network pill shows Ethernet, and the remote's mic button still opens Alexa. Do not disable any package with "alexa" or "vizzini"
in its name. Name it "<Living room>" through a profile (prompt 2).
```

## 6. Undo everything
```
Run tools/firetv-undo.sh <TV_IP>, confirm the TV is back on Amazon's home screen, and list anything the script could not restore.
```
