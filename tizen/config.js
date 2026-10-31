/* Everything you are likely to change lives here. dashboard.js reads it once at start. */

window.TVH = {

  /* Where the snapshot comes from. Your existing Home Assistant package already
     writes this file every 15 seconds (docs/HOME-ASSISTANT.md):

       snapshotUrl: "http://192.168.1.10:8123/local/tvhome.json"

     Use the HA machine's IP, not homeassistant.local: the TV does not always
     resolve .local. Empty means "read data/tvhome.sample.json", with a small
     "sample data" chip in the corner so it is never mistaken for live. */
  snapshotUrl: "",
  sampleUrl: "data/tvhome.sample.json",

  /* tvhome.yaml republishes every 15 s; polling faster only re-reads the file. */
  refreshSeconds: 15,

  /* Lines under the battery row. They fill in this order: bins, the next
     event, rain (unless the forecast is dry), who is home, the day's solar,
     sunrise/sunset, wind. Raise this only if your snapshot is quiet. */
  maxLines: 3,

  /* "Good evening, <family>" above the clock. Empty shows the greeting alone.
     tools/package-tizen.py --family sets it in the TV's copy only. */
  family: "",

  /* 12-hour clock, and 12-hour sunrise/sunset times. Set false for 19:55. */
  hour12: true,

  /* A web page cannot switch the TV's input, so the source tiles (Fire TV on
     HDMI, Live TV on the aerial) post their remote key to this URL as
     ?key=KEY_HDMI1, and Home Assistant presses it (README.md, "Sources"):

       controlUrl: "http://192.168.1.10:8123/api/webhook/tvhome_key_x7Rq2"

     Empty = the tile explains itself instead of failing silently. */
  controlUrl: "",

  /* Full-bleed background, self-contained: a widget cannot read outside its
     own folder, so these live in tizen/wallpapers/. Seconds = 0 keeps the
     first one; anything else slowly crossfades through the list. */
  wallpapers: [
    "wallpapers/09.jpg",
    "wallpapers/01.jpg",
    "wallpapers/05.jpg"
  ],
  wallpaperSeconds: 0,

  /* The dock, left to right. `divider: true` draws the vertical rule.
     `names` are what the app is called on the TV: on the Tizen runtime the page
     asks the TV for its installed apps, matches these, and **hides the tile when
     nothing matches** — so the dock is what this set actually has, not a wish
     list. `id` pins one anyway. `keyAction` sends a remote key through controlUrl
     and is never hidden (it is a source, not an app). */
  apps: [
    /* Sources first: the Fire TV on its HDMI port (change KEY_HDMI1 to the
       port it is plugged into), the aerial tuner, and Samsung TV Plus. */
    { key: "firetv",  label: "Fire TV",         keyAction: "KEY_HDMI1" },
    { key: "livetv",  label: "Live TV",         keyAction: "KEY_TV" },
    { key: "tvplus",  label: "Samsung TV Plus", names: ["Samsung TV Plus", "TV Plus"] },

    { key: "plex",    label: "Plex",            names: ["Plex"], divider: true },
    { key: "netflix", label: "Netflix",         names: ["Netflix"] },
    { key: "prime",   label: "Prime Video",     names: ["Prime Video", "Amazon Prime Video"] },
    { key: "youtube", label: "YouTube",         names: ["YouTube"] },
    { key: "disney",  label: "Disney+",         names: ["Disney+", "Disney Plus"] },
    { key: "spotify", label: "Spotify",         names: ["Spotify"] },
    { key: "iview",   label: "ABC iview",       names: ["ABC iview", "ABC iView"] },
    { key: "sbs",     label: "SBS On Demand",   names: ["SBS On Demand", "SBS"] },
    { key: "nine",    label: "9Now",            names: ["9Now", "Nine Now", "9 Now"] }
  ]
};
