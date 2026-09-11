# 0 FOMO — Brand Identity Brief

Renamed from **wah gwaan** on 2026-09-11. Publisher: **ARC Technology**
(GitHub: `arctechnology-hq/zero-fomo`). Mark v2 "The Embrace" replaced v1 "The Loop" the same day.

## 1. Positioning

| | |
|---|---|
| Name | **0 FOMO** (spoken "zero FOMO"). Always the digit `0`, never the letter O. |
| One-liner | Everything happening near you, before it sells out. |
| Promise | You are already in the loop. |
| Personality | Confident, quick, a little cheeky. Nightlife energy, daytime clarity. Never corporate, never cluttered. |
| Audience | 18–40, mobile-first, locals and visitors in the Caribbean and US cities. |
| Voice | Short sentences. Second person. No exclamation marks in UI copy. |

## 2. The mark: "The Embrace"

The brand idea is **embracing fear**: FOMO is not something to cancel, it is
something to hold. The zero is drawn as two arms that wrap around a small flame
and cross at the bottom.

- **Arms** = the `0`. Two arcs (volt on the left, aqua on the right) start at the
  top with a small opening between them, sweep down, and overlap at the bottom.
  The overlap is the embrace; the opening at the top is the way in.
- **Flame** = the fear. Small, centred, held. It is the only warm-shaped element in
  the system and it never grows larger than a third of the ring.
- The two-colour arms read as motion and as two people; in one colour (themed
  icons, notifications) the crossing still reads because the arm ends overlap.

The mark is the `0` of the wordmark: lockup reads `[mark] FOMO`. It must survive at
16 px (favicon, notification icon) and in one colour (Android themed icons). At
24 dp the flame is a solid teardrop with no inner counter.

## 3. Palette

| Token | Hex | Use |
|---|---|---|
| Void | `#0B0F14` | Primary background (dark-first product) |
| Volt | `#C8FF2E` | Left arm of the mark, primary CTA, live/“happening now” signals |
| Paper | `#F5F4EF` | Text on dark, light-mode background |
| Aqua | `#19D3C5` | Right arm of the mark, secondary accent, links |
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
3. Never rotate the mark; the opening is always at the top, the crossing at the bottom.
4. On light backgrounds the volt arm becomes Void (see
   `branding/zero-fomo-logo-light.svg`); Volt never sits on Paper.
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

Anchor the prompt on the mark spec so the model explores around the idea instead
of inventing a different symbol.

```
modern minimalist logo for "0 FOMO", a local events discovery app. Concept:
embracing fear. The zero is formed by two thick rounded arms that wrap around a
small flame at the centre and overlap at the bottom like a hug; the arms are
open at the top. Left arm electric lime green (#C8FF2E), right arm teal
(#19D3C5), flame off-white, near-black background (#0B0F14). Wordmark "FOMO" in
a bold geometric grotesque to the right, the embrace acting as the zero. Flat
vector, geometric, no gradients, no shadows, no bevel, no extra icons, no
texture, centred, generous negative space, brand identity presentation
--v 7 --style raw --ar 3:1 --no photorealism, 3d, gradient, glow, mockup, hands
```

Variants to request in the same session:

- `--ar 1:1` mark only, no wordmark (app icon).
- "on off-white background (#F5F4EF), near-black left arm, teal right arm,
  near-black flame" for the light version.
- "sticker sheet, six variations: arm thickness, size of the opening, flame
  with and without inner counter" to explore.
- "the flame replaced by a small heart" and "the flame replaced by a spark" as
  alternates for the fear element; keep the arms unchanged.

Reject any output where the arms become literal hands, where the flame is missing,
or where a second symbol appears. Redraw the chosen direction as clean vectors (the
SVGs here are the starting point) and never ship the raster straight from the
generator.

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
