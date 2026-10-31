/* Home screen behaviour: clock, feed, dock navigation, launching.
   Written for Chromium 69 (Tizen 5.5): no optional chaining, no ??, no flex gap. */

(function () {
  "use strict";

  var cfg = window.TVH || {};
  var $ = function (id) { return document.getElementById(id); };

  /* ---------- logos ---------- */

  var MARKS = {
    plex: '<span class="mark"><span class="word plex">plex</span></span>',

    netflix: '<span class="mark"><svg class="g-netflix" viewBox="0 0 24 34" aria-hidden="true">' +
      '<path d="M2 0h5.6l8.8 22.6V0H22v34h-5.2L7.9 10.6V34H2z"/></svg></span>',

    prime: '<span class="mark"><svg class="g-prime" viewBox="0 0 40 15" aria-hidden="true">' +
      '<path d="M2 3.9c8.4 7 23.8 7.4 33.8 1.3" fill="none" stroke="currentColor" ' +
      'stroke-width="2.5" stroke-linecap="round"/>' +
      '<path d="M33.6 2.5 37.2 5.0 33.0 8.2" fill="none" stroke="currentColor" ' +
      'stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/></svg></span>',

    youtube: '<span class="mark"><svg class="g-youtube" viewBox="0 0 34 24" aria-hidden="true">' +
      '<rect x="1.3" y="1.3" width="31.4" height="21.4" rx="6" fill="none" ' +
      'stroke="currentColor" stroke-width="2.4"/>' +
      '<path d="M13 7.1 23.8 12 13 16.9z"/></svg></span>',

    disney: '<span class="mark"><span class="word disney">Disney+</span></span>',

    spotify: '<span class="mark"><svg class="g-spotify" viewBox="0 0 32 32" aria-hidden="true">' +
      '<circle cx="16" cy="16" r="13.6" fill="none" stroke="currentColor" stroke-width="2.6"/>' +
      '<path d="M9.2 12.2c4.7-1.4 9.9-1 14 1.2" fill="none" stroke="currentColor" ' +
      'stroke-width="2.9" stroke-linecap="round"/>' +
      '<path d="M10.4 17c3.8-1.1 7.9-.7 11.2 1.1" fill="none" stroke="currentColor" ' +
      'stroke-width="2.6" stroke-linecap="round"/>' +
      '<path d="M11.6 21.5c2.9-.8 6.1-.5 8.5.9" fill="none" stroke="currentColor" ' +
      'stroke-width="2.3" stroke-linecap="round"/></svg></span>',

    firetv: '<span class="mark"><span class="word firetv">fire tv</span></span>',

    // An aerial on a set: the tile switches to the TV's own tuner.
    livetv: '<span class="mark"><svg class="g-aerial" viewBox="0 0 34 30" aria-hidden="true">' +
      '<path d="M10 2.5l7 8 7-8" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>' +
      '<rect x="2.5" y="11" width="29" height="16.5" rx="3.5" fill="none" stroke="currentColor" stroke-width="2.2"/>' +
      '<path d="M12 30h10" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"/></svg></span>',

    tvplus: '<span class="mark"><span class="badge tvplus">TV+</span></span>',

    iview: '<span class="mark"><span class="word iview">abc iview</span></span>',

    sbs: '<span class="mark"><span class="word sbs">SBS</span></span>',

    nine: '<span class="mark"><span class="word nine">9now</span></span>'
  };

  /* ---------- info icons ---------- */

  var ICONS = {
    cloud: '<svg viewBox="0 0 24 19" aria-hidden="true"><path d="M7 17h10a4 4 0 0 0 .4-8 5.7 5.7 0 0 0-10.9 1.3A3.5 3.5 0 0 0 7 17z" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"/></svg>',
    rain: '<svg viewBox="0 0 24 19" aria-hidden="true"><path d="M7 13h10a3.6 3.6 0 0 0 .4-7.2A5.6 5.6 0 0 0 6.6 7 3.2 3.2 0 0 0 7 13z" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"/><path d="M8 15.6 7 18M12 15.6 11 18M16 15.6 15 18" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/></svg>',
    sun: '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="4.4" fill="none" stroke="currentColor" stroke-width="1.7"/><path d="M12 2.6v2.4M12 19v2.4M2.6 12h2.4M19 12h2.4M5.4 5.4l1.7 1.7M16.9 16.9l1.7 1.7M18.6 5.4l-1.7 1.7M7.1 16.9l-1.7 1.7" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/></svg>',
    snow: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v18M4.2 7.5l15.6 9M19.8 7.5l-15.6 9" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>',
    spa: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 15c1.8-1.7 3.6-1.7 5.4 0s3.6 1.7 5.4 0 3.6-1.7 5.4 0" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/><path d="M3 19c1.8-1.7 3.6-1.7 5.4 0s3.6 1.7 5.4 0 3.6-1.7 5.4 0" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/><path d="M8 8.6c0-1.6 1.8-1.7 1.8-3.4M12 8.6c0-1.6 1.8-1.7 1.8-3.4M16 8.6c0-1.6 1.8-1.7 1.8-3.4" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>',
    bin: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5.4 7.4h13.2l-1.3 12a2 2 0 0 1-2 1.8H8.7a2 2 0 0 1-2-1.8z" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"/><path d="M3.6 7.4h16.8M9.6 7.4V4.6h4.8v2.8M10 11v6M14 11v6" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>',
    bolt: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M13.4 2 4.8 13.4h5.8l-1 8.6 8.6-11.4h-5.8z" fill="currentColor"/></svg>',
    person: '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="8" r="3.6" fill="none" stroke="currentColor" stroke-width="1.7"/><path d="M5 20c.6-3.6 3.4-5.6 7-5.6s6.4 2 7 5.6" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/></svg>',
    music: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9.5 17.5V6.2l9-1.7v11" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"/><circle cx="6.6" cy="17.6" r="2.9" fill="none" stroke="currentColor" stroke-width="1.7"/><circle cx="15.6" cy="15.6" r="2.9" fill="none" stroke="currentColor" stroke-width="1.7"/></svg>',
    dot: '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="3" fill="currentColor"/></svg>',
    cal: '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3.2" y="5" width="17.6" height="15.6" rx="3" fill="none" stroke="currentColor" stroke-width="1.7"/><path d="M3.2 9.8h17.6M8.4 3v3.6M15.6 3v3.6" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/></svg>',
    wind: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 8.4h10.2a2.6 2.6 0 1 0-2.5-3.3M3 12.4h14.6a2.7 2.7 0 1 1-2.6 3.4M3 16.4h6.4" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/></svg>'
  };

  /* ---------- state ---------- */

  /* True while the page is reading the sample file because config.js has no
     snapshotUrl yet; it drives the small "sample data" chip. */
  var usingSample = true;

  /* False until the TV answers with its installed apps. While false the dock
     cannot know what exists (a desktop browser, or the API failed), so it
     shows everything in config.js rather than hiding the lot. */
  var appsKnown = false;

  var hour12 = cfg.hour12 !== false;
  var sheet = null;
  var lastFocus = null;
  var toastTimer = null;
  var wallTimer = null;
  var front = $("wallA");
  var back = $("wallB");
  var wallIndex = 0;
  var tileIndex = 0;
  /* The last snapshot that read cleanly: a missed poll keeps it on screen, dimmed,
     instead of blanking the panel until the next good read. */
  var lastSnap = null;
  var loading = false;

  /* The Fire TV's two rotating cards, read from the same tvhome_new.json:
     what is on live now (TiviMate on the Fire TV) and the newest on Plex. */
  var decks = [];
  var DECK_EVERY = 10000;

  /* ---------- helpers ---------- */

  function pad(n) { return n < 10 ? "0" + n : "" + n; }

  function stored(key) {
    try { return localStorage.getItem(key); } catch (e) { return null; }
  }

  function store(key, value) {
    try { localStorage.setItem(key, value); } catch (e) { /* private mode */ }
  }

  function toast(message) {
    var t = $("toast");
    t.textContent = message;
    t.className = "toast on";
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.className = "toast"; }, 3400);
  }

  /* ---------- clock ---------- */

  function tick() {
    var d = new Date();
    var h = d.getHours();
    if (hour12) {
      h = h % 12;
      if (!h) { h = 12; }
    }
    var text = (hour12 ? h : pad(h)) + ":" + pad(d.getMinutes());
    var el = $("clock");
    if (el.textContent === text) { return; }
    // The minute turns over like the Lock Screen's: the old time lifts away, the new one rises.
    if (!el.textContent || document.hidden) { el.textContent = text; return; }
    el.className = "clock out";
    setTimeout(function () {
      el.textContent = text;
      el.className = "clock in";
    }, 160);
  }

  /* ---------- background ---------- */

  function applyWall(index) {
    var list = cfg.wallpapers || [];
    if (!list.length) { return; }
    var url = list[index % list.length];
    back.style.backgroundImage = "url('" + url + "')";
    back.className = "wall on";
    tintGlass(url);
    front.className = "wall";
    var swap = front;
    front = back;
    back = swap;
  }

  /* The glass takes a little of the photo's colour, as tvOS glass does: the
     wallpaper's average, at a fifth of its brightness. Two strengths, because the
     2020 set has no backdrop blur and needs a denser tint to keep text legible. */
  function tintGlass(url) {
    var img = new Image();
    img.onload = function () {
      try {
        var c = document.createElement("canvas");
        c.width = 16;
        c.height = 9;
        var ctx = c.getContext("2d");
        ctx.drawImage(img, 0, 0, 16, 9);
        var px = ctx.getImageData(0, 0, 16, 9).data;
        var r = 0, g = 0, b = 0;
        for (var i = 0; i < px.length; i += 4) { r += px[i]; g += px[i + 1]; b += px[i + 2]; }
        var n = px.length / 4;
        var rgb = Math.round(r / n * 0.2) + "," + Math.round(g / n * 0.2) + "," + Math.round(b / n * 0.2);
        var root = document.documentElement.style;
        root.setProperty("--glass", "rgba(" + rgb + ",.42)");
        root.setProperty("--glass-solid", "rgba(" + rgb + ",.66)");
      } catch (e) { /* a tainted canvas keeps the neutral glass */ }
    };
    img.src = url;
  }

  function setRotation(on) {
    if (wallTimer) { clearInterval(wallTimer); wallTimer = null; }
    if (on && (cfg.wallpapers || []).length > 1) {
      var seconds = cfg.wallpaperSeconds || 90;
      wallTimer = setInterval(function () {
        wallIndex += 1;
        applyWall(wallIndex);
      }, seconds * 1000);
    }
  }

  /* ---------- info panel ---------- */

  function row(icon) {
    var r = document.createElement("div");
    r.className = "row";
    if (icon) {
      var ic = document.createElement("span");
      ic.className = "ic";
      ic.innerHTML = ICONS[icon] || ICONS.dot;
      r.appendChild(ic);
    }
    return r;
  }

  function pill(kind, label, level) {
    var el = document.createElement("span");
    el.className = "pill " + kind;
    var icon;
    if (kind === "battery") {
      var pct = Math.max(0, Math.min(100, level));
      if (pct <= 20) { el.className += " low"; } else if (pct <= 45) { el.className += " warn"; }
      var fill = (16.6 * pct / 100).toFixed(1);
      icon = '<svg viewBox="0 0 26 15" aria-hidden="true">' +
        '<rect x="1" y="2.4" width="21" height="10.2" rx="2.8" fill="none" stroke="currentColor" stroke-width="1.5"/>' +
        '<rect x="2.9" y="4.4" width="' + fill + '" height="6.2" rx="1.5" fill="currentColor"/>' +
        '<rect x="23.4" y="5.6" width="2.2" height="3.8" rx="1.1" fill="currentColor"/></svg>';
    } else if (kind === "solar") {
      icon = '<svg class="g" viewBox="0 0 24 24" aria-hidden="true">' +
        '<path d="M13.4 2 4.8 13.2h5.9l-1 8.8 8.6-11.4h-5.9z" fill="currentColor"/></svg>';
    } else {
      icon = '<svg class="g" viewBox="0 0 24 24" aria-hidden="true">' +
        '<path d="M4 10.4 12 3.4l8 7M6.4 9.4v10.2h11.2V9.4" fill="none" stroke="currentColor" ' +
        'stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>';
    }
    el.innerHTML = icon + "<span>" + label + "</span>";
    return el;
  }

  function pillsRow(snap) {
    var battery = num(snap.battery);
    var solar = num(snap.solar);
    var house = num(snap.house);
    var made = [];

    if (battery !== null) {
      var b = pill("battery", "Battery " + Math.round(battery) + "%", battery);
      if (isOld(snap, "battery")) { b.className += " stale"; }
      made.push(b);
    }
    if (solar !== null) {
      var s = pill("solar", "Solar " + one(solar) + " kW");
      if (isOld(snap, "solar")) { s.className += " stale"; }
      made.push(s);
    }
    if (house !== null) {
      var h = pill("using", "Using " + one(house) + " kW");
      if (isOld(snap, "house")) { h.className += " stale"; }
      made.push(h);
    }

    if (!made.length) { return null; }
    var r = row(null);
    made.forEach(function (p) { r.appendChild(p); });
    return r;
  }

  function weatherIcon(condition) {
    var c = String(condition || "").toLowerCase();
    if (/rain|shower|storm|drizzle/.test(c)) { return "rain"; }
    if (/snow|sleet|hail/.test(c)) { return "snow"; }
    if (/sun|clear/.test(c)) { return "sun"; }
    return "cloud";
  }

  /* ---------- snapshot -> screen ---------- */

  function num(value) {
    if (value === null || value === undefined || value === "") { return null; }
    var n = Number(value);
    return isFinite(n) ? n : null;
  }

  function words(value) {
    return value === null || value === undefined ? "" : String(value).trim();
  }

  function one(n) { return (Math.round(n * 10) / 10).toFixed(1); }

  function titleCase(s) {
    s = s.replace(/_/g, " ").toLowerCase();
    return s ? s.charAt(0).toUpperCase() + s.slice(1) : "";
  }

  /* tvhome.yaml sends seconds since each entity last updated; anything older
     than 15 minutes is dimmed, the same rule the Fire TV panel uses. */
  function isOld(snap, key) {
    var ages = snap.ages || {};
    var age = num(ages[key]);
    return age !== null && age > 900;
  }

  function span(value, dim) {
    var s = document.createElement("span");
    s.textContent = value;
    if (dim) { s.className = "dim"; }
    return s;
  }

  function weatherRow(snap) {
    var temp = num(snap.temp);
    var inside = num(snap.inside);
    var condition = titleCase(words(snap.condition));
    if (temp === null && inside === null && !condition) { return null; }

    var r = row(weatherIcon(condition));
    if (temp !== null) {
      r.appendChild(span(temp + "°" + (condition ? " " + condition : ""), isOld(snap, "temp")));
    } else if (condition) {
      r.appendChild(span(condition, false));
    }
    if (inside !== null) {
      var sep = document.createElement("span");
      sep.className = "sep";
      sep.textContent = "·";
      r.appendChild(sep);
      r.appendChild(span(inside + "° inside", isOld(snap, "inside")));
    }
    return r;
  }

  function clockTime(epoch) {
    var at = num(epoch);
    if (at === null || at <= 0) { return ""; }
    var d = new Date(at * 1000);
    var h = d.getHours();
    var m = d.getMinutes();
    if (!hour12) { return pad(h) + ":" + pad(m); }
    var hh = h % 12;
    if (!hh) { hh = 12; }
    return hh + ":" + pad(m) + (h < 12 ? " AM" : " PM");
  }

  function inText(epoch) {
    var at = num(epoch);
    if (at === null || at <= 0) { return ""; }
    var mins = Math.round((at * 1000 - Date.now()) / 60000);
    if (mins <= 0) { return ""; }
    if (mins < 60) { return "in " + mins + " min"; }
    var hours = Math.round(mins / 60);
    if (hours < 24) { return "in " + hours + " h"; }
    return "in " + Math.round(hours / 24) + " d";
  }

  function peopleText(snap) {
    var n = num(snap.people);
    if (n === null) { return ""; }
    var names = words(snap.people_names).split("&")
      .map(function (s) { return s.trim(); })
      .filter(Boolean);
    if (n === 1 && names.length === 1) { return names[0] + " is home"; }
    if (n === 2 && names.length === 2) { return names[0] + " & " + names[1] + " are home"; }
    if (n === 0) { return "Nobody home"; }
    return n + " people home";
  }

  function energyText(snap) {
    var kwh = num(snap.energy_today);
    var left = words(snap.battery_left);
    if (kwh !== null && left) { return one(kwh) + " kWh today · " + left + " left"; }
    if (kwh !== null) { return one(kwh) + " kWh today"; }
    if (left) { return "Battery " + left + " left"; }
    return "";
  }

  function sunText(snap) {
    var rise = clockTime(snap.sunrise_at);
    var set = clockTime(snap.sunset_at);
    if (rise && set) { return "Sunrise " + rise + " · Sunset " + set; }
    if (rise) { return "Sunrise " + rise; }
    if (set) { return "Sunset " + set; }
    return "";
  }

  function windText(snap) {
    var wind = num(snap.wind);
    var unit = words(snap.wind_unit) || "km/h";
    var humidity = num(snap.humidity);
    var bits = [];
    if (wind !== null) { bits.push(Math.round(wind) + " " + unit); }
    if (humidity !== null) { bits.push(Math.round(humidity) + "% humidity"); }
    return bits.join(" · ");
  }

  /* The reference screen has room for a few lines under the battery row, so
     the things you can act on come first: bins, then the next event, then
     weather, then who is home, then power. Nothing is shown twice. */
  function snapshotLines(snap) {
    var out = [];

    if (words(snap.bins)) {
      out.push({ icon: "bin", text: words(snap.bins), accent: true });
    }

    if (words(snap.next)) {
      var when = inText(snap.next_at);
      out.push({ icon: "cal", text: words(snap.next) + (when ? " · " + when : "") });
    }

    var rain = words(snap.rain);
    if (rain && !/^(no rain|dry|none)/i.test(rain)) {
      out.push({ icon: "rain", text: rain });
    }

    var people = peopleText(snap);
    if (people) { out.push({ icon: "person", text: people }); }

    var energy = energyText(snap);
    if (energy) { out.push({ icon: "bolt", text: energy }); }

    var sun = sunText(snap);
    if (sun) { out.push({ icon: "sun", text: sun }); }

    var wind = windText(snap);
    if (wind) { out.push({ icon: "wind", text: wind }); }

    return out.slice(0, cfg.maxLines || 3);
  }

  /* The card is the same idea as the Fire TV panel's Plex line: audio first
     (media_* in tvhome.yaml), otherwise what Plex is playing elsewhere. */
  function renderNow(snap) {
    var box = $("now");
    var title = words(snap.media_title);
    var sub = words(snap.media_artist);
    var art = words(snap.media_art);
    var warm = true;

    if (!title) {
      var plex = words(snap.plex);
      if (!plex) { box.hidden = true; return; }
      var bits = plex.split(/\s+[-\u2013]\s+/);
      title = bits[0];
      var code = "";
      var m = plex.match(/s(\d{1,2})e(\d{1,2})/i);
      if (m) {
        code = "S" + m[1] + "E" + m[2];
      } else if (bits.length > 1 && bits[1].length <= 4) {
        code = bits[1].toUpperCase();
      }
      sub = (code ? code + " · " : "") + "Plex";
      warm = false;
    }

    box.hidden = false;
    $("nowTitle").textContent = title;
    $("nowSub").textContent = sub;

    var el = $("nowArt");
    el.style.backgroundImage = "none";
    if (art) {
      el.style.background = "none";
      el.style.backgroundImage = "url('" + art + "')";
    } else if (warm) {
      el.style.background = "linear-gradient(140deg,#e8a06a,#8f6fd0)";
    } else {
      el.style.background = "linear-gradient(140deg,#7f8fb4,#2b3550)";
    }
  }

  function render(snap) {
    snap = snap || {};
    var info = $("info");
    info.innerHTML = "";

    var weather = weatherRow(snap);
    if (weather) { info.appendChild(weather); }
    var pills = pillsRow(snap);
    if (pills) { info.appendChild(pills); }

    snapshotLines(snap).forEach(function (line) {
      var r = row(line.icon);
      var s = document.createElement("span");
      s.textContent = line.text;
      if (line.accent) { s.style.color = "#f0c45a"; }
      if (line.dim) { s.style.color = "rgba(255,255,255,.55)"; }
      r.appendChild(s);
      info.appendChild(r);
    });

    renderNow(snap);
  }

  function note(msg) {
    var el = $("dataNote");
    if (el) { el.textContent = msg; }
  }

  function load() {
    usingSample = !cfg.snapshotUrl;
    var url = cfg.snapshotUrl || cfg.sampleUrl;
    var chip = $("sampleChip");

    if (!window.fetch) {
      render({});
      note("This browser cannot read the snapshot (no fetch).");
      return;
    }

    // One read at a time, and none that hangs: the TV's network can stall a request
    // for minutes, and a 15-second poll would otherwise stack them up.
    if (loading) { return; }
    loading = true;
    var busted = url + (url.indexOf("?") < 0 ? "?" : "&") + "_=" + Date.now();
    var timeout = new Promise(function (resolve, reject) {
      setTimeout(function () { reject(new Error("timed out")); }, 10000);
    });
    Promise.race([fetch(busted, { cache: "no-store" }), timeout])
      .then(function (r) {
        if (!r || !r.ok) { throw new Error("HTTP " + (r ? r.status : "error")); }
        return r.json();
      })
      .then(function (snap) {
        loading = false;
        lastSnap = snap;
        $("info").className = "info";
        render(snap);
        if (chip) {
          chip.hidden = !usingSample;
          chip.textContent = "sample data";
        }
        var d = new Date();
        note((usingSample ? "Sample file" : "Home Assistant") +
          " · read " + pad(d.getHours()) + ":" + pad(d.getMinutes()) + ":" + pad(d.getSeconds()));
      })
      .catch(function (err) {
        // Nothing invented on screen. A missed read keeps the last good snapshot, dimmed,
        // so one dropped poll does not blank the panel; with none yet, only the clock and dock.
        loading = false;
        if (lastSnap) {
          $("info").className = "info stale";
        } else {
          render({});
        }
        if (chip) {
          chip.hidden = false;
          chip.textContent = lastSnap ? "offline · last read kept" : "no data";
        }
        note("Could not read " + url + " — " + (err && err.message ? err.message : "error") +
          ". Set snapshotUrl in config.js and allow this origin in HA (http.cors_allowed_origins).");
      });
  }

  /* ---------- live now / new on Plex ---------- */

  /* The feed was written through a Latin-1 step somewhere, so its middle dots
     arrive as "Â·". Put them back rather than show the mojibake. */
  function clean(text) {
    return String(text || "").replace(/\u00c2\u00b7/g, "\u00b7").replace(/\s+/g, " ").trim();
  }

  function clock12(sec) {
    var d = new Date(sec * 1000);
    var h = d.getHours();
    var m = pad(d.getMinutes());
    if (!hour12) { return pad(h) + ":" + m; }
    return (h % 12 || 12) + ":" + m + (h < 12 ? "am" : "pm");
  }

  function cardsUrl() {
    if (cfg.cardsUrl) { return cfg.cardsUrl; }
    return cfg.snapshotUrl ? cfg.snapshotUrl.replace(/tvhome\.json(\?.*)?$/, "tvhome_new.json") : "";
  }

  function paneFor(item, live) {
    var p = document.createElement("div");
    p.className = "pane" + (live ? "" : " plex");
    var art = document.createElement("div");
    art.className = "art";
    if (item.image) { art.style.backgroundImage = "url('" + item.image + "')"; }
    var words = document.createElement("div");
    words.className = "words";
    var eyebrow = document.createElement("div");
    eyebrow.className = "eyebrow";
    eyebrow.innerHTML = '<span class="dot"></span>';
    eyebrow.appendChild(document.createTextNode(live ? "Live now on TV" : "Now Showing on Plex"));
    var t = document.createElement("div");
    t.className = "t";
    t.textContent = clean(item.title);
    var s = document.createElement("div");
    s.className = "s";
    s.textContent = live
      ? [clean(item.channel), item.until ? "ends " + clock12(item.until) : ""].filter(Boolean).join(" \u00b7 ")
      : clean(item.sub);
    words.appendChild(eyebrow);
    words.appendChild(t);
    words.appendChild(s);
    p.appendChild(art);
    p.appendChild(words);
    return p;
  }

  /* One card: its list, the pane on screen, and its own slot on a shared 10 s
     grid (live on the beat, Plex half a beat later) so the two never turn together. */
  function makeDeck(live, items, open) {
    var el = document.createElement("button");
    el.type = "button";
    el.className = "deck";
    var deck = { el: el, live: live, items: items, cur: 0 };
    items.forEach(function (item, i) {
      var p = paneFor(item, live);
      if (i === 0) { p.className += " on"; }
      el.appendChild(p);
    });
    el.addEventListener("click", function () { open(items[deck.cur]); });
    deck.turn = function () {
      if (items.length < 2 || document.activeElement === el) { return; }
      var panes = el.children;
      panes[deck.cur].className = panes[deck.cur].className.replace(" on", "");
      deck.cur = (deck.cur + 1) % items.length;
      panes[deck.cur].className += " on";
    };
    return deck;
  }

  function sourceApp(key) {
    return (cfg.apps || []).filter(function (a) { return a.key === key; })[0];
  }

  function renderDecks(items) {
    var box = $("decks");
    var now = Date.now() / 1000;
    var live = items.filter(function (i) { return i.kind === "live" && (!i.until || i.until > now); }).slice(0, 10);
    var plex = items.filter(function (i) { return i.kind !== "live" && i.app === "Plex"; }).slice(0, 10);
    var sig = JSON.stringify([live, plex]);
    if (box.getAttribute("data-sig") === sig) { return; }
    box.setAttribute("data-sig", sig);
    var had = document.activeElement && document.activeElement.className === "deck";
    box.innerHTML = "";
    decks = [];
    // Live is TiviMate on the Fire TV, so its card switches to the Fire TV's input;
    // the Plex card opens Plex on this TV.
    if (live.length) {
      decks.push(makeDeck(true, live, function () {
        var ft = sourceApp("firetv");
        if (ft) { launch(ft); }
      }));
    }
    if (plex.length) {
      decks.push(makeDeck(false, plex, function () {
        var px = sourceApp("plex");
        if (px) { launch(px); } else { toast("Plex is not on this TV"); }
      }));
    }
    decks.forEach(function (d) { box.appendChild(d.el); });
    box.hidden = !decks.length;
    if (had && decks.length) { decks[0].el.focus(); }
  }

  function loadCards() {
    var url = cardsUrl();
    if (!url || !window.fetch) { return; }
    fetch(url + (url.indexOf("?") < 0 ? "?" : "&") + "_=" + Date.now(), { cache: "no-store" })
      .then(function (r) { if (!r.ok) { throw new Error("HTTP " + r.status); } return r.json(); })
      .then(function (feed) { renderDecks((feed && feed.items) || []); })
      .catch(function () { /* keep whatever cards are on screen */ });
  }

  function deckEls() {
    return decks.map(function (d) { return d.el; });
  }

  /* ---------- dock ---------- */

  function markFor(app) {
    return MARKS[app.key] || '<span class="mark"><span class="word">' + app.label + "</span></span>";
  }

  /* What this TV actually has: a tile with no match is left out, but a
     keyAction tile (Live TV) is a source, not an installed app, and stays. */
  function visibleApps() {
    return (cfg.apps || []).filter(function (app) {
      return app.keyAction || !appsKnown || app.found;
    });
  }

  function buildDock() {
    var dock = $("dock");
    dock.innerHTML = "";
    visibleApps().forEach(function (app) {
      if (app.divider) {
        var d = document.createElement("span");
        d.className = "divider";
        dock.appendChild(d);
      }
      var b = document.createElement("button");
      b.type = "button";
      b.className = "tile";
      b.setAttribute("aria-label", app.label);
      // The name shows under the focused tile only, as on tvOS (style.css .name).
      b.innerHTML = markFor(app) + '<span class="name">' + app.label + "</span>";
      b.addEventListener("click", function () { launch(app); });
      b.addEventListener("focus", function () { if (b.className.indexOf("focused") < 0) { spot(b); } });
      // Left to right, half a beat apart, after the glass itself has risen.
      b.className = "tile enter";
      b.style.webkitAnimationDelay = b.style.animationDelay = (160 + dock.children.length * 20) + "ms";
      dock.appendChild(b);
    });
  }

  function buildGrid() {
    var grid = $("appsGrid");
    grid.innerHTML = "";
    visibleApps().forEach(function (app) {
      var c = document.createElement("button");
      c.type = "button";
      c.className = "card";
      c.innerHTML = markFor(app) + '<span class="label">' + app.label + "</span>";
      c.addEventListener("click", function () {
        closeSheet();
        launch(app);
      });
      grid.appendChild(c);
    });
  }

  function tiles() {
    return Array.prototype.slice.call(document.querySelectorAll("#dock .tile"));
  }

  /* The TV sets the spotlight from the remote, so do not rely on :focus alone:
     an inactive browser window can leave :focus unmatched. */
  /* dir: "l" or "r" when the cursor arrived sideways, so the tile leans the way it
     travelled and levels out (style.css .lean-l / .lean-r). */
  function spot(element, dir) {
    Array.prototype.forEach.call(document.querySelectorAll(".tile.focused"), function (t) {
      t.className = "tile";
    });
    if (element) { element.className = "tile focused" + (dir ? " lean-" + dir : ""); }
  }

  function focusTile(index, dir) {
    var all = tiles();
    if (!all.length) { return; }
    tileIndex = (index + all.length) % all.length;
    spot(all[tileIndex], dir);
    all[tileIndex].focus();
  }

  /* ---------- actions ---------- */

  function normal(s) {
    return String(s || "").toLowerCase().replace(/[^a-z0-9]/g, "");
  }

  /* Tizen knows which apps are installed on this set, so the dock matches them by
     name instead of relying on ids copied from another region's TV (a Tizen id is
     region- and firmware-specific). A match is cached; a failed launch drops it. */
  function resolveApps(done) {
    var apps = cfg.apps || [];
    if (!(window.tizen && window.tizen.application && window.tizen.application.getAppsInfo)) {
      done();
      return;
    }
    try {
      window.tizen.application.getAppsInfo(function (list) {
        // An empty list is far more likely to be a firmware quirk than a TV
        // with no apps at all; in that case keep showing the configured dock.
        if (list && list.length) {
          match(apps, list);
          appsKnown = true;
        }
        done();
      }, function () { done(); });
    } catch (e) {
      done();
    }
  }

  /* Names first, cached ids second, and a cached id only survives if the TV
     still reports it: an uninstalled app should lose its tile, not keep it
     until someone taps a dead button. */
  function match(apps, list) {
    var ids = {};
    var byName = {};

    list.forEach(function (item) {
      if (!item || !item.id) { return; }
      ids[item.id] = true;
      var names = [item.name].concat(item.names || []);
      names.forEach(function (name) {
        var key = normal(name);
        if (key && !byName[key]) { byName[key] = item; }
      });
    });

    apps.forEach(function (app) {
      if (app.keyAction) { app.found = true; return; }

      var saved = stored("tvh.id." + app.key);
      if (saved) {
        if (ids[saved]) { app.id = saved; app.found = true; return; }
        try { localStorage.removeItem("tvh.id." + app.key); } catch (e) { /* private mode */ }
      }

      if (app.id) {
        app.found = !!ids[app.id];
        return;
      }

      var want = (app.names || [app.label]).map(normal);
      var hit = null;
      for (var i = 0; i < want.length && !hit; i++) {
        if (byName[want[i]]) { hit = byName[want[i]]; }
      }

      if (hit) {
        app.id = hit.id;
        app.found = true;
        store("tvh.id." + app.key, hit.id);
      } else {
        app.found = false;
      }
    });
  }

  function launch(app) {
    if (app.keyAction) { sendKey(app); return; }
    if (!app.id) {
      toast(app.label + " is not on this TV, or Tizen did not report it");
      return;
    }
    openApp(app);
  }

  function openApp(app) {
    if (window.tizen && window.tizen.application && window.tizen.application.launch) {
      try {
        window.tizen.application.launch(app.id, function () {}, function (err) {
          try { localStorage.removeItem("tvh.id." + app.key); } catch (e) { /* private mode */ }
          app.id = "";
          toast("Could not open " + app.label + ": " + (err && err.message ? err.message : "failed"));
        });
      } catch (e) {
        toast("Could not open " + app.label);
      }
      return;
    }
    toast(app.label + " → " + app.id + " (needs the Tizen runtime)");
  }

  function sendKey(app) {
    var key = app.keyAction;
    if (!cfg.controlUrl) {
      toast(app.label + " sends the " + key + " key, which needs controlUrl in config.js");
      return;
    }
    var url = cfg.controlUrl + (cfg.controlUrl.indexOf("?") < 0 ? "?" : "&") + "key=" + encodeURIComponent(key);
    fetch(url, { method: "POST" })
      .then(function (r) { toast(r && r.ok ? "Switching to " + app.label : "Control URL returned an error"); })
      .catch(function () { toast("Could not reach the control URL"); });
  }

  /* ---------- sheets ---------- */

  function openSheet(el) {
    lastFocus = document.activeElement;
    [$("appsSheet"), $("settingsSheet")].forEach(function (s) { s.className = "sheet"; });
    el.className = "sheet open";
    sheet = el;
    var first = el.querySelector("button");
    if (first) { first.focus(); }
  }

  function closeSheet() {
    if (!sheet) { return; }
    sheet.className = "sheet";
    sheet = null;
    if (lastFocus && lastFocus.focus) { lastFocus.focus(); } else { focusTile(tileIndex); }
  }

  function setSwitch(el, on) {
    el.setAttribute("aria-checked", on ? "true" : "false");
  }

  /* ---------- layout ---------- */

  function fit() {
    var scale = Math.min(window.innerWidth / 1920, window.innerHeight / 1080);
    var x = (window.innerWidth - 1920 * scale) / 2;
    var y = (window.innerHeight - 1080 * scale) / 2;
    var stage = $("stage");
    stage.style.transform = "translate(" + x + "px," + y + "px) scale(" + scale + ")";
  }

  /* ---------- start ---------- */

  function init() {
    var savedHour = stored("tvh.hour12");
    if (savedHour === "0") { hour12 = false; }
    if (savedHour === "1") { hour12 = true; }

    var rotate = stored("tvh.rotate") === "1";

    fit();
    window.addEventListener("resize", fit);
    window.addEventListener("orientationchange", fit);

    applyWall(0);
    setRotation(rotate);
    setSwitch($("toggleWall"), rotate);

    tick();
    // The panel (and some TV firmware) can change the viewport without firing resize,
    // so re-fit alongside the clock rather than trusting the event alone.
    setInterval(function () { tick(); fit(); }, 1000);

    // Ask the TV which apps it has before drawing the dock. In a browser this
    // falls straight through and the tiles simply cannot launch.
    resolveApps(function () {
      buildDock();
      buildGrid();
      focusTile(0);
    });
    load();
    setInterval(load, Math.max(15, cfg.refreshSeconds || 15) * 1000);

    loadCards();
    setInterval(loadCards, 60000);
    setInterval(function () { if (decks[0]) { decks[0].turn(); } }, DECK_EVERY);
    setTimeout(function () {
      setInterval(function () { if (decks[1]) { decks[1].turn(); } }, DECK_EVERY);
    }, DECK_EVERY / 2);

    setSwitch($("toggle24"), !hour12);

    $("toggle24").addEventListener("click", function () {
      hour12 = !hour12;
      setSwitch($("toggle24"), !hour12);
      store("tvh.hour12", hour12 ? "1" : "0");
      tick();
    });

    $("toggleWall").addEventListener("click", function () {
      rotate = !rotate;
      setSwitch($("toggleWall"), rotate);
      store("tvh.rotate", rotate ? "1" : "0");
      setRotation(rotate);
    });

    $("refreshBtn").addEventListener("click", function () {
      note("Refreshing…");
      load();
      toast("Refreshed");
    });

    $("allAppsBtn").addEventListener("click", function () { openSheet($("appsSheet")); });
    $("settingsBtn").addEventListener("click", function () { openSheet($("settingsSheet")); });
    $("allAppsBtn").addEventListener("focus", function () { spot(null); });
    $("settingsBtn").addEventListener("focus", function () { spot(null); });

    $("appsSheet").addEventListener("click", function (e) { if (e.target === this) { closeSheet(); } });
    $("settingsSheet").addEventListener("click", function (e) { if (e.target === this) { closeSheet(); } });

    document.addEventListener("keydown", function (e) {
      var k = e.keyCode;

      if (sheet) {
        if (k === 27 || k === 8 || k === 461) {
          e.preventDefault();
          closeSheet();
        }
        return;
      }

      var top = [$("allAppsBtn"), $("settingsBtn")];
      var t = top.indexOf(document.activeElement);
      var di = deckEls().indexOf(document.activeElement);
      if (di >= 0 && (k === 37 || k === 39)) {
        e.preventDefault();
        var dn = di + (k === 39 ? 1 : -1);
        if (dn >= 0 && dn < decks.length) { decks[dn].el.focus(); }
      } else if (t >= 0 && (k === 37 || k === 39)) {
        // Left and right move between the round buttons; they used to drop into the dock.
        e.preventDefault();
        top[Math.max(0, Math.min(top.length - 1, t + (k === 39 ? 1 : -1)))].focus();
      } else if (k === 37 || k === 39) {
        e.preventDefault();
        var i = tiles().indexOf(document.activeElement);
        if (k === 37) { focusTile(i <= 0 ? tiles().length - 1 : i - 1, "l"); }
        else { focusTile(i < 0 ? 0 : i + 1, "r"); }
      } else if (k === 38) {
        e.preventDefault();
        // Up from the dock lands on the cards, up again on the round buttons.
        var onTile = tiles().indexOf(document.activeElement) >= 0;
        spot(null);
        if (onTile && decks.length) { decks[0].el.focus(); } else { $("allAppsBtn").focus(); }
      } else if (k === 40) {
        e.preventDefault();
        var fromTop = document.activeElement === $("allAppsBtn") || document.activeElement === $("settingsBtn");
        if (fromTop && decks.length) {
          decks[0].el.focus();
        } else {
          focusTile(fromTop || deckEls().indexOf(document.activeElement) >= 0 ? tileIndex : 0);
        }
      }
    });

    document.addEventListener("visibilitychange", function () {
      if (!document.hidden) { tick(); }
    });
  }

  /* Console hook: feed it a snapshot to see how the screen lays it out.
     window.TVH_debug.render({ temp: 24, inside: 21.6 }) */
  window.TVH_debug = { render: render, load: load, loadCards: loadCards };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
