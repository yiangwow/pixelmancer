# PIXELMANCER — Expansion Roadmap

## Thesis

PIXELMANCER is not a platformer with sand in it. It is **a physics toy you can
break, and a machine kit disguised as a toy**. Every feature must serve one of
three loops:

1. **Discover** — "what happens if lava touches sand?" should have an answer worth screenshotting.
2. **Build** — give players parts that combine into contraptions they invented, not ones we designed.
3. **Share** — a creation that can't leave the game might as well not exist.

The wizard stays — he's the camera, the stakes, and the charm — but he serves
the sandbox, not the other way around.

Constraints that make this project special and must never break:
- **Zero dependencies, plain `javac`.** Anyone with a JDK can build it.
- **Readable Java.** The codebase *is* content: tinkerers should learn from it.
- **Everything simulated.** No scripted set pieces; if it happens, the automaton did it.

---

## Pillar 1 — Heat: one system to replace ad-hoc chemistry  (v0.2)

The single highest-leverage change. Today every interaction is a hand-written
special case (water+lava, fire+oil...). A per-cell **temperature field** replaces
most of them with one rule: *materials change state at threshold temperatures.*

- `temp[]` byte array, ambient-biased; emitters (fire/lava/ember) heat, water/ice cool.
  Cheap stochastic diffusion (each tick, a cell averages with one random neighbor)
  — no full convolution needed at 60 Hz.
- State table per material: `WATER <0° → ICE`, `>100° → STEAM`; `LAVA <700° → STONE`;
  `STONE >900° → LAVA` (the loop closes!); `SAND >1200° → GLASS`; `SNOW → WATER`; `ICE` melts.
- **Payoff moments that come for free:** lightning striking a beach fuses it into
  glass. Lava pouring into a lake grows an obsidian shelf. A world can freeze over
  at night in a cold biome. Fires spread realistically (heat radius, not adjacency).

New elements unlocked by the system: **ICE** (slippery player physics — 3 lines),
**SNOW** (powder that melts), **GLASS** (transparent solid — render tint),
**HEATER / COOLER** blocks (creative tools; they just clamp local temp).

This *removes* code as much as it adds: water/lava/fire special cases collapse
into table entries.

## Pillar 2 — Machines: the Powder Toy hook  (v0.3)

Static placement (F) was the first machine part. Add the rest of the kit —
each one is small, but they multiply:

- **SOURCE** — emits its stored element forever (paint one with lava selected → lava tap).
- **DRAIN** — deletes whatever touches it. Source + drain = flow circuits.
- **FAN** — pushes powders/liquids/gases directionally (4 orientations). Fountains, sorters, hovercraft pits.
- **FUSE** — burns slowly and predictably in a line. This is the timing wire; with
  gunpowder it makes *sequenced* demolitions. People will build Rube Goldberg machines.
- **PORTAL** (paired) — matter that falls in A exits B with velocity preserved.
  Perpetual waterfalls. Infinite sand fountains. The screenshot generator.

Design rule: **no logic gates, no electronics.** The moment it needs a manual,
we've left "toy". Fuse + source + fan + portal is Turing-enough for fun.

## Pillar 3 — Alive: the world does things without you  (v0.4)

Sandboxes die when they're only reactive. Self-propagating and ambient elements
give players something to *tend*:

- **FUNGUS** — slowly eats plant matter, spreads; fire is the cure. Creates
  "save the forest" moments nobody scripted.
- **CORAL** — grows underwater toward light, petrifies out of water. Underwater gardens.
- **Critters (entities, not cells — like meteors):** fish that school in water and
  cook into... regret, when the lake boils; fireflies at night (light seeds — the
  lighting system already pays for them); birds that flee fires. 3-6 pixels each,
  ~40 lines per species. Charm-per-line is off the charts.
- **Procedural audio** — pure `javax.sound.sampled` synthesis, no asset files:
  filtered-noise fire crackle, brown-noise rain, sine-thump explosions with the
  screen shake, glassy plinks for casting. Sound is half of "juicy".

## Pillar 4 — Bigger world, better wizard  (v0.5)

- World grows to ~3 screens wide (960×270 ≈ 260k cells — Java holds 60fps if the
  update loop stays branch-lean; profile first, this is the one perf risk).
- **Camera** follows the wizard; free-scroll when a build tool is active.
- **Biomes** along the width: temperate / desert / frozen / volcanic — same
  generator, different material palette and starting temperature. Heat system (P1)
  makes them behave differently, not just look different.
- Player feel pass: coyote time, wand recoil, ice sliding, a dash. Campfire
  proximity slowly regenerates hearts — gives fire a *reason* beyond arson.

## Pillar 5 — Share: creations must escape the game  (v0.6, release)

- **World saves are PNG images.** One pixel per cell, exact material colors —
  the save file *is* a picture of your world, postable anywhere, loadable by
  anyone. (Pin/temp go in ancillary PNG chunks.) This is the killer trick:
  the community trades screenshots that are playable.
- **Seed codes** — world gen already deterministic-capable; surface the seed, let
  people type one in.
- **GIF recorder** — hold a key, get the last ~6 seconds as an animated GIF
  (a pure-Java GIF89a encoder is ~150 lines). Explosions go viral or they didn't happen.
- **Ship it:** public GitHub repo (source is part of the appeal) + itch.io page
  with the jpackage win64 zip + three GIFs. itch's falling-sand tag has an audience.

## Cross-cutting: make the code the mod API

The audience that tinkers *with* the game overlaps the audience that tinkers
*in* it — lean into Java being readable:

- Split the single file into ~6 short ones (`World`, `Elements`, `Player`,
  `Render`, `Sound`, `Main`). Still `javac *.java`, still no build system.
- Move element definitions into a **data table** (color, density, flammability,
  corrodible, state-change rows). Adding a boring element becomes one line;
  exotic behaviors stay as honest Java methods.
- Stretch: `elements.txt` hot-reloaded on keypress — modding without recompiling.
- Grow `PinTest` into a real headless suite (`java Tests`) — the `--shot` +
  assertion pattern already works; every pillar adds cases.

## Explicitly cut (and why)

- **Multiplayer** — cost is a whole engine; the share pillar gets 80% of the social value.
- **Electronics/logic elements** — turns the toy into homework (Powder Toy already exists).
- **Crafting/inventory/progression** — that's Terraria's lane; our lane is physics.
- **Infinite worlds/chunks** — finite worlds keep saves-as-PNG and explosions global.

## Order of battle

| Version | Theme | Rough size |
|---|---|---|
| v0.2 | Heat + ice/glass/snow/heater/cooler | ✅ shipped 2026-07-12 |
| v0.3 | Folk: WorldBox-style civilization (villages, eras, wars, rebellion) | ✅ shipped 2026-07-12 |
| v0.35 | Machines: source, drain, fan, fuse, portal | 2 sessions |
| v0.4 | Fungus, coral, critters, synth audio | 2-3 sessions |
| v0.5 | Camera, 3-screen worlds, biomes, player feel | 2 sessions |
| v0.6 | PNG saves, seeds, GIF export, GitHub + itch.io release | 2 sessions |

Start with **Heat**. It deletes existing special-case code, multiplies the
reaction space, and every later pillar (biomes, critters, machines) stands on it.
