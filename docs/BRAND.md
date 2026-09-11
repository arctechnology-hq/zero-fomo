# 0 FOMO — Brand Identity Brief

Renamed from **wah gwaan** on 2026-09-11. Publisher: **ARC Technology**.

## 1. Positioning

| | |
|---|---|
| Name | **0 FOMO** (spoken "zero FOMO"). Always the digit `0`, never the letter O. |
| One-liner | Everything happening near you, before it sells out. |
| Promise | You are already in the loop. |
| Personality | Confident, quick, a little cheeky. Nightlife energy, daytime clarity. Never corporate, never cluttered. |
| Audience | 18–40, mobile-first, locals and visitors in the Caribbean and US cities. |
| Voice | Short sentences. Second person. No exclamation marks in UI copy. |

## 2. The mark: "The Loop"

A bold zero drawn as a ring with a deliberate gap at one o'clock, and a solid dot at
its centre.

- **Ring** = the `0`, and a radar sweep / the loop you are in.
- **Gap** = the opening you came through. It also makes the ring read as motion, not a
  full stop.
- **Dot** = you, already inside.

The mark is the `0` of the wordmark: lockup reads `[mark] FOMO`. It must survive at
16 px (favicon, notification icon) and in one colour (Android themed icons).

## 3. Palette

| Token | Hex | Use |
|---|---|---|
| Void | `#0B0F14` | Primary background (dark-first product) |
| Volt | `#C8FF2E` | The ring, primary CTA, live/“happening now” signals |
| Paper | `#F5F4EF` | Text on dark, light-mode background |
| Aqua | `#19D3C5` | Secondary accent, the dot on light backgrounds, links |
| Coral | `#FF5C5C` | Destructive / sold-out / errors |
| Graphite | `#141C26` | Cards and elevated surfaces on Void |

Contrast: Volt on Void 15.9:1, Paper on Void 17.2:1, Void on Volt 15.9:1. Aqua is
never used for body text on Paper (3.1:1); use it for icons and large type only.

## 4. Typography

- Display / wordmark: **Space Grotesk 700** (fallback Inter Tight 700).
- UI: **Inter** 400/500/600.
- Numerals are tabular in listings (`font-feature-settings: "tnum"`).

## 5. Usage rules

1. Clear space around the lockup = the dot diameter on every side.
2. Minimum lockup width 96 px; below that use the mark alone.
3. Never rotate the gap; it always sits at one o'clock.
4. Never put the Volt ring on a light background (use Void ring + Aqua dot, see
   `branding/zero-fomo-logo-light.svg`).
5. No drop shadows, gradients, or outlines on the mark.

## 6. Files in `branding/`

| File | Purpose |
|---|---|
| `zero-fomo-mark.svg` | App icon / avatar, 160 × 160, dark tile with 36 px radius |
| `zero-fomo-logo.svg` | Horizontal lockup on Void (default) |
| `zero-fomo-logo-light.svg` | Horizontal lockup on Paper |

The Android adaptive icon (`zero_fomo/app/src/main/res/drawable/ic_launcher_*.xml`)
and the notification icon (`ic_stat_zerofomo.xml`) are hand-ported from the mark.
Wordmark SVGs use live text; convert to outlines (Inkscape → Path → Object to Path)
before sending to print or to the Play / App Store listing generators.

## 7. Generative prompt (Midjourney v7 / Ideogram / Imagen)

Use the mark spec as the anchor so the model does not invent a different symbol.

```
minimalist app logo for "0 FOMO", a local events discovery app. The zero is a thick
geometric ring with a small gap at the one o'clock position and a solid dot at its
centre, like a radar loop or a "you are here" pin. Wordmark "FOMO" in a bold
geometric grotesque typeface to the right of the ring, the ring acting as the zero.
Electric lime green ring (#C8FF2E) on near-black background (#0B0F14), off-white
text. Flat vector, no gradients, no shadows, no bevel, no extra icons, no
background texture, centred, generous negative space, brand identity presentation
--v 7 --style raw --ar 3:1 --no photorealism, 3d, gradient, glow, mockup
```

Variants to request in the same session:

- `--ar 1:1` mark only, no wordmark (app icon).
- Same prompt with "on off-white background (#F5F4EF), near-black ring, teal dot
  (#19D3C5)" for the light version.
- Add "sticker sheet, six variations of the gap width and dot size" to explore.

Reject any output where the gap moves, the dot is missing, or a second symbol appears.
Then redraw the chosen direction as clean vectors (the SVGs here are the starting
point) — never ship the raster straight from the generator.

## 8. Naming in code

| Surface | Value |
|---|---|
| Display name | `0 FOMO` |
| Android applicationId / namespace | `com.arctechnology.zerofomo` |
| Kotlin package | `com.arctechnology.zerofomo` |
| Deep-link scheme | `zerofomo://event/{id}` |
| Room database | `zerofomo.db` |
| Gradle root project | `0 FOMO`, module dir `zero_fomo/` |
| Calendar PRODID / UID domain | `-//0 FOMO//Events//EN`, `<id>@0fomo.app` |
| Target web domain (to acquire) | `0fomo.app` |
