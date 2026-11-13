# 1.7 plan: the light pass

Status: the Fire TV items are implemented, 12 November 2026. Follows 1.5.5. Independent of
[1.6](ROADMAP-1.6.md): 1.6 adds live TV to the Samsung, this changes how everything already on
screen looks and moves.

Three notes on the plan as it stands after the build:

- **Item 7 was already done.** The countdown hairline has shipped since 1.3 (the dismiss animator in
  `AlertReceiver` drives it), so there was nothing to add - only a doorbell path to check.
- **Item 8 is Samsung work** and was set aside with that screen: the Tizen dashboard is at its limit
  for the hardware and is not being extended.
- **Item 13 was left out.** Renaming six radii would touch every call site for no pixel of
  difference; the three motion beats it also asked for already exist as `MS_FOCUS`,
  `MS_REVEAL` and `MS_FADE`.

The house rules still apply:

- Build it on the Stick and check it there first.
- Then the Cube and the Samsung.
- Then the PR and the release.

Everything below is local UI. No new Home Assistant keys, no new permissions, no new assets, no new
network calls. That is deliberate: this is the release that costs nothing to run.

## At a glance

| # | Item | Where | Benefit | Size | Risk |
|---|------|-------|---------|------|------|
| 1 | Glass with a lit edge | Fire TV, Samsung | The dock stops reading as a flat grey bar | S | Low |
| 2 | Artwork never pops in | Fire TV, Samsung | Removes the most common visible glitch | S | None |
| 3 | The wallpaper leans with the cursor | Fire TV | The screen gets depth it has never had | M | Medium: measure on the Stick |
| 4 | Bounce light on the shadows | Fire TV | Focus feels lit, not drawn | S | Low: eyeball both TVs |
| 5 | One focus language | Fire TV | A row stops snapping where the rest springs | S | Low |
| 6 | Numbers arrive softly | Fire TV, Samsung | The block stops flickering as values change | S | Low |
| 7 | The alert card shows its own clock | Fire TV | You can see how long a doorbell card will stay | XS | None |
| 8 | The Samsung gets real glass | Samsung | The biggest single visual jump on the 2020 set | M | Medium: Chromium 69, weak GPU |
| 9 | Motion: Full / Reduced | Fire TV | Respects "remove animations", and helps the Stick | M | Low |
| 10 | The Home handoff has no seam | Fire TV | One less hitch on the most-seen transition | M | Medium: memory |
| 11 | Sheen discipline | Fire TV | Fast scrolling stops shimmering | XS | Taste, reversible |
| 12 | The saver gets a vignette | Fire TV | White type holds on a bright canvas | XS | None |
| 13 | One radius and timing scale | Both | Keeps the next change from inventing a fourth | XS | None |

If we only do three: **1, 2 and 8.** They are the ones a guest notices without being told to look.

Suggested order: 1, 2, 5, 7, 12, 13 (one build, one Stick check), then 8 (its own Samsung pass),
then 3, 4, 10, then 6, 9, 11.

---

## 1. Glass with a lit edge

**What:** the glass draws a vertical gradient instead of one flat colour: lighter along its top
edge, the tinted colour through the middle, darker at the foot - plus the existing 1dp hairline.
The dock pill, the two Now cards and the Home-key cover's pill all read as one sheet of glass with
light falling on it. (The 44dp round chips stay flat: at that size the gradient is invisible.)

**Why:** today `HomeTheme.GLASS` is a single 35% black and `setStroke` is the only edge. A real pane
has a brighter top edge where the light catches it; a flat fill is what makes the dock look painted
on rather than laid over the photo. tvOS does exactly this, for the same reason.

**How:**

- `HomeTheme.glassPill()` returns a `GradientDrawable` with three stops from a new
  `glassStops(int tint)`, oriented `TOP_BOTTOM`, keeping the 1dp hairline.
- `MainActivity.tintGlass()` currently animates one ARGB value and calls `setColor` on everything in
  `glasses`; it becomes `setColors(glassStops(colour))` on the same animator, one recompute a frame
  for the two seconds a wallpaper change lasts. Cheap and only while the photo changes.
- `paintChip()` does the same for the unfocused state; the focused white pill is unchanged.
- Samsung: `.dock`, `.now` and `.nav-btn` already take `var(--glass)`; add a
  `linear-gradient(180deg, rgba(255,255,255,.06), rgba(255,255,255,0) 42%, rgba(0,0,0,.10))` as a
  second `background-image` layer over the tint. No new nodes.

**Checks:** the dock on a bright snow photo and on a dark painting; the top edge must read as a
highlight, not as a second border. The cover's pill must still match the dock exactly on arrival.

## 2. Artwork never pops in

**What:** every image that arrives over the network fades in instead of appearing between frames:
the resume tiles, the Now cards' stills, the full-screen idle still, and the app banners in the grid.

**Why:** the artwork is fetched off the main thread and assigned the moment it decodes. On a busy
LAN that is a tile going from empty to full in one frame, and it is the most common visible glitch
left on the screen - more visible than any of the motion currently there.

**How:** a `softBitmap(ImageView, Bitmap)` helper next to `loadArt` in `MainActivity`: if the view is
already showing that bitmap, do nothing; otherwise set it at alpha 0 and animate to its intended
alpha over `MS_FOCUS`. For the ambient full-screen swap, fade the *new* bitmap over the old for
400ms rather than a hard replace (`paintAmbient` already has the second layer for this).

**Checks:** watch a cold start with the feed warmed and one with it cold; nothing should appear
between one frame and the next. Frame timing on the dock must be unchanged.

## 3. The wallpaper leans with the cursor

**What:** a parallax of up to 8dp horizontally and 4dp vertically between the wallpaper and the UI,
easing over `MS_TILT` in the direction the cursor travelled. It reads as the photo sitting behind
the glass, not on it.

**Why:** the screen is depth-cued everywhere except the layer that carries the most depth. Apple TV
gets its "living" feel mostly from the top layer being closer than the photo; the dock's lift and
the cards' separation only half sell it on their own.

**How:**

- **Prerequisite:** `showWallpaper()` currently starts the photo at scale 1.00 and drifts to 1.07, so
  at the moment a wallpaper lands there is no slack to move into. Change the drift to 1.03 → 1.09:
  1.5% of the width (18dp at 1200dp) of slack on each side is enough for an 8dp lean, and the zoom
  still only steps once a second.
- Feed `lastStep` and the row change into a `parallax(int dx, int dy)` that animates
  `wallFront`'s `translationX/Y`. One view translation, no redraw of the children.
- Also stop it dead while the screensaver, idle mode or the HA sheet is up - those own the screen.
- **The gate:** a pass along the dock on the Stick must stay inside the 1.5.1 baseline of 2-13
  frames over 16ms. If it does not, the fallback is to lean the *cards and dock* by 2dp instead of
  moving the 4K layer, which costs nothing at all.

**Checks:** frame timing on the Stick, before/after, three passes; the photo must not show its edge
at either end of the dock; the drift must still be invisible over 20 seconds.

## 4. Bounce light on the shadows

**What:** the pre-baked focus shadow takes a hint of the tile's own colour (about 12% of it mixed
into the black) instead of being neutral. A deep-blue app casts a faintly blue shadow, a warm one a
warm shadow. The 1dp rim does the same at 8%.

**Why:** the shadow is currently the thing that says "off the wallpaper", and it is the only part of
focus that does not know what it is under. Colour in the shadow reads as light bouncing off the
tile - the one cheap trick that makes a composited screen feel photographed.

**How:** `AppTile.shadow()` already caches one bitmap per size and fades an `ImageView`; the tint
goes on the `ImageView` (`setColorFilter(..., SRC_IN)`), never on the shared bitmap, so the cache
stays one entry per size. Use the `app.color` already sampled by `sampleColor`, at low strength so
a pale app cannot produce a grey smear. Falls back to plain black above a luminance threshold.

**Checks:** both TVs, a dark app and a white-banner app; the shadow must not read as a colour cast.
Sitting next to the dock, the difference should be felt rather than named.

## 5. One focus language

**What:** the Continue Watching row stops snapping. Its tiles scale by setting `setScaleX` directly
because a property animator inside the `HorizontalScrollView` never produced a visible change
(1.3.3). They now animate through a `ValueAnimator` driving `setScaleX/Y` per frame - the same path
that already works - at `MS_FOCUS`, with the dock's `settle()` curve, plus the dock's 4dp lift at a
slightly smaller amount. The row then moves exactly like the dock it sits under.

**Why:** every other focusable thing on the screen springs; this one cuts. That single cut is what
makes the row feel like a different app.

**How:** a small `springScale(View, float to)` helper used by the resume tiles; keep the rim and
shadow fades as they are.

**Checks:** along the row and back, on the Stick; the scale must not fight the scroll or clip on the
row's ends.

## 6. Numbers arrive softly

**What:** a value that changes fades out in 90ms, swaps, and fades back in 160ms, instead of
replacing its characters between two frames: the weather and battery lines, the sheet's tiles, the
card meta line.

**Why:** the clock already rolls and reads alive; everything else clicks over. On a screen that
updates every fifteen seconds, that is a visible jolt in an otherwise still composition - and it
happens while someone is reading the line.

**How:** `softText(TextView, String, float toAlpha)`, which no-ops when the text is unchanged and
restores the caller's intended alpha (a stale line is dimmed to 55%, and must return to 55%, not 1).
Never used for the clock, which has its own roll.

**Checks:** watch one HA poll go by on a still screen; nothing should jump. A stale line must stay
dim after updating.

## 7. The alert card shows its own clock

**What:** a 2dp hairline along the card's bottom edge drains from full width to nothing over the
card's lifetime, in the brand colour when there is one, else 30% white.

**Why:** cards appear over a programme for ten seconds and then leave; there is currently no way to
tell whether it is about to go. tvOS's now-playing bar answers the same question with the same
device.

**How:** the dismiss `ValueAnimator` in `AlertReceiver` already runs for the card's whole life;
drive the bar's `scaleX` (pivot left) from the same fraction. No extra timer.

**Checks:** a ten-second test alert; the bar must reach zero as the card leaves, not before.

## 8. The Samsung gets real glass

**What:** the Samsung's dock, cards, buttons and sheets stop being flat translucent fills and get a
soft, blurred sampling of the current wallpaper behind them - the thing `backdrop-filter` would give
and Chromium 69 does not have. Plus the sheet slides in the way the Fire TV's does, and the CSS
timings move onto the same three beats as the launcher.

**Why:** the 2020 set is where the dashboard looks least like the product: the same tint on a flat
panel reads as grey plastic, and its sheets only fade. This is the largest visible change available
on that screen without new hardware.

**How:**

- When `applyWall` loads a photo, draw it into a 96x54 canvas (one `drawImage`, no JS blur) and put
  the data URL into a `--frost` custom property. A 96x54 image stretched by `background-size: cover`
  is bilinear-smoothed by the compositor, which is a blur that costs nothing after load.
- `.dock`, `.now`, `.nav-btn` and `.sheet` take `background-image: var(--frost)` under their tint
  gradient. The elements sample different regions of the photo because each crops it; at that blur
  level it is invisible, and it avoids per-element positioning.
- Crossfade the layer's opacity over the same 2 seconds as the tint, so a wallpaper change moves as
  one thing.
- Sheets: `.ha-panel` slides 40px from the right with a fade at `--reveal` (matching the Fire TV);
  the Apps and Settings sheets rise 24px.
- Tokens in `:root`: `--focus: 160ms`, `--reveal: 240ms`, `--fade: 400ms`; replace the current mix of
  .16/.18/.2/.22/.24/.4/.6s so both screens breathe at the same rates.

**Checks:** on the TV, not just the laptop - the dock must still scroll and the page's wallpaper
drift must not stutter. Package size must not move (the canvas is generated, not shipped). Sheet
open/close must not leave a stale frosted image after a wallpaper change.

## 9. Motion: Full / Reduced

**What:** a Settings row that trims movement to fades only - no lean, no sheen, no parallax, no
dock lift, no launch zoom - and the launcher also honours Android's own "remove animations" switch
through `ValueAnimator.areAnimatorsEnabled()`.

**Why:** three reasons that all point the same way. Accessibility settings exist for a reason and
this app currently ignores them. The Stick is bound by a 4K panel at about 30ms a frame, and motion
is the first thing to give up. And some people simply prefer the calmer screen.

**How:** a `motion` field read from the `launcher` shared preferences, beside `nav_sounds`; one
`boolean prefersMotion()` consulted by `lift`, `sweep`, `parallax` and `zoomOpen`; a row modelled on
`soundsRow()`. Reduced keeps the entrance (alpha only), the crossfades and the clock roll - those
are legibility, not decoration.

**Checks:** Reduced on the Stick: every focus move is a fade in place, nothing stutters, and the
screen still reads as finished. Full must be unchanged.

## 10. The Home handoff has no seam

**What:** the launcher takes the cover's already-decoded wallpaper as its first frame instead of
decoding the same photo a second time, and the cover's fade is retuned to the launcher's reveal beat
so the two cross as one move.

**Why:** pressing Home is the most-seen transition in the product, and on a cold start it does the
expensive thing twice: the cover has already decoded the photo ahead of time, and then the launcher
decodes the same file again on a thread while its black root shows through. `HomeKeyService.coverWall`
is `static` in the same process, so the second decode - and the black frame it leaves - is avoidable.

**How:** `HomeKeyService.coverWall` is a full-size RGB_565 decode of the photo the launcher last
showed. On `onCreate`, when it is present and matches the saved index, seed `wallBack` with that same
bitmap (the reference, not a copy) and paint it immediately, so the first frame drawn is already the
photo. `nextWallpaper()` then runs as it does now and fades the launcher's own decode in over it,
which means the crossfade happens between two identical pictures and stops being visible. Keep the
field rather than clearing it: the reference is shared, so peak memory is one copy where it is two
today, and the cover stays pre-armed for the next Home press.

**Checks:** `dumpsys meminfo` before/after on both TVs; kill the launcher, press Home, and check
there is no black frame and no visible sharpening as the full-size decode lands. Frame timing at idle
unchanged.

## 11. Sheen discipline

**What:** the sheen sweeps when focus *enters a region* or after roughly half a second of quiet, not
on every single step.

**Why:** holding right along the dock fires a sweep on every tile. At speed that reads as shimmer,
which is the opposite of the calm the rest of the screen is going for. One sweep per arrival is the
tvOS effect; one per step is a laser show.

**How:** a single `lastSweepAt` guard inside `AppTile.sweep`, cleared when the row changes. This is
the most subjective item here, so it ships behind the Motion row's existing style of preference:
easy to flip back after a day's viewing.

**Checks:** a slow single step (should still sweep) and a held right (should sweep once).

## 12. The saver gets a vignette

**What:** a soft elliptical vignette over the painting, under the clock - darkest in the corners,
clear at the centre - drawn once as a cached bitmap and laid over the art.

**Why:** the paintings are brighter than the photographs were, and the type sits in their bottom
corner. The floor gradient already handles the bottom; the top corners are where a pale canvas
fights the credit and the painting's own frame. A vignette also reads as a projected image, which is
the register the saver wants.

**How:** `AppTile.shadow` already proves the pattern: build a 480x270 ARGB bitmap once, radial
transparent centre to 35% black at the edge, `ImageView` with `FIT_XY` over the art. Also let the
credit rise 4dp as it fades in, as the card's words already do.

**Checks:** Whistler (dark) and the Hay Wain (bright); the painting must not look dimmed overall.

## 13. One radius and timing scale

**What:** the radii and durations that are currently literals at their call sites become named
constants (`RADIUS_TILE`, `RADIUS_CARD`, `RADIUS_SHEET`, and the three `MS_*` beats already exist),
and the Samsung's CSS reads the same three beats.

**Why:** there are six radii and about a dozen durations in the code today. Every one that is not a
token is an invitation for the next change to invent a seventh. This is the item that keeps 1.8 from
undoing 1.7.

**How:** no behaviour change; `HomeTheme` gains the radius names, `:root` gains the duration names,
and the literals are replaced where they already equal a token.

**Checks:** build and eyeball; nothing should move by a pixel.

---

## How we will judge it

The gate is the same one 1.5 was held to, because it is the one that matters on a 4K panel:

- **Stick, along the dock:** frames over 16ms per pass. Baseline is 2-13 (1.5.1); a pass must not
  leave that range. `dumpsys gfxinfo` before and after each item, three passes each.
- **Stick, idle:** frames per 5 seconds. Baseline is 19 (1.5); nothing here may raise it.
- **Cube:** one pair of eyes in a dark room on all thirteen items, because that is the only test that
  can fail something that measures fine.
- **Samsung:** the dashboard must scroll and the wallpaper drift must step without tearing.
- **Screens:** one `adb screencap` before and after each item, on the same wallpaper, kept with the
  PR - the same way the README's own shots were made.
- **Tests:** `bash tests/run.sh` for anything pure. Only item 7's fraction and item 2's "is this the
  same bitmap" rule are testable without a TV; add them if the helper has a rule worth holding.

## Not in this pass

- **A glow for focus.** Rejected once already (1.5) and still right: a glow disappears on a bright
  photo, a shadow does not. Item 4 puts colour *in* the shadow instead.
- **Ken Burns on the full-screen idle card.** 1.5.5's reason holds - continuous motion all evening
  costs GPU and power on a device already bound by its panel.
- **Film grain, bloom, chromatic edges.** All of them are per-frame full-screen work; the Stick has
  no headroom for any of them at 4K.
- **Animated weather icons in the status block.** They would be the only moving thing in a still
  block, and the block's job is to be read in half a second.
- **Wallpaper-derived accent for alerts.** The amber means "something needs you" and nothing else.
  Borrowing the photo's colour would make a warning look decorative.
- **3D tilt on the Now cards.** They are wider than the tilt budget reads well at, and their focus
  is already a rim and a lighter glass.
- **Cross-device sync of the visual settings.** Two TVs, two preferences, no server; not worth it.

## Release checklist

1. Changelog entry, `versionName` 1.7 / `versionCode` 18 (1.6's slot stays free if it ships first).
2. `bash tests/run.sh` passes.
3. Stick: install, reboot, Home bound; frame timing before/after; every item eyeballed.
4. Cube: install; Home, dock, cards, sheet, saver.
5. Samsung: package, install, the frost and the sheet.
6. PR with the before/after screencaps; merge; generic APK from `main`; release.
