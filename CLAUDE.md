# CLAUDE.md

## Overview

PIXELMANCER — a falling-sand cellular-automata sandbox game in a single Java file
(`Pixelmancer.java`, zero dependencies, JDK 17+). No build system: `javac Pixelmancer.java && java Pixelmancer`.

## Architecture (all in Pixelmancer.java)

- **World**: 320x180 (`W`x`H`) byte grid `m[]` of material ids, rendered at 4x into a 1280x720 window.
  Parallel arrays: `life[]` (fuel/growth/gas lifetime), `stamp[]` (frame parity — prevents a cell
  updating twice per tick), `bg[]` (1 = was underground at gen time → dark cave backdrop instead of sky),
  `pin[]` (1 = placed in static mode, F key: skipped by the sim, refused by `trySwap`; `set()` and
  `ignite()` clear it, so any reaction un-freezes the cell).
- **Simulation** (`tick()`): bottom-up scan, alternating x direction per row per frame.
  Movement is density-based swapping (`trySwap`/`dens`), so sinking/floating is emergent.
  Per-element behaviour methods: `powder, liquid, gas, fire, lava, acid, plant, seed, ember, water`.
- **Heat** (`tempTick()`, `short[] temp`): per-cell temperature drives state changes via a table
  (water↔ice/steam, lava↔stone, sand→glass, radiant ignition, snow melt). Diffusion is stochastic
  (half the cells exchange with one random neighbour per tick; air conducts at 1/4 rate). Lava
  self-heats toward 1000° and only crusts on contact with water/ice/snow (or below 200°) — pure
  temperature crusting was tried and killed magma pockets via corner-cell double-drain in one tick.
  Explosions/lightning/meteors inject heat, which is what makes glass.
- **Interactions**: fire ignites via `flam()` on contact plus radiant ignition from the state table,
  acid via `corrodible()`, gunpowder (`BOOM`) triggers `explode()`, which chains by converting to fire.
- **Rendering** (`renderFrame()`): writes directly into the BufferedImage's int pixel array.
  Sky gradient + stars/sun/moon/clouds for non-`bg` empty pixels; per-material colors with hash noise;
  2D lighting = emitter field (`light[]`) blurred twice (`blur()`), mixed with time-of-day ambient `amb`.
  Sky pixels are NOT ambient-multiplied (the gradient already encodes night) — only glow is added.
- **Player**: 3x6 pixel wizard, axis-separated collision with 2px auto step-up, swim physics,
  hazard damage with i-frames; death regenerates the world.
- **Civilization** (`Folk`/`Village`/`Kingdom` inner classes): 2x3 agents with the same collision
  (`rectHits`), aging, drowning, hazard death. Villages build HOUSE/BRICK huts as world pixels
  (ghost-solid: cells collide for sand/water, but excluded from `rectHits` so people walk through),
  spawn babies against housing caps, sow SEED farms at era 1+. Kingdoms advance eras by population,
  declare wars (warriors mobilize, march via `warriorBrain`, melee + arson), and secede when
  `unrest > 60`. `folkTick` runs per tick; `villagesTick`/`kingdomsTick` every 60/120 frames.
  Stone/dirt melt at 1050° — deliberately above lava's 1000° self-heat cap, or magma pockets
  chain-melt the entire underground (this happened; don't lower it).
- **HUD** (`hud()`): drawn in screen space with Graphics2D over the scaled frame.

## Testing / verification

Headless screenshot mode (no window needed):
```bash
java Pixelmancer --shot out.png [timeOfDay]   # 0 = noon, 0.5 = midnight
```
It generates a world, simulates ~270 ticks with a scripted lava pour + meteor, and saves a PNG.
Use it after any sim or render change; check both day and night.

## Gotchas

- Don't name a method `move`, `show`, `list`, etc. — the class extends `java.awt.Canvas` and
  AWT `Component` has public methods with those signatures (that's why it's `moveCell`).
- `life[]` is bytes: always read as `life[i] & 0xff`, keep values ≤ 127 when setting.
- New cells must be written through `set()` (or set `stamp[i] = par`) or they'll update the same tick.
- `set()` assigns creation temperatures (lava 1100°, ice -25°, ...); direct `m[i] = X` writes in the
  state table intentionally keep the cell's temp and pin. Moving cells must carry `temp` (moveCell/swapCells do).
- Headless test suites live in the session scratchpad pattern (`PinTest`, `TempTest`): compile with
  `javac -cp . -d <dir> <dir>/XTest.java`, run with `java -cp .:<dir> XTest`.
