/* Everything you are likely to change lives here. dashboard.js reads it once at start. */

window.TVH = {

  /* Where the home screen gets its data. Any URL works; keep data/dashboard.json
     next to this page, or point this at Home Assistant's /local/dashboard.json. */
  feedUrl: "data/dashboard.json",
  refreshSeconds: 60,

  /* 12-hour clock without AM/PM, like the reference. Set false for 19:55. */
  hour12: true,

  /* Live TV cannot be opened from a web page: it needs a remote key sent over the
     network, so that action posts to this URL (see README.md). Empty = the tile
     explains itself instead of failing silently. */
  controlUrl: "",

  /* Full-bleed background. Packaged as a Tizen app, copy these into tizen/wallpapers/
     and change the paths. Seconds = 0 keeps the first one. */
  wallpapers: [
    "../launcher/assets/wallpapers/09.jpg",
    "../launcher/assets/wallpapers/01.jpg",
    "../launcher/assets/wallpapers/05.jpg"
  ],
  wallpaperSeconds: 0,

  /* The dock, left to right. `divider: true` draws the vertical rule.
     ids are Tizen app ids, not Android package names: run
         python tools/tizen.py apps
     once the TV is answering as its host client, and paste them in.
     keyAction sends a remote key through controlUrl instead. */
  apps: [
    { key: "plex",    label: "Plex",             id: "" },
    { key: "netflix", label: "Netflix",          id: "3201907018784" },
    { key: "prime",   label: "Prime Video",      id: "" },
    { key: "youtube", label: "YouTube",          id: "" },
    { key: "disney",  label: "Disney+",          id: "" },
    { key: "spotify", label: "Spotify",          id: "" },

    { key: "livetv",  label: "Live TV",          keyAction: "KEY_TV", divider: true },
    { key: "tvplus",  label: "Samsung TV Plus",  id: "" },
    { key: "iview",   label: "ABC iview",        id: "" }
  ]
};
