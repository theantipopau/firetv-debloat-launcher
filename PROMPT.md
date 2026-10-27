# The prompt

**First, on the TV:** Settings → System → About → press OK on **Build** 7 times → back → Developer
options → turn on **USB debugging**. Keep the TV on, on the same Wi-Fi as your computer.

Then paste this into Claude Code (works on Android TV / Google TV, not Samsung, LG or Fire TV):

```text
My Android TV is slow and full of ads. Find it on my Wi-Fi, connect over ADB, and debloat it:
disable the manufacturer's ads, telemetry and junk (reversibly, never uninstall), asking me first
about apps and features I might use. Then build and install a fast, minimal Apple TV-style home
screen with a dock of my favourite apps and rotating nature wallpapers. Back up before changing
anything, check every step with screenshots from the TV, and give me a restore script.

Heads-up from people who've done this: use the adb-shell Python package if macOS blocks adb; on
Google TV also disable com.google.android.tungsten.setupwraith or it hijacks the Home button; and
test the Home button from inside an app.
```

If you cloned [this repo](https://github.com/mihirgupta0900/tv-home), run Claude Code inside it. It
can reuse the launcher and scripts instead of writing them from scratch.
