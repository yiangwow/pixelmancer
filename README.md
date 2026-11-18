# PIXELMANCER

A falling-sand sorcery sandbox in **one Java file, zero dependencies**.

Every pixel of the world is simulated: sand falls, water flows and carves,
oil floats on water, lava turns water to stone, fire spreads through forests,
acid eats terrain, buried gunpowder veins chain-explode, plants drink water
and grow. There is a day/night cycle with real 2D glow lighting, weather with
lightning storms, meteors on demand, and a very small wizard with a very big wand.

## Run

```bash
javac Pixelmancer.java
java Pixelmancer
```

Needs any JDK 17+ (uses switch expressions). Window is 1280x720.

## Controls

| Input | Action |
|---|---|
| A/D or ←/→ | move |
| W / space / ↑ | jump (hold to swim up in water) |
| mouse + LMB | cast the selected element at the cursor |
| RMB | erase / dig |
| 1-9 0 - = | pick element directly |
| E / mouse wheel | cycle elements |
| F | placement mode: **falling** (everything obeys gravity, even stone) or **static** (everything freezes in place) |
| [ ] | brush size |
| M | call down a meteor |
| R | cycle weather: clear → rain → snow |
| T | thermal view — see the world as temperatures |
| N | generate a new world |

## Elements

sand · water · stone · dirt · wood · plant · seed · oil · lava · fire · acid ·
boom (gunpowder) · ice · snow · heater · cooler — plus glass, steam, smoke and
embers, which you don't place, you *cause*.

## Folk

The world is inhabited. Two tribes spawn with every new world; they wander,
age from babies to grey-haired elders, build villages of real wood-pixel huts
(brick, once their kingdom's tech advances), plant farms, and raise banners in
their kingdom's color. Kingdoms research through four eras, declare wars —
warriors march on enemy villages, fight, and **burn them down with the actual
fire simulation** — and when unrest runs high, distant provinces secede and
turn on their motherland.

You are their god, for better or worse:

- Select the **folk** brush (K) and paint people anywhere. Three strangers on
  a hill will found a brand-new kingdom.
- RMB doesn't just dig — it **smites**.
- Every disaster you already have is now a story: meteors are extinction
  events, snowstorms freeze their lakes, lightning burns their forests.
- `,` / `.` control simulation speed (up to 8x), **P** pauses. Watch history
  at fast-forward like the god-sim intended.

The kingdom chronicle lives in the top-right: name, era (I–IV), population,
and who's at war.

## Heat

Every pixel has a temperature, and temperature drives everything: water
freezes and boils, lava crusts to stone when quenched (and stone melts back
to lava if you really commit), snow melts in the morning sun, oil auto-ignites
near heat without touching it, and **lightning striking a beach fuses it into
glass**. Deep underground there are magma pockets that stay molten until you
let water in. Press **T** to see the heat flowing.

Things to try:

- Pour **oil** on a lake, then drop **fire** on it.
- Plant **seeds** on dirt and rain on them (**R**) — they grow.
- Dig into the ground with RMB; you may hit oil or gunpowder pockets. Bring lava.
- **Acid** rain is exactly as bad an idea as it sounds.
- Stand well back and press **M**. The crater rim comes out molten, and craters
  in sand come out glass.
- Cycle to **snow** (R twice) and watch lakes freeze over — then skate across.
  Ice is exactly as slippery as you fear.
- Build a **cooler** above a lake: steam from your lava experiments condenses
  on it and rains back down. Congratulations, you made weather.
- Drop a **heater** in a snowfield and watch it carve a melt bubble.
- By default even placed **stone** falls as loose rubble (and stays loose —
  undermine a pile and it collapses again). Natural terrain never moves.
- Toggle **F** and build with anything: floating sand bridges, suspended water
  ceilings, walls of frozen lava. A pinned **fire** pixel never burns out —
  instant torch for your caves. Neighbors still react to pinned cells
  (and any reaction un-freezes them), so a static oil wall is still a bad
  place to lean a torch.

Lava, fire, acid and explosions hurt. Five hearts, then the world takes you
with it — death regenerates the world.

## How it works (the fun bits)

- The world is a 320x180 grid of bytes; a cellular automaton updates it
  bottom-up at 60 Hz with alternating scan direction to avoid drift bias.
- Movement is density-based swapping, so heavy things sink through light
  things for free (sand through water, water through oil, bubbles up through lakes).
- Lighting is a per-pixel emitter field (fire/lava/embers/your wand) blurred
  with two separable box passes, mixed with a time-of-day ambient.
- A demo screenshot mode exists for headless testing:
  `java Pixelmancer --shot out.png [timeOfDay 0..1]` (0 = noon, 0.5 = midnight).

## Packaging a no-Java-needed Windows build

Build a self-contained `Pixelmancer.exe` (bundled runtime, ~75 MB unpacked / ~25 MB zipped)
using a **Windows** JDK's jpackage:

```
jar --create --file pixelmancer.jar --main-class Pixelmancer Pixelmancer*.class
jpackage --type app-image --input <dir with jar> --main-jar pixelmancer.jar \
         --main-class Pixelmancer --name Pixelmancer --icon pixelmancer.ico \
         --add-modules java.desktop --dest <out>
```

Zip the resulting `Pixelmancer/` folder; the recipient just runs `Pixelmancer.exe` inside it.
