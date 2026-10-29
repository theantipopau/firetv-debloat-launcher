/* Home screen behaviour: clock, feed, dock navigation, launching.
   Written for Chromium 69 (Tizen 5.5): no optional chaining, no ??, no flex gap. */

(function () {
  "use strict";

  var cfg = window.TVH || {};
  var $ = function (id) { return document.getElementById(id); };

  /* ---------- logos ---------- */

  var MARKS = {
    plex: '<span class="mark"><span class="word plex">plex</span></span>',

    netflix: '<span class="mark"><svg viewBox="0 0 24 34" aria-hidden="true">' +
      '<path d="M2 0h5.6l8.8 22.6V0H22v34h-5.2L7.9 10.6V34H2z"/></svg></span>',

    prime: '<span class="mark"><svg viewBox="0 0 40 15" aria-hidden="true">' +
      '<path d="M2 4.6c7.2 6.2 22.2 6.9 32.4 1.3" fill="none" stroke="currentColor" ' +
      'stroke-width="2.8" stroke-linecap="round"/>' +
      '<path d="M32.8 2.6 37.4 5.9 32.2 9.2" fill="none" stroke="currentColor" ' +
      'stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/></svg></span>',

    youtube: '<span class="mark"><svg viewBox="0 0 34 24" aria-hidden="true">' +
      '<rect x="1.3" y="1.3" width="31.4" height="21.4" rx="6" fill="none" ' +
      'stroke="currentColor" stroke-width="2.4"/>' +
      '<path d="M13 7.1 23.8 12 13 16.9z"/></svg></span>',

    disney: '<span class="mark"><span class="word disney">Disney+</span></span>',

    spotify: '<span class="mark"><svg viewBox="0 0 32 32" aria-hidden="true">' +
      '<circle cx="16" cy="16" r="13.6" fill="none" stroke="currentColor" stroke-width="2.6"/>' +
      '<path d="M9.2 12.2c4.7-1.4 9.9-1 14 1.2" fill="none" stroke="currentColor" ' +
      'stroke-width="2.9" stroke-linecap="round"/>' +
      '<path d="M10.4 17c3.8-1.1 7.9-.7 11.2 1.1" fill="none" stroke="currentColor" ' +
      'stroke-width="2.6" stroke-linecap="round"/>' +
      '<path d="M11.6 21.5c2.9-.8 6.1-.5 8.5.9" fill="none" stroke="currentColor" ' +
      'stroke-width="2.3" stroke-linecap="round"/></svg></span>',

    livetv: '<span class="mark"><span class="badge">LIVE</span></span>',

    tvplus: '<span class="mark"><span class="badge tvplus">TV+</span></span>',

    iview: '<span class="mark"><span class="word iview">abc iview</span></span>'
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
    dot: '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="3" fill="currentColor"/></svg>'
  };

  /* ---------- state ---------- */

  var FALLBACK = {
    weather: { temp: 23, condition: "Partly cloudy", inside: 20 },
    batteries: [{ name: "House", level: 84 }, { name: "Cottage", level: 62 }],
    lines: [
      { icon: "snow", text: "Air con cooling to 22°" },
      { icon: "spa", text: "Spa 36° · heating to 38.5°" }
    ],
    now_playing: { title: "Sample song", subtitle: "Sample artist · Lounge + Kitchen", tint: ["#e8a06a", "#8f6fd0"] }
  };

  var hour12 = cfg.hour12 !== false;
  var sheet = null;
  var lastFocus = null;
  var toastTimer = null;
  var wallTimer = null;
  var front = $("wallA");
  var back = $("wallB");
  var wallIndex = 0;
  var tileIndex = 0;

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
    $("clock").textContent = (hour12 ? h : pad(h)) + ":" + pad(d.getMinutes());
  }

  /* ---------- background ---------- */

  function applyWall(index) {
    var list = cfg.wallpapers || [];
    if (!list.length) { return; }
    var url = list[index % list.length];
    back.style.backgroundImage = "url('" + url + "')";
    back.className = "wall on";
    front.className = "wall";
    var swap = front;
    front = back;
    back = swap;
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

  function batteryPill(battery) {
    var level = Math.max(0, Math.min(100, Number(battery.level) || 0));
    var cls = "pill";
    if (level <= 20) { cls += " low"; } else if (level <= 45) { cls += " warn"; }
    var fill = (16.6 * level / 100).toFixed(1);
    var el = document.createElement("span");
    el.className = cls;
    el.innerHTML =
      '<svg viewBox="0 0 26 15" aria-hidden="true">' +
      '<rect x="1" y="2.4" width="21" height="10.2" rx="2.8" fill="none" stroke="currentColor" stroke-width="1.5"/>' +
      '<rect x="2.9" y="4.4" width="' + fill + '" height="6.2" rx="1.5" fill="currentColor"/>' +
      '<rect x="23.4" y="5.6" width="2.2" height="3.8" rx="1.1" fill="currentColor"/></svg>' +
      '<span>' + battery.name + " " + level + '%</span>';
    return el;
  }

  function weatherIcon(condition) {
    var c = String(condition || "").toLowerCase();
    if (/rain|shower|storm|drizzle/.test(c)) { return "rain"; }
    if (/snow|sleet|hail/.test(c)) { return "snow"; }
    if (/sun|clear/.test(c)) { return "sun"; }
    return "cloud";
  }

  function render(data) {
    var info = $("info");
    info.innerHTML = "";

    var w = data.weather;
    if (w && (typeof w.temp === "number" || typeof w.inside === "number")) {
      var wr = row(weatherIcon(w.condition));
      var text = "";
      if (typeof w.temp === "number") {
        text = w.temp + "°" + (w.condition ? " " + w.condition : "");
      } else {
        text = w.condition || "";
      }
      if (text) {
        var s = document.createElement("span");
        s.textContent = text;
        wr.appendChild(s);
      }
      if (typeof w.inside === "number") {
        var sep = document.createElement("span");
        sep.className = "sep";
        sep.textContent = "·";
        wr.appendChild(sep);
        var ins = document.createElement("span");
        ins.textContent = w.inside + "° inside";
        wr.appendChild(ins);
      }
      info.appendChild(wr);
    }

    if (data.batteries && data.batteries.length) {
      var br = row(null);
      data.batteries.forEach(function (b) { br.appendChild(batteryPill(b)); });
      info.appendChild(br);
    }

    (data.lines || []).forEach(function (line) {
      if (!line || !line.text) { return; }
      var lr = row(line.icon || "dot");
      var span = document.createElement("span");
      span.textContent = line.text;
      if (line.accent) { span.style.color = "#f0c45a"; }
      if (line.dim) { span.style.color = "rgba(255,255,255,.55)"; }
      lr.appendChild(span);
      info.appendChild(lr);
    });

    var np = data.now_playing;
    var box = $("now");
    if (np && np.title) {
      box.hidden = false;
      $("nowTitle").textContent = np.title;
      $("nowSub").textContent = np.subtitle || "";
      var art = $("nowArt");
      art.style.backgroundImage = "none";
      if (np.art) {
        art.style.backgroundImage = "url('" + np.art + "')";
      } else if (np.tint && np.tint.length > 1) {
        art.style.background = "linear-gradient(140deg," + np.tint[0] + "," + np.tint[1] + ")";
      }
    } else {
      box.hidden = true;
    }
  }

  function note(text) {
    var el = $("dataNote");
    if (el) { el.textContent = text; }
  }

  function load() {
    var url = cfg.feedUrl + (cfg.feedUrl.indexOf("?") < 0 ? "?" : "&") + "_=" + Date.now();
    var request;
    if (!window.fetch) { render(FALLBACK); return; }
    fetch(url, { cache: "no-store" })
      .then(function (r) { if (!r || !r.ok) { throw new Error("http"); } return r.json(); })
      .then(function (data) {
        render(data);
        var d = new Date();
        note("Updated " + pad(d.getHours()) + ":" + pad(d.getMinutes()) + " · " + cfg.feedUrl);
      })
      .catch(function () {
        render(FALLBACK);
        note("Demo data (could not read " + cfg.feedUrl + ")");
      });
    return request;
  }

  /* ---------- dock ---------- */

  function markFor(app) {
    return MARKS[app.key] || '<span class="mark"><span class="word">' + app.label + "</span></span>";
  }

  function buildDock() {
    var dock = $("dock");
    dock.innerHTML = "";
    (cfg.apps || []).forEach(function (app) {
      if (app.divider) {
        var d = document.createElement("span");
        d.className = "divider";
        dock.appendChild(d);
      }
      var b = document.createElement("button");
      b.type = "button";
      b.className = "tile";
      b.setAttribute("aria-label", app.label);
      b.innerHTML = markFor(app);
      b.addEventListener("click", function () { launch(app); });
      dock.appendChild(b);
    });
  }

  function buildGrid() {
    var grid = $("appsGrid");
    grid.innerHTML = "";
    (cfg.apps || []).forEach(function (app) {
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

  function focusTile(index) {
    var all = tiles();
    if (!all.length) { return; }
    tileIndex = (index + all.length) % all.length;
    all[tileIndex].focus();
  }

  /* ---------- actions ---------- */

  function launch(app) {
    if (app.keyAction) { sendKey(app.keyAction); return; }
    if (!app.id) {
      toast("No Tizen app id for " + app.label + " yet — run: python tools/tizen.py apps");
      return;
    }
    openApp(app);
  }

  function openApp(app) {
    if (window.tizen && window.tizen.application && window.tizen.application.launch) {
      try {
        window.tizen.application.launch(app.id, function () {}, function (err) {
          toast("Could not open " + app.label + ": " + (err && err.message ? err.message : "failed"));
        });
      } catch (e) {
        toast("Could not open " + app.label);
      }
      return;
    }
    toast(app.label + " → app id " + app.id + " (needs the Tizen runtime)");
  }

  function sendKey(key) {
    if (!cfg.controlUrl) {
      toast("Live TV sends the " + key + " key, which needs controlUrl in config.js");
      return;
    }
    var url = cfg.controlUrl + (cfg.controlUrl.indexOf("?") < 0 ? "?" : "&") + "key=" + encodeURIComponent(key);
    fetch(url, { method: "POST" })
      .then(function (r) { toast(r && r.ok ? "Sent " + key : "Control URL returned an error"); })
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

    applyWall(0);
    setRotation(rotate);
    setSwitch($("toggleWall"), rotate);

    tick();
    setInterval(tick, 1000);

    buildDock();
    buildGrid();
    load();
    setInterval(load, Math.max(15, cfg.refreshSeconds || 60) * 1000);

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

      if (k === 37 || k === 39) {
        e.preventDefault();
        var i = tiles().indexOf(document.activeElement);
        if (k === 37) { focusTile(i <= 0 ? tiles().length - 1 : i - 1); }
        else { focusTile(i < 0 ? 0 : i + 1); }
      } else if (k === 38) {
        e.preventDefault();
        $("allAppsBtn").focus();
      } else if (k === 40) {
        e.preventDefault();
        if (document.activeElement === $("allAppsBtn") || document.activeElement === $("settingsBtn")) {
          focusTile(tileIndex);
        } else {
          focusTile(0);
        }
      }
    });

    document.addEventListener("visibilitychange", function () {
      if (!document.hidden) { tick(); }
    });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
