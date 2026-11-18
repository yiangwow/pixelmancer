import java.awt.*;
import java.awt.event.*;
import java.awt.image.*;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.swing.JFrame;

/**
 * PIXELMANCER — a falling-sand sorcery sandbox in one Java file.
 *
 * Every pixel of the world is simulated: sand falls, water flows, oil floats,
 * lava petrifies, fire spreads, acid corrodes, gunpowder chain-explodes,
 * plants drink and grow. You are a very small wizard with a very big wand.
 *
 *   compile:  javac Pixelmancer.java
 *   play:     java Pixelmancer
 *
 * Controls:
 *   A/D or arrows  move        W/space  jump (swim up in water)
 *   mouse + LMB    cast selected element at cursor
 *   RMB            erase / dig
 *   1-9 0 - =      pick element     E / wheel  cycle elements
 *   [ ]            brush size       M  call down a meteor
 *   R              toggle rain      N  new world
 */
public class Pixelmancer extends Canvas implements Runnable {

    static final int W = 320, H = 180, SCALE = 4;
    static final int PW = 3, PH = 6; // player size in pixels

    // ---------------- materials ----------------
    static final byte EMPTY = 0, STONE = 1, DIRT = 2, GRASS = 3, SAND = 4,
            WATER = 5, OIL = 6, ACID = 7, LAVA = 8, FIRE = 9, SMOKE = 10,
            STEAM = 11, PLANT = 12, WOOD = 13, SEED = 14, BOOM = 15, EMBER = 16,
            ICE = 17, SNOW = 18, GLASS = 19, HEATER = 20, COOLER = 21,
            HOUSE = 22, BRICK = 23;   // buildings: ghost-solid (cells collide, people pass)
    static final byte FOLKB = 127;    // palette sentinel: brush spawns people, not pixels

    static final byte[]   PALETTE = {SAND, WATER, STONE, DIRT, WOOD, PLANT, SEED, OIL, LAVA, FIRE, ACID, BOOM,
                                     ICE, SNOW, HEATER, COOLER, FOLKB};
    static final String[] PNAME   = {"sand", "water", "stone", "dirt", "wood", "plant", "seed", "oil", "lava", "fire", "acid", "boom",
                                     "ice", "snow", "heater", "cooler", "folk"};
    static final int[]    PCOL    = {0xE0CA7A, 0x3D6FD8, 0x8F9099, 0x8A5A34, 0x6B462A, 0x3FA34D,
                                     0xA8D040, 0x4A3B2A, 0xFF6A00, 0xFFB030, 0x8CFF3C, 0x44444C,
                                     0xA8D4EE, 0xF2F5FC, 0xE04818, 0x66B8E8, 0xF0A8A0};

    // ---------------- world state ----------------
    final byte[] m     = new byte[W * H]; // material per pixel
    final byte[] life  = new byte[W * H]; // fuel / growth budget / gas lifetime
    final byte[] stamp = new byte[W * H]; // frame parity, prevents double updates
    final byte[] bg    = new byte[W * H]; // 1 = was underground at gen time (cave backdrop)
    final byte[] pin   = new byte[W * H]; // 0 = natural, 1 = placed static (frozen), 2 = placed loose (solids fall)
    final short[] temp = new short[W * H]; // degrees C; drives all state changes
    byte par = 0;

    final BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
    final int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
    final float[] light = new float[W * H];
    final float[] ltmp  = new float[W * H];

    final Random rnd = new Random();
    long frame = 0;
    double dayT = 0.08;             // 0 = noon, 0.5 = midnight
    double daylight = 1, amb = 1;
    int weather = 0;                // 0 clear, 1 rain, 2 snow
    double airAmb = 15;             // atmosphere target temperature
    boolean thermal = false;        // T: heatmap debug view
    int flash = 0, boltTimer = 0;
    final ArrayList<Point> bolt = new ArrayList<>();
    double shake = 0;
    final ArrayList<double[]> meteors = new ArrayList<>(); // {x,y,vx,vy}
    final double[][] clouds = new double[4][4];            // {x,y,rx,ry}

    // ---------------- civilization ----------------
    final ArrayList<Folk> folks = new ArrayList<>();
    final ArrayList<Village> villages = new ArrayList<>();
    final ArrayList<Kingdom> kingdoms = new ArrayList<>();
    int deaths = 0;
    int speed = 1;                  // , and . — god-sims need fast-forward
    boolean paused = false;         // P
    static final int[] KCOLORS = {0xE04848, 0x4878E0, 0x50C050, 0xE0C040, 0xB050D0,
                                  0x40C8C8, 0xE08030, 0x9090F8, 0xD05088, 0x88B830};
    static final String[] SYL = {"ash", "bel", "cor", "dun", "eld", "fen", "gor", "hal", "ith",
                                 "kel", "lum", "mor", "nim", "oak", "pyr", "rav", "sol", "tor", "vael", "wyn"};
    static final String[] SUF = {"heim", "dor", "mark", "fell", "wick", "gard", "holm", "stead"};
    static final String[] ROMAN = {"I", "II", "III", "IV"};

    class Folk {
        double x, y, vx, vy;
        int age, hp = 20, lifespan, drown, wait, acd, dir = 1, tx;
        Kingdom k; Village home;
        boolean warrior, dead;
        Folk(double x, double y, int age) {
            this.x = x; this.y = y; this.age = age;
            lifespan = 9000 + rnd.nextInt(4800);
            tx = (int) x;
        }
        boolean adult() { return age > 2400; }
        boolean elder() { return age > lifespan - 2400; }
    }

    class Village {
        int x; Kingdom k;
        final ArrayList<int[]> houses = new ArrayList<>(); // {x, floorY}
        int buildSide = 0;
    }

    class Kingdom {
        int color; String name;
        double tech; int era, unrest, warCd = 5;
        final ArrayList<Village> vills = new ArrayList<>();
        final Set<Kingdom> wars = new HashSet<>();
        boolean alive = true;
    }

    // ---------------- player ----------------
    double plx, ply, pvx, pvy;
    boolean onGround, faceR = true;
    int hp = 5, iframes = 0, meteorCd = 0;

    // ---------------- input ----------------
    final boolean[] keys = new boolean[600];
    int mx = W * SCALE / 2, my = H * SCALE / 2;
    boolean lmb, rmb;
    int sel = 0, brush = 2;
    boolean pinMode = false; // F: place elements frozen in space instead of simulating

    Pixelmancer() {
        for (int k = 0; k < clouds.length; k++)
            clouds[k] = new double[]{rnd.nextInt(W), 10 + rnd.nextInt(30), 18 + rnd.nextInt(16), 4 + rnd.nextInt(4)};
    }

    // =============================================================
    // world generation
    // =============================================================
    void gen() {
        Arrays.fill(m, EMPTY);
        Arrays.fill(life, (byte) 0);
        Arrays.fill(stamp, (byte) 0);
        Arrays.fill(bg, (byte) 0);
        Arrays.fill(pin, (byte) 0);
        Arrays.fill(temp, (short) 15);
        meteors.clear();
        bolt.clear();
        boltTimer = 0;

        double p1 = rnd.nextDouble() * 9, p2 = rnd.nextDouble() * 9, p3 = rnd.nextDouble() * 9;
        int[] surf = new int[W];
        for (int x = 0; x < W; x++) {
            double hgt = H * 0.55 + 17 * Math.sin(x * 0.021 + p1)
                       + 8 * Math.sin(x * 0.052 + p2) + 4 * Math.sin(x * 0.113 + p3);
            surf[x] = clampi((int) hgt, 20, H - 8);
        }
        int WL = (int) (H * 0.63); // water line: valleys below it become lakes

        for (int x = 0; x < W; x++) {
            int g = surf[x];
            boolean beach = Math.abs(g - WL) < 5;
            for (int y = g; y < H; y++) {
                int d = y - g;
                byte t = d == 0 ? GRASS : d < 5 + (hash(x, 7) & 3) ? DIRT : STONE;
                if (beach && d < 3) t = SAND;
                if (g > WL && d < 3) t = SAND;   // lake beds are sandy
                m[y * W + x] = t;
            }
            if (g > WL) for (int y = WL; y < g; y++) m[y * W + x] = WATER;
            for (int y = g; y < H; y++) bg[y * W + x] = 1;
        }

        // caves: drunken walkers carving tunnels
        for (int c = 0; c < 5; c++) {
            double cx = rnd.nextInt(W), cy = surf[(int) cx] + 10 + rnd.nextInt(30), ang = rnd.nextDouble() * Math.PI * 2;
            for (int s = 0; s < 140; s++) {
                ang += (rnd.nextDouble() - 0.5) * 0.8;
                cx += Math.cos(ang); cy += Math.sin(ang) * 0.6;
                carve((int) cx, (int) cy, 1 + (s % 13 == 0 ? 1 : 0));
                if (cx < 2 || cx > W - 3 || cy < 10 || cy > H - 4) break;
            }
        }

        // buried pockets: oil, water, gunpowder veins, and deep magma
        for (int k = 0; k < 14; k++) {
            int x = rnd.nextInt(W), y0 = surf[x] + 8 + rnd.nextInt(Math.max(1, H - surf[x] - 14));
            byte fill = k < 5 ? OIL : k < 9 ? WATER : k < 12 ? BOOM : LAVA;
            if (fill == LAVA) y0 = Math.min(H - 6, y0 + 15); // magma runs deep
            int r = fill == BOOM ? 2 : 2 + rnd.nextInt(3);
            for (int dy = -r; dy <= r; dy++) for (int dx = -r; dx <= r; dx++) {
                int xx = x + dx, yy = y0 + dy;
                if (dx * dx + dy * dy <= r * r && in(xx, yy)) {
                    byte t = m[yy * W + xx];
                    if (t == STONE || t == DIRT) {
                        m[yy * W + xx] = fill;
                        if (fill == LAVA) temp[yy * W + xx] = 1000;
                    }
                }
            }
        }

        // trees on dry grass
        for (int x = 6; x < W - 6; x += 10 + rnd.nextInt(18)) {
            int g = surf[x];
            if (g >= WL - 1 || m[g * W + x] != GRASS) continue;
            int th = 5 + rnd.nextInt(5);
            for (int k = 1; k <= th; k++) if (g - k >= 0) m[(g - k) * W + x] = WOOD;
            int ty = g - th, cr = 2 + rnd.nextInt(2);
            for (int dy = -cr - 1; dy <= cr; dy++) for (int dx = -cr; dx <= cr; dx++) {
                int xx = x + dx, yy = ty + dy;
                if (dx * dx + dy * dy <= cr * cr + 1 && in(xx, yy)
                        && m[yy * W + xx] == EMPTY && rnd.nextInt(10) < 8)
                    m[yy * W + xx] = PLANT;
            }
        }

        // bedrock floor so nothing leaks out of the world
        for (int x = 0; x < W; x++) { m[(H - 1) * W + x] = STONE; m[(H - 2) * W + x] = STONE; }

        // spawn the wizard on dry land near the middle
        int sx = W / 2;
        for (int off = 0; off < W / 2; off++) {
            int cand = W / 2 + (off % 2 == 0 ? off / 2 : -off / 2);
            if (cand > 4 && cand < W - 4 && surf[cand] < WL - 2) { sx = cand; break; }
        }
        plx = sx - 1; ply = groundY(sx) - PH - 2; pvx = pvy = 0; hp = 5; iframes = 60;

        // two founding tribes on opposite hills
        folks.clear(); villages.clear(); kingdoms.clear(); deaths = 0;
        for (int side = 0; side < 2; side++) {
            int want = side == 0 ? W / 4 : 3 * W / 4, tx = -1;
            for (int off = 0; off < W / 3; off++) {
                int cand = want + (off % 2 == 0 ? off / 2 : -off / 2);
                if (cand > 10 && cand < W - 10 && surf[cand] < WL - 2) { tx = cand; break; }
            }
            if (tx < 0) continue;
            Kingdom k = foundKingdom(tx, null);
            for (int n = 0; n < 4; n++)
                spawnFolk(tx + rnd.nextInt(9) - 4, groundY(tx) - 6, 2500 + rnd.nextInt(2000), k,
                        k.vills.isEmpty() ? null : k.vills.get(0));
        }
    }

    void carve(int cx, int cy, int r) {
        for (int dy = -r; dy <= r; dy++) for (int dx = -r; dx <= r; dx++)
            if (dx * dx + dy * dy <= r * r && in(cx + dx, cy + dy))
                m[(cy + dy) * W + cx + dx] = EMPTY;
    }

    int groundY(int x) {
        for (int y = 0; y < H; y++) {
            byte t = m[y * W + x];
            if (t != EMPTY && t != SMOKE && t != STEAM && t != FIRE) return y;
        }
        return H - 1;
    }

    // =============================================================
    // simulation tick (60 per second)
    // =============================================================
    void tick() {
        frame++;
        par = (byte) (frame & 1);
        if (flash > 0) flash--;
        if (boltTimer > 0) boltTimer--;
        shake *= 0.88;
        dayT = (dayT + 1.0 / (60 * 140)) % 1.0;
        daylight = Math.max(0, Math.cos(dayT * Math.PI * 2) * 0.55 + 0.5);
        amb = 0.30 + 0.70 * Math.min(1, daylight);

        for (double[] c : clouds) { c[0] += 0.06; if (c[0] - c[2] > W) c[0] = -c[2]; }

        // atmosphere: cold nights, brutal snowstorms
        airAmb = 2 + 26 * daylight;
        if (weather == 1) airAmb -= 4;
        if (weather == 2) airAmb = -20;

        // weather drifts on its own; R cycles it
        if (frame % 60 == 0 && rnd.nextInt(50) == 0)
            weather = weather != 0 ? 0 : (daylight < 0.2 || rnd.nextInt(3) == 0 ? 2 : 1);
        if (weather != 0) {
            for (int k = 0; k < 5; k++) {
                int x = rnd.nextInt(W);
                if (m[x] == EMPTY) set(x, weather == 2 ? SNOW : WATER);
            }
            if (weather == 1 && rnd.nextDouble() < 0.004) lightning();
        }

        applyBrush();

        // cellular automata: bottom-up, alternating scan direction per row
        for (int y = H - 1; y >= 0; y--) {
            boolean ltr = ((frame + y) & 1) == 0;
            int row = y * W;
            for (int k = 0; k < W; k++) {
                int x = ltr ? k : W - 1 - k;
                int i = row + x;
                byte t = m[i];
                if (t == EMPTY || stamp[i] == par) continue;
                if (pin[i] == 1) continue; // frozen cells sit this one out
                stamp[i] = par;
                switch (t) {
                    case SAND, BOOM, SNOW -> powder(x, y, i);
                    case SEED       -> seed(x, y, i);
                    case EMBER      -> ember(x, y, i);
                    case WATER      -> water(x, y, i);
                    case OIL        -> liquid(x, y, i, 3);
                    case ACID       -> acid(x, y, i);
                    case LAVA       -> lava(x, y, i);
                    case FIRE       -> fire(x, y, i);
                    case SMOKE, STEAM -> gas(x, y, i);
                    case PLANT      -> plant(x, y, i);
                    default -> { if (pin[i] == 2) powder(x, y, i); } // placed solids fall as loose rubble
                }
            }
        }

        tempTick();
        tickMeteors();
        tickPlayer();
        for (Folk f : folks) if (!f.dead) folkTick(f);
        folks.removeIf(f -> f.dead);
        if (frame % 60 == 0) villagesTick();
        if (frame % 120 == 0) kingdomsTick();
        if (meteorCd > 0) meteorCd--;
    }

    // =============================================================
    // heat: emitters, diffusion, and the state-change table
    // =============================================================
    void tempTick() {
        int A = (int) airAmb;
        for (int i = 0; i < W * H; i++) {
            byte t = m[i];

            // self-regulating cells
            switch (t) {
                case LAVA   -> temp[i] = (short) Math.min(1000, temp[i] + 20); // molten core keeps pools alive
                case FIRE   -> { if (temp[i] < 420) temp[i] = 420; }
                case EMBER  -> { if (temp[i] < 500) temp[i] = 500; }
                case HEATER -> temp[i] = 800;
                case COOLER -> temp[i] = -150;
                default -> {}
            }

            // the atmosphere itself drifts toward ambient
            if (t == EMPTY) {
                int a2 = bg[i] == 1 ? 12 : A;
                temp[i] += (short) ((a2 - temp[i]) / 8);
            }

            // diffusion: half the cells exchange heat with one random neighbour;
            // air is a poor conductor so flowing lava stays molten for a while
            if (((i + frame) & 1) == 0) {
                int n = rnd.nextInt(4);
                int x = i % W, y = i / W;
                int nx2 = x + NX[n], ny2 = y + NY[n];
                if (in(nx2, ny2)) {
                    int j = ny2 * W + nx2;
                    int d = (temp[j] - temp[i]) / ((t == EMPTY || m[j] == EMPTY) ? 4 : 2);
                    temp[i] += (short) d;
                    temp[j] -= (short) d;
                }
            }

            // state changes (direct writes keep the pin; gases/fire go through set/ignite)
            int T = temp[i];
            switch (t) {
                case WATER -> { if (T <= -2) m[i] = ICE; else if (T >= 100) set(i, STEAM); }
                case ICE   -> { if (T >= 3) m[i] = WATER; }
                case SNOW  -> { if (T >= 2) m[i] = WATER; }
                case STEAM -> { if (T <= 45 && rnd.nextInt(8) == 0) m[i] = WATER; }
                case LAVA  -> { // crusting needs real coolant contact — rock alone can't kill a magma pocket
                    if (T < 200) m[i] = STONE;
                    else if (T < 600) {
                        int x = i % W, y = i / W;
                        for (int n = 0; n < 4; n++) {
                            int nx2 = x + NX[n], ny2 = y + NY[n];
                            if (!in(nx2, ny2)) continue;
                            byte nb = m[ny2 * W + nx2];
                            if (nb == WATER || nb == ICE || nb == SNOW) { m[i] = STONE; break; }
                        }
                    }
                }
                // 1050 is deliberately ABOVE lava's 1000 self-heat cap: only explosions,
                // lightning and meteors melt rock — lava contact alone must never chain-melt the world
                case STONE, DIRT -> { if (T >= 1050) { m[i] = LAVA; temp[i] = 980; } }
                case SAND  -> { if (T >= 1000) m[i] = GLASS; }
                case OIL   -> { if (T >= 230) ignite(i, OIL); }
                case WOOD, HOUSE -> { if (T >= 320 && rnd.nextInt(3) == 0) ignite(i, t); }
                case PLANT, GRASS, SEED -> { if (T >= 280 && rnd.nextInt(3) == 0) ignite(i, t); }
                case BOOM  -> { if (T >= 200 && rnd.nextInt(2) == 0) explode(i % W, i / W, 9); }
                default -> {}
            }
        }
    }

    // ---------------- element behaviours ----------------

    boolean powder(int x, int y, int i) {
        if (y + 1 >= H) return false;
        if (trySwap(i, i + W)) return true;
        int d = rnd.nextBoolean() ? 1 : -1;
        if (x + d >= 0 && x + d < W && trySwap(i, i + W + d)) return true;
        if (x - d >= 0 && x - d < W && trySwap(i, i + W - d)) return true;
        return false;
    }

    void seed(int x, int y, int i) {
        if (powder(x, y, i)) return;
        if (y + 1 < H && isSolid(m[i + W]) && rnd.nextInt(3) == 0) set(i, PLANT);
    }

    void ember(int x, int y, int i) {
        int L = (life[i] & 0xff) - 1;
        if (L <= 0) { set(i, rnd.nextInt(3) == 0 ? SMOKE : EMPTY); return; }
        life[i] = (byte) L;
        for (int n = 0; n < 4; n++) {
            int nx2 = x + NX[n], ny2 = y + NY[n];
            if (!in(nx2, ny2)) continue;
            int j = ny2 * W + nx2;
            byte t = m[j];
            if (t == WATER) { set(i, SMOKE); return; }
            if (t == BOOM) { explode(nx2, ny2, 9); return; }
            double f = flam(t);
            if (f > 0 && rnd.nextDouble() < f * 0.5) ignite(j, t);
        }
        powder(x, y, i);
    }

    void water(int x, int y, int i) {
        // dousing fire stays a contact rule for crispness; lava is handled by heat now
        for (int n = 0; n < 4; n++) {
            int nx2 = x + NX[n], ny2 = y + NY[n];
            if (!in(nx2, ny2)) continue;
            int j = ny2 * W + nx2;
            if (m[j] == FIRE) set(j, STEAM);
        }
        liquid(x, y, i, 4);
    }

    void acid(int x, int y, int i) {
        if (rnd.nextDouble() < 0.12) {
            int n = rnd.nextInt(4);
            int nx2 = x + NX[n], ny2 = y + NY[n];
            if (in(nx2, ny2)) {
                int j = ny2 * W + nx2;
                if (corrodible(m[j])) {
                    set(j, rnd.nextInt(4) == 0 ? SMOKE : EMPTY);
                    int L = (life[i] & 0xff) - 1;
                    if (L <= 0) { set(i, SMOKE); return; }
                    life[i] = (byte) L;
                }
            }
        }
        liquid(x, y, i, 3);
    }

    void lava(int x, int y, int i) {
        for (int n = 0; n < 4; n++) {
            int nx2 = x + NX[n], ny2 = y + NY[n];
            if (!in(nx2, ny2)) continue;
            int j = ny2 * W + nx2;
            byte t = m[j];
            if (t == BOOM) { explode(nx2, ny2, 9); return; }
            double f = flam(t);
            if (f > 0 && rnd.nextDouble() < f * 0.6) ignite(j, t);
        }
        if (y > 0 && m[i - W] == EMPTY && rnd.nextDouble() < 0.012) set(i - W, FIRE);
        if (rnd.nextDouble() < 0.35) liquid(x, y, i, 2);
    }

    void fire(int x, int y, int i) {
        int L = (life[i] & 0xff) - 1;
        if (L <= 0) { set(i, rnd.nextInt(3) == 0 ? SMOKE : EMPTY); return; }
        life[i] = (byte) L;
        for (int n = 0; n < 4; n++) {
            int nx2 = x + NX[n], ny2 = y + NY[n];
            if (!in(nx2, ny2)) continue;
            int j = ny2 * W + nx2;
            byte t = m[j];
            if (t == BOOM) { explode(nx2, ny2, 9); return; }
            double f = flam(t);
            if (f > 0 && rnd.nextDouble() < f) ignite(j, t);
        }
        if (y > 0 && m[i - W] == EMPTY && rnd.nextDouble() < 0.35) {
            m[i - W] = FIRE; life[i - W] = (byte) L; temp[i - W] = temp[i]; stamp[i - W] = par;
            set(i, rnd.nextInt(6) == 0 ? SMOKE : EMPTY);
        }
    }

    void gas(int x, int y, int i) {
        int L = (life[i] & 0xff) - 1;
        if (L <= 0) {
            set(i, m[i] == STEAM && rnd.nextInt(4) == 0 ? WATER : EMPTY);
            return;
        }
        life[i] = (byte) L;
        if (y > 0) {
            int up = i - W;
            byte a = m[up];
            if (a == EMPTY && rnd.nextDouble() < 0.85) { moveCell(i, up); return; }
            if (isLiquid(a) && pin[up] != 1) { swapCells(i, up); return; } // bubbles!
            int d = rnd.nextBoolean() ? 1 : -1;
            if (x + d >= 0 && x + d < W && m[up + d] == EMPTY) { moveCell(i, up + d); return; }
        }
        int d = rnd.nextBoolean() ? 1 : -1;
        if (x + d >= 0 && x + d < W && m[i + d] == EMPTY && rnd.nextDouble() < 0.4) moveCell(i, i + d);
    }

    void plant(int x, int y, int i) {
        int L = life[i] & 0xff;
        for (int n = 0; n < 4; n++) {
            int nx2 = x + NX[n], ny2 = y + NY[n];
            if (!in(nx2, ny2)) continue;
            int j = ny2 * W + nx2;
            if (m[j] == WATER && rnd.nextDouble() < 0.05) { set(j, EMPTY); L = Math.min(40, L + 9); }
        }
        if (L > 0 && rnd.nextDouble() < 0.05) {
            int gx = x + (rnd.nextInt(5) == 0 ? (rnd.nextBoolean() ? 1 : -1) : 0);
            int gy = y - 1;
            if (in(gx, gy) && m[gy * W + gx] == EMPTY) {
                int j = gy * W + gx;
                set(j, PLANT); life[j] = (byte) (L - 1);
                L = 0;
            }
        }
        life[i] = (byte) L;
    }

    void liquid(int x, int y, int i, int disp) {
        if (y + 1 < H && trySwap(i, i + W)) return;
        int d = rnd.nextBoolean() ? 1 : -1;
        if (y + 1 < H) {
            if (x + d >= 0 && x + d < W && trySwap(i, i + W + d)) return;
            if (x - d >= 0 && x - d < W && trySwap(i, i + W - d)) return;
        }
        int dest = -1;
        for (int s = 1; s <= disp; s++) {
            int tx = x + d * s;
            if (tx < 0 || tx >= W) break;
            int j = y * W + tx;
            if (m[j] == EMPTY) dest = j; else break;
        }
        if (dest >= 0) moveCell(i, dest);
    }

    // ---------------- element helpers ----------------

    static final int[] NX = {1, -1, 0, 0}, NY = {0, 0, 1, -1};

    void ignite(int j, byte t) {
        m[j] = FIRE;
        pin[j] = 0;
        life[j] = t == WOOD ? (byte) (90 + rnd.nextInt(40)) : (byte) (20 + rnd.nextInt(20));
        stamp[j] = par;
    }

    double flam(byte t) {
        return switch (t) {
            case PLANT -> 0.22; case GRASS -> 0.12; case WOOD -> 0.05;
            case OIL -> 0.45; case SEED -> 0.3; case HOUSE -> 0.08; default -> 0;
        };
    }

    boolean corrodible(byte t) {
        return switch (t) {
            case STONE, DIRT, GRASS, SAND, WOOD, PLANT, SEED, BOOM, ICE, SNOW, HOUSE, BRICK -> true;
            default -> false; // glass is acid-proof: store your acid in style
        };
    }

    boolean isLiquid(byte t) { return t == WATER || t == OIL || t == ACID || t == LAVA; }
    boolean isGasT(byte t)   { return t == SMOKE || t == STEAM || t == FIRE; }
    boolean isSolid(byte t)  { return t != EMPTY && !isLiquid(t) && !isGasT(t); }

    int dens(byte t) {
        return switch (t) {
            case EMPTY -> 0; case SMOKE, STEAM, FIRE -> 1;
            case OIL -> 10; case SNOW -> 15; // snow floats on water
            case WATER, ACID -> 20; case LAVA -> 25;
            default -> 40; // powders and solids
        };
    }

    boolean displaceable(byte t) { return isLiquid(t) || isGasT(t); }

    boolean trySwap(int i, int j) {
        if (pin[j] == 1) return false;
        byte a = m[i], b = m[j];
        if (b == EMPTY || (displaceable(b) && dens(a) > dens(b))) {
            swapCells(i, j);
            return true;
        }
        return false;
    }

    void swapCells(int i, int j) {
        byte t = m[i]; m[i] = m[j]; m[j] = t;
        byte l = life[i]; life[i] = life[j]; life[j] = l;
        byte p = pin[i]; pin[i] = pin[j]; pin[j] = p;
        short h = temp[i]; temp[i] = temp[j]; temp[j] = h;
        stamp[i] = par; stamp[j] = par;
    }

    void moveCell(int i, int j) {
        m[j] = m[i]; life[j] = life[i]; pin[j] = pin[i]; temp[j] = temp[i]; stamp[j] = par;
        m[i] = EMPTY; life[i] = 0; pin[i] = 0; // vacated air keeps the matter's heat behind
    }

    void set(int i, byte t) {
        m[i] = t;
        pin[i] = 0; // any reaction or overwrite un-freezes the cell
        life[i] = switch (t) {
            case FIRE -> (byte) (25 + rnd.nextInt(20));
            case ACID -> (byte) (12 + rnd.nextInt(10));
            case PLANT -> (byte) 10;
            case EMBER -> (byte) (30 + rnd.nextInt(30));
            case SMOKE -> (byte) (50 + rnd.nextInt(40));
            case STEAM -> (byte) (80 + rnd.nextInt(40));
            default -> 0;
        };
        switch (t) { // creation temperature; other materials inherit the cell's heat
            case FIRE -> temp[i] = 500;
            case LAVA -> temp[i] = 1100;
            case EMBER -> temp[i] = 600;
            case STEAM -> temp[i] = 110;
            case ICE -> temp[i] = -25;
            case SNOW -> temp[i] = -8;
            case WATER -> temp[i] = 12;
            case HEATER -> temp[i] = 800;
            case COOLER -> temp[i] = -150;
            default -> {}
        }
        stamp[i] = par;
    }

    // =============================================================
    // explosions, meteors, lightning
    // =============================================================
    void explode(int cx, int cy, int r) {
        int r2 = r * r;
        for (int dy = -r; dy <= r; dy++) for (int dx = -r; dx <= r; dx++) {
            int d2 = dx * dx + dy * dy;
            if (d2 > r2) continue;
            int x = cx + dx, y = cy + dy;
            if (!in(x, y)) continue;
            int i = y * W + x;
            byte t = m[i];
            double edge = 1.0 - (double) d2 / r2;
            int heat = (int) (1100 * edge); // explosions vitrify sand and melt crater rims
            if (temp[i] < heat) temp[i] = (short) heat;
            if (t == BOOM) { set(i, FIRE); continue; } // chains via fire next frames
            if (d2 < r2 * 0.3) {
                set(i, rnd.nextInt(4) == 0 ? FIRE : EMPTY);
            } else if (rnd.nextDouble() < edge + 0.15) {
                if (t == STONE && rnd.nextBoolean()) continue; // stone shrugs sometimes
                int roll = rnd.nextInt(10);
                set(i, roll < 2 ? EMBER : roll < 5 ? FIRE : roll < 7 ? SMOKE : EMPTY);
            }
        }
        shake = Math.min(12, shake + r * 0.7);
        flash = Math.max(flash, 2);
        for (Folk f : folks) { // no witnesses inside the blast radius
            double fdx = f.x - cx, fdy = f.y - cy;
            if (fdx * fdx + fdy * fdy < r * r) killFolk(f, true);
        }
        double pdx = plx + PW / 2.0 - cx, pdy = ply + PH / 2.0 - cy;
        if (pdx * pdx + pdy * pdy < (r + 3) * (r + 3)) {
            hurt(2);
            pvx += pdx > 0 ? 1.6 : -1.6;
            pvy -= 2;
        }
    }

    void tickMeteors() {
        for (int k = meteors.size() - 1; k >= 0; k--) {
            double[] mt = meteors.get(k);
            mt[0] += mt[2]; mt[1] += mt[3]; mt[3] += 0.06;
            int x = (int) mt[0], y = (int) mt[1];
            if (x < -10 || x > W + 10 || y > H) { meteors.remove(k); continue; }
            if (in(x, y - 1) && m[(y - 1) * W + x] == EMPTY && rnd.nextBoolean())
                set((y - 1) * W + x, rnd.nextInt(3) == 0 ? SMOKE : FIRE);
            if (in(x, y)) {
                byte t = m[y * W + x];
                if (t != EMPTY && !isGasT(t)) { explode(x, y, 13); meteors.remove(k); }
            }
        }
    }

    void lightning() {
        int lx = 10 + rnd.nextInt(W - 20);
        bolt.clear();
        int x = lx, gy = groundY(lx);
        for (int y = 0; y < gy; y += 3 + rnd.nextInt(3)) {
            bolt.add(new Point(x, y));
            x = clampi(x + rnd.nextInt(7) - 3, 2, W - 3);
        }
        bolt.add(new Point(lx, gy));
        for (Point p : bolt) // the bolt path is furnace-hot: strikes on sand fuse glass
            for (int d = -1; d <= 1; d++)
                if (in(p.x + d, p.y)) temp[p.y * W + p.x + d] = 1500;
        boltTimer = 5;
        flash = 5;
        explode(lx, gy, 5);
    }

    // =============================================================
    // the wizard
    // =============================================================
    void tickPlayer() {
        if (iframes > 0) iframes--;
        boolean left  = keys[KeyEvent.VK_A] || keys[KeyEvent.VK_LEFT];
        boolean right = keys[KeyEvent.VK_D] || keys[KeyEvent.VK_RIGHT];
        boolean jump  = keys[KeyEvent.VK_SPACE] || keys[KeyEvent.VK_W] || keys[KeyEvent.VK_UP];

        double target = (right ? 1.1 : 0) - (left ? 1.1 : 0);
        int bx = (int) (plx + 1), by = (int) (ply + PH);
        boolean onIce = in(bx, by) && m[by * W + bx] == ICE;
        pvx += (target - pvx) * (onIce ? 0.045 : 0.3); // ice: good luck stopping
        if (right) faceR = true;
        if (left) faceR = false;

        boolean inLiq = liquidAtPlayer();
        pvy += inLiq ? 0.07 : 0.22;
        double cap = inLiq ? 0.7 : 3.6;
        if (pvy > cap) pvy = cap;
        if (jump) {
            if (onGround) { pvy = -3.3; onGround = false; }
            else if (inLiq) pvy = Math.max(pvy - 0.25, -1.0);
        }

        // horizontal, with 2px auto step-up so terrain is walkable
        double nx = plx + pvx;
        if (!collide(nx, ply)) plx = nx;
        else if (!collide(nx, ply - 1)) { plx = nx; ply -= 1; }
        else if (!collide(nx, ply - 2)) { plx = nx; ply -= 2; }
        else pvx = 0;
        plx = Math.max(0, Math.min(W - PW, plx));

        // vertical
        double ny = ply + pvy;
        onGround = false;
        if (!collide(plx, ny)) ply = ny;
        else {
            double s = Math.signum(pvy) * 0.25;
            for (int k = 0; k < 14 && s != 0 && !collide(plx, ply + s); k++) ply += s;
            if (pvy > 0) onGround = true;
            pvy = 0;
        }
        if (ply < -4) { ply = -4; pvy = 0; }

        // hazards
        int dmg = 0;
        for (int cy = (int) ply; cy < ply + PH; cy++) for (int cx = (int) plx; cx < plx + PW; cx++) {
            if (!in(cx, cy)) continue;
            byte t = m[cy * W + cx];
            if (t == LAVA) dmg = Math.max(dmg, 2);
            else if (t == FIRE || t == ACID || t == EMBER) dmg = Math.max(dmg, 1);
        }
        if (dmg > 0) hurt(dmg);
    }

    boolean collide(double x, double y) { return rectHits(x, y, PW, PH); }

    boolean rectHits(double x, double y, int w, int h) {
        int x0 = (int) Math.floor(x), x1 = (int) Math.floor(x + w - 0.01);
        int y0 = (int) Math.floor(y), y1 = (int) Math.floor(y + h - 0.01);
        for (int cy = y0; cy <= y1; cy++) for (int cx = x0; cx <= x1; cx++) {
            if (cx < 0 || cx >= W || cy >= H) return true;
            if (cy < 0) continue;
            byte t = m[cy * W + cx];
            // snow is soft; HOUSE/BRICK are ghost-solid so people can live in them
            if (t == STONE || t == DIRT || t == GRASS || t == SAND || t == WOOD || t == BOOM
                    || t == ICE || t == GLASS || t == HEATER || t == COOLER) return true;
        }
        return false;
    }

    boolean liquidAtPlayer() {
        int cx = (int) (plx + PW / 2.0), cy = (int) (ply + PH / 2.0);
        return in(cx, cy) && isLiquid(m[cy * W + cx]);
    }

    void hurt(int n) {
        if (iframes > 0) return;
        hp -= n;
        iframes = 50;
        shake = Math.min(10, shake + 2.5);
        if (hp <= 0) die();
    }

    void die() {
        int cx = (int) plx, cy = (int) ply;
        for (int k = 0; k < 40; k++) { // undignified puff of smoke
            int x = cx + rnd.nextInt(13) - 6, y = cy + rnd.nextInt(13) - 6;
            if (in(x, y) && m[y * W + x] == EMPTY) set(y * W + x, SMOKE);
        }
        gen(); // fresh world, fresh wizard — death has consequences
    }

    // =============================================================
    // civilization: folk live, build, age, conquer, rebel
    // =============================================================

    String genName() {
        String s = SYL[rnd.nextInt(SYL.length)] + SUF[rnd.nextInt(SUF.length)];
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    Folk spawnFolk(double x, double y, int age, Kingdom k, Village home) {
        Folk f = new Folk(x, y, age);
        f.k = k; f.home = home;
        folks.add(f);
        return f;
    }

    Kingdom foundKingdom(int x, ArrayList<Folk> founders) {
        Kingdom k = new Kingdom();
        k.color = KCOLORS[kingdoms.size() % KCOLORS.length];
        k.name = genName();
        kingdoms.add(k);
        Village v = new Village();
        v.x = clampi(x, 10, W - 10); v.k = k;
        k.vills.add(v); villages.add(v);
        buildHouse(v);
        if (founders != null) for (Folk f : founders) { f.k = k; f.home = v; f.warrior = false; }
        return k;
    }

    void buildHouse(Village v) {
        int n = v.houses.size();
        int hx = clampi(v.x + (n % 2 == 0 ? 2 + (n / 2) * 8 : -(7 + (n / 2) * 8)), 3, W - 9);
        int gy = groundY(hx + 2);
        if (gy < 30 || gy > H - 6) return;
        for (int dx = 0; dx < 5; dx++) { // level a foundation pad
            for (int y = gy; y < Math.min(H - 2, gy + 3); y++) {
                int i = y * W + hx + dx;
                if (m[i] == EMPTY || isLiquid(m[i]) || isGasT(m[i])) { m[i] = DIRT; pin[i] = 0; }
            }
            for (int y = gy - 5; y < gy; y++) { // clear headroom
                int i = y * W + hx + dx;
                if (y >= 0 && m[i] != EMPTY && m[i] != HOUSE && m[i] != BRICK) { m[i] = EMPTY; pin[i] = 0; }
            }
        }
        byte matl = v.k != null && v.k.era >= 2 ? BRICK : HOUSE;
        int by = gy - 1; // floor row
        for (int dx = 0; dx < 5; dx++) setCell(hx + dx, by - 3, matl);
        for (int dy = 0; dy < 3; dy++) { setCell(hx, by - dy, matl); setCell(hx + 4, by - dy, matl); }
        for (int dy = 0; dy < 3; dy++) for (int dx = 1; dx < 4; dx++) // cozy dark interiors
            if (in(hx + dx, by - dy)) bg[(by - dy) * W + hx + dx] = 1;
        v.houses.add(new int[]{hx, by});
    }

    void setCell(int x, int y, byte t) { if (in(x, y)) set(y * W + x, t); }

    /** How many of a house's 11 structural pixels still stand. */
    int housePixels(int[] h) {
        int c = 0;
        for (int dx = 0; dx < 5; dx++) c += isHouseMat(h[0] + dx, h[1] - 3) ? 1 : 0;
        for (int dy = 0; dy < 3; dy++) {
            c += isHouseMat(h[0], h[1] - dy) ? 1 : 0;
            c += isHouseMat(h[0] + 4, h[1] - dy) ? 1 : 0;
        }
        return c;
    }

    boolean isHouseMat(int x, int y) {
        if (!in(x, y)) return false;
        byte t = m[y * W + x];
        return t == HOUSE || t == BRICK;
    }

    void killFolk(Folk f, boolean burned) {
        if (f.dead) return;
        f.dead = true;
        deaths++;
        if (f.k != null) f.k.unrest += 3;
        int cx = (int) f.x, cy = (int) f.y;
        for (int k2 = 0; k2 < 5; k2++) {
            int x = cx + rnd.nextInt(5) - 2, y = cy + rnd.nextInt(5) - 2;
            if (in(x, y) && m[y * W + x] == EMPTY) set(y * W + x, burned ? EMBER : SMOKE);
        }
    }

    void folkTick(Folk f) {
        f.age++;
        if (f.age > f.lifespan) { killFolk(f, false); return; }
        int cx = (int) (f.x + 1), cy = (int) (f.y + 1);
        if (!in(cx, cy)) { killFolk(f, false); return; }
        byte at = m[cy * W + cx];
        if (at == LAVA || at == FIRE || at == ACID || at == EMBER) { killFolk(f, true); return; }
        boolean inLiq = isLiquid(at);
        if (at == WATER) { if (++f.drown > 420) { killFolk(f, false); return; } } else f.drown = 0;

        // flee nearby heat
        boolean fleeing = false;
        for (int d = -4; d <= 4 && !fleeing; d++) {
            int x = cx + d;
            if (!in(x, cy)) continue;
            byte t = m[cy * W + x];
            if (t == FIRE || t == LAVA) { f.tx = clampi(cx - Integer.signum(d) * 20, 2, W - 4); fleeing = true; }
        }

        if (!fleeing) {
            if (f.warrior && f.k != null && !f.k.wars.isEmpty()) warriorBrain(f, cx, cy);
            else { // wander around home
                if (f.wait > 0) f.wait--;
                else if (Math.abs(f.x - f.tx) < 2) {
                    f.wait = 60 + rnd.nextInt(240);
                    int cxr = f.home != null ? f.home.x : (int) f.x;
                    f.tx = clampi(cxr + rnd.nextInt(57) - 28, 2, W - 4);
                }
            }
        }

        // walk physics: 2x3 box, step-up, hop at walls
        double sp = f.adult() ? 0.45 : 0.3;
        double target = f.wait > 0 && !f.warrior && !fleeing ? 0 : Integer.signum(f.tx - (int) f.x) * sp;
        f.vx += (target - f.vx) * 0.3;
        if (target > 0.01) f.dir = 1; else if (target < -0.01) f.dir = -1;
        f.vy += inLiq ? 0.05 : 0.2;
        if (f.vy > (inLiq ? 0.5 : 3.2)) f.vy = inLiq ? 0.5 : 3.2;
        if (inLiq && rnd.nextInt(3) == 0) f.vy -= 0.2; // doggy paddle

        double nx = f.x + f.vx;
        if (!rectHits(nx, f.y, 2, 3)) f.x = nx;
        else if (!rectHits(nx, f.y - 1, 2, 3)) { f.x = nx; f.y -= 1; }
        else if (!rectHits(nx, f.y - 2, 2, 3)) { f.x = nx; f.y -= 2; }
        else { f.vx = 0; if (!inLiq && rnd.nextInt(20) == 0) f.vy = -2.2; }
        f.x = Math.max(1, Math.min(W - 3, f.x));
        double ny = f.y + f.vy;
        if (!rectHits(f.x, ny, 2, 3)) f.y = ny;
        else {
            double s = Math.signum(f.vy) * 0.25;
            for (int k2 = 0; k2 < 12 && s != 0 && !rectHits(f.x, f.y + s, 2, 3); k2++) f.y += s;
            f.vy = 0;
        }
    }

    void warriorBrain(Folk f, int cx, int cy) {
        // march on the nearest enemy village; fight what you meet; burn what you reach
        Village best = null; int bd = Integer.MAX_VALUE;
        for (Kingdom e : f.k.wars) for (Village v : e.vills) {
            int d = Math.abs(v.x - cx);
            if (d < bd) { bd = d; best = v; }
        }
        if (best != null) f.tx = best.x;
        if (f.acd > 0) f.acd--;
        for (Folk e : folks) {
            if (e.dead || e.k == null || e.k == f.k || !f.k.wars.contains(e.k)) continue;
            double d = Math.abs(e.x - f.x) + Math.abs(e.y - f.y);
            if (d < 3.5 && f.acd == 0) {
                e.hp -= 3 + 2 * f.k.era;
                f.acd = 30;
                if (e.hp <= 0) { killFolk(e, false); if (e.k != null) e.k.unrest += 2; }
                break;
            }
        }
        if (best != null && bd < 12 && rnd.nextInt(90) == 0 && !best.houses.isEmpty()) { // arson
            int[] h = best.houses.get(rnd.nextInt(best.houses.size()));
            int x = h[0] + rnd.nextInt(5), y = h[1] - 3;
            if (in(x, y) && isHouseMat(x, y)) set(y * W + x, FIRE);
        }
    }

    void villagesTick() { // once per second
        for (int vi = villages.size() - 1; vi >= 0; vi--) {
            Village v = villages.get(vi);
            // collapse burned houses
            v.houses.removeIf(h -> housePixels(h) < 6);
            if (v.houses.isEmpty()) { // razed
                villages.remove(vi);
                if (v.k != null) { v.k.vills.remove(v); v.k.unrest += 25; }
                for (Folk f : folks) if (f.home == v)
                    f.home = v.k != null && !v.k.vills.isEmpty() ? v.k.vills.get(0) : null;
                continue;
            }
            int pop = 0, adults = 0;
            for (Folk f : folks) if (!f.dead && f.home == v) { pop++; if (f.adult()) adults++; }
            int cap = v.houses.size() * 4;
            double fertility = v.k != null && v.k.era >= 1 ? 0.5 : 0.3;
            if (pop < cap && adults >= 2 && folks.size() < 400 && rnd.nextDouble() < fertility) {
                int[] h = v.houses.get(rnd.nextInt(v.houses.size()));
                spawnFolk(h[0] + 2, h[1] - 2, 0, v.k, v);
            }
            if (pop >= cap - 1 && v.houses.size() < 7 && rnd.nextDouble() < 0.5) buildHouse(v);
            if (v.k != null && v.k.era >= 1 && rnd.nextDouble() < 0.3) { // sow the fields
                int fx = v.x - 14 + rnd.nextInt(6);
                if (fx > 2 && fx < W - 2) {
                    int gy = groundY(fx);
                    byte g = in(fx, gy) ? m[gy * W + fx] : EMPTY;
                    if ((g == GRASS || g == DIRT) && gy > 1 && m[(gy - 1) * W + fx] == EMPTY)
                        set((gy - 1) * W + fx, SEED);
                }
            }
        }
        // three strangers on a hill become a nation
        ArrayList<Folk> loose = new ArrayList<>();
        for (Folk f : folks) if (!f.dead && f.k == null && f.adult()) loose.add(f);
        for (Folk a : loose) {
            ArrayList<Folk> band = new ArrayList<>();
            for (Folk b : loose) if (b.k == null && Math.abs(b.x - a.x) < 18) band.add(b);
            if (band.size() >= 3) { foundKingdom((int) a.x, band); break; }
        }
    }

    void kingdomsTick() { // once per two seconds
        for (int ki = kingdoms.size() - 1; ki >= 0; ki--) {
            Kingdom k = kingdoms.get(ki);
            int pop = 0;
            for (Folk f : folks) if (!f.dead && f.k == k) pop++;
            if (k.vills.isEmpty() && pop == 0) { // gone from the map
                k.alive = false;
                for (Kingdom o : kingdoms) o.wars.remove(k);
                kingdoms.remove(ki);
                continue;
            }
            k.tech += pop * 0.7 * (1 + k.era * 0.3);
            k.era = k.tech > 3000 ? 3 : k.tech > 1200 ? 2 : k.tech > 300 ? 1 : 0;
            if (k.unrest > 0) k.unrest--;
            if (k.warCd > 0) k.warCd--;

            if (k.wars.isEmpty() && k.warCd == 0 && kingdoms.size() > 1
                    && rnd.nextDouble() < 0.08 + k.unrest * 0.002) {
                Kingdom t = null; int bd = Integer.MAX_VALUE;
                for (Kingdom o : kingdoms) {
                    if (o == k || o.vills.isEmpty() || k.vills.isEmpty()) continue;
                    int d = Math.abs(o.vills.get(0).x - k.vills.get(0).x);
                    if (d < bd) { bd = d; t = o; }
                }
                if (t != null) declareWar(k, t);
            } else if (!k.wars.isEmpty() && rnd.nextDouble() < 0.06) { // war-weariness
                Kingdom o = k.wars.iterator().next();
                k.wars.remove(o); o.wars.remove(k);
                demobilize(k); demobilize(o);
            }

            if (k.vills.size() >= 2 && k.unrest > 60) { // the provinces have had enough
                Village sec = k.vills.get(k.vills.size() - 1);
                k.vills.remove(sec);
                Kingdom nk = new Kingdom();
                nk.color = KCOLORS[kingdoms.size() % KCOLORS.length];
                nk.name = genName();
                nk.tech = k.tech * 0.6; nk.era = Math.max(0, k.era - 1);
                kingdoms.add(nk);
                nk.vills.add(sec); sec.k = nk;
                for (Folk f : folks) if (!f.dead && f.home == sec) f.k = nk;
                k.unrest = 15;
                declareWar(nk, k);
            }
        }
    }

    void declareWar(Kingdom a, Kingdom b) {
        a.wars.add(b); b.wars.add(a);
        a.warCd = 12; b.warCd = 12;
        mobilize(a); mobilize(b);
    }

    void mobilize(Kingdom k) {
        for (Folk f : folks) if (!f.dead && f.k == k && f.adult() && !f.elder() && rnd.nextBoolean()) f.warrior = true;
    }

    void demobilize(Kingdom k) {
        if (k.wars.isEmpty()) for (Folk f : folks) if (f.k == k) f.warrior = false;
    }

    // =============================================================
    // casting
    // =============================================================
    void applyBrush() {
        if (!lmb && !rmb) return;
        int wx = mx / SCALE, wy = my / SCALE;
        byte mat = rmb ? EMPTY : PALETTE[sel];
        int r = brush;
        if (rmb) { // the smiting finger of god
            for (Folk f : folks)
                if (!f.dead && (f.x - wx) * (f.x - wx) + (f.y - wy) * (f.y - wy) <= (r + 2) * (r + 2))
                    killFolk(f, true);
        }
        if (mat == FOLKB) { // people brush: drop wanderers; strangers found tribes
            if (lmb && frame % 5 == 0 && folks.size() < 400 && in(wx, wy))
                spawnFolk(wx, wy, 2500 + rnd.nextInt(2000), null, null);
            return;
        }
        for (int dy = -r; dy <= r; dy++) for (int dx = -r; dx <= r; dx++) {
            if (dx * dx + dy * dy > r * r) continue;
            int x = wx + dx, y = wy + dy;
            if (!in(x, y)) continue;
            int i = y * W + x;
            if (rmb) { if (m[i] != EMPTY) set(i, EMPTY); continue; }
            if (m[i] != EMPTY) continue;
            if (overlapsPlayer(x, y)) continue;
            if (!pinMode && mat != STONE && mat != WOOD && rnd.nextDouble() < 0.35) continue; // spray feel
            set(i, mat);
            if (mat == HEATER || mat == COOLER) pin[i] = 1; // machines never fall
            else if (pinMode) pin[i] = 1;
            else if (mat == STONE || mat == DIRT || mat == WOOD || mat == ICE) pin[i] = 2;
        }
    }

    boolean overlapsPlayer(int x, int y) {
        return x >= plx - 1 && x <= plx + PW && y >= ply - 1 && y <= ply + PH;
    }

    // =============================================================
    // rendering
    // =============================================================
    void renderFrame() {
        if (thermal) { // T: heatmap debug view — the tinkerer's x-ray
            for (int i = 0; i < W * H; i++) px[i] = heatColor(temp[i]);
            drawPlayer();
            return;
        }
        // --- 2D lighting: seed emitters, blur into a glow field ---
        Arrays.fill(light, 0f);
        for (int i = 0; i < W * H; i++) {
            byte t = m[i];
            if (t == FIRE) light[i] = 1f;
            else if (t == LAVA) light[i] = 0.85f;
            else if (t == EMBER) light[i] = 0.8f;
            else if (t == HEATER) light[i] = 0.55f;
        }
        for (double[] mt : meteors) {
            int x = (int) mt[0], y = (int) mt[1];
            if (in(x, y)) light[y * W + x] = 1f;
        }
        int pcx = (int) (plx + 1), pcy = (int) (ply + 2);
        if (in(pcx, pcy)) light[pcy * W + pcx] = Math.max(light[pcy * W + pcx], 0.45f); // wand glow
        blur(light, ltmp);
        blur(light, ltmp);

        // --- sky gradient rows for this time of day ---
        int topC = lerpC(0x070718, 0x5EA9E8, daylight);
        int botC = lerpC(0x1A1A3A, 0xBFE3F8, daylight);
        double sunX = W * ((dayT + 0.25) % 1.0), sunY = 20 + (1 - daylight) * 90;
        double moonX = W * ((dayT + 0.75) % 1.0), moonY = 20 + daylight * 90;

        for (int y = 0; y < H; y++) {
            int rowC = lerpC(topC, botC, y / (double) H);
            int row = y * W;
            for (int x = 0; x < W; x++) {
                int i = row + x;
                byte t = m[i];
                int c;
                boolean emissive = t == FIRE || t == LAVA || t == EMBER || t == HEATER;
                if (t == EMPTY || t == SMOKE || t == STEAM || t == GLASS) {
                    if (bg[i] == 1) {
                        c = adj(0x262032, ((hash(x, y) & 15) - 8)); // dark cave backdrop
                    } else {
                    c = rowC;
                    if (daylight < 0.4) { // stars
                        int h2 = hash(x, y);
                        if ((h2 & 1023) < 3) {
                            double tw = 0.5 + 0.5 * Math.sin(frame * 0.06 + (h2 & 63));
                            c = lerpC(c, 0xFFFFFF, (0.4 - daylight) * 2.2 * tw);
                        }
                    }
                    double ds = (x - sunX) * (x - sunX) + (y - sunY) * (y - sunY);
                    if (daylight > 0.05 && ds < 100) c = lerpC(c, 0xFFE9A0, ds < 28 ? 0.95 : 0.3 * daylight);
                    double dm = (x - moonX) * (x - moonX) + (y - moonY) * (y - moonY);
                    if (daylight < 0.5 && dm < 40) c = lerpC(c, 0xD8D8E8, dm < 12 ? 0.9 : 0.25);
                    for (double[] cl : clouds) {
                        double ex = (x - cl[0]) / cl[2], ey = (y - cl[1]) / cl[3];
                        double e = ex * ex + ey * ey;
                        if (e < 1) c = lerpC(c, weather != 0 ? 0x66666F : 0xFFFFFF, (1 - e) * 0.55 * Math.max(0.25, daylight));
                    }
                    }
                    if (t == GLASS) c = lerpC(c, matColor(t, x, y, i), 0.45); // see-through
                    else if (t != EMPTY) c = lerpC(c, matColor(t, x, y, i), 0.55); // translucent gasses
                } else {
                    c = matColor(t, x, y, i);
                }
                if (!emissive) {
                    boolean skyPix = (t == EMPTY || t == SMOKE || t == STEAM) && bg[i] == 0;
                    float br = skyPix ? 1f + light[i] * 1.2f // sky gradient already knows night
                            : (float) Math.min(1.25f, (float) (amb + light[i] * 1.6 * (1.05 - amb)));
                    c = scaleC(c, br);
                } else {
                    c = scaleC(c, 1f + light[i] * 0.25f);
                }
                px[i] = c;
            }
        }

        // meteor cores
        for (double[] mt : meteors) {
            int x = (int) mt[0], y = (int) mt[1];
            for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) {
                int d2 = dx * dx + dy * dy;
                if (d2 > 5 || !in(x + dx, y + dy)) continue;
                px[(y + dy) * W + x + dx] = d2 <= 1 ? 0xFFF3B0 : 0xFF7A28;
            }
        }

        // lightning bolt
        if (boltTimer > 0) for (int k = 0; k + 1 < bolt.size(); k++)
            linePx(bolt.get(k).x, bolt.get(k).y, bolt.get(k + 1).x, bolt.get(k + 1).y, 0xF8F8FF);

        drawFolks();
        drawPlayer();

        if (flash > 0) for (int i = 0; i < px.length; i++) px[i] = lerpC(px[i], 0xFFFFFF, 0.45);
    }

    void drawFolks() {
        for (Village v : villages) { // banners fly over every roof
            if (v.k == null) continue;
            for (int[] h : v.houses) {
                if (in(h[0] + 2, h[1] - 4)) px[(h[1] - 4) * W + h[0] + 2] = 0x5A4630;
                if (in(h[0] + 3, h[1] - 5)) px[(h[1] - 5) * W + h[0] + 3] = v.k.color;
                if (in(h[0] + 2, h[1] - 5)) px[(h[1] - 5) * W + h[0] + 2] = v.k.color;
            }
        }
        for (Folk f : folks) {
            if (f.dead) continue;
            int x = (int) f.x, y = (int) f.y;
            int body = f.k != null ? f.k.color : 0xB0A090;
            int head = f.elder() ? 0xC8C8C8 : 0xE8B088;
            if (!f.adult()) { // wee ones
                if (in(x, y + 1)) px[(y + 1) * W + x] = head;
                if (in(x, y + 2)) px[(y + 2) * W + x] = body;
                continue;
            }
            int hx = f.dir > 0 ? x : x + 1;
            if (in(hx, y)) px[y * W + hx] = head;
            if (in(x, y + 1)) px[(y + 1) * W + x] = body;
            if (in(x + 1, y + 1)) px[(y + 1) * W + x + 1] = body;
            int leg = ((int) (f.x * 2) & 1);
            if (in(x + leg, y + 2)) px[(y + 2) * W + x + leg] = 0x30283C;
            if (f.warrior) { // a glint of drawn iron
                int wx = f.dir > 0 ? x + 2 : x - 1;
                if (in(wx, y + 1)) px[(y + 1) * W + wx] = 0xD8DCE8;
            }
        }
    }

    static final String[] SPR = {".P.", "PPP", ".F.", "RRR", "RRR", "B.B"};

    void drawPlayer() {
        if (iframes > 0 && (frame / 4) % 2 == 0) return; // damage blink
        int sx = (int) plx, sy = (int) ply;
        for (int r = 0; r < PH; r++) for (int c = 0; c < PW; c++) {
            char ch = SPR[r].charAt(faceR ? c : PW - 1 - c);
            if (ch == '.') continue;
            int x = sx + c, y = sy + r;
            if (!in(x, y)) continue;
            px[y * W + x] = switch (ch) {
                case 'P' -> 0x7B3FD1; case 'F' -> 0xF0C29A;
                case 'R' -> 0x5B2E9E; default -> 0x2E1A4F;
            };
        }
        int wx = faceR ? sx + PW : sx - 1, wy = sy + 3;
        if (in(wx, wy)) px[wy * W + wx] = 0x8A5A2A;
        if (in(wx, wy - 1)) px[(wy - 1) * W + wx] = lmb ? 0xFFE080 : 0xC9A0FF;
    }

    int matColor(byte t, int x, int y, int i) {
        int v = ((hash(x, y) & 31) - 16);
        switch (t) {
            case STONE: return adj(0x8F9099, v);
            case DIRT:  return adj(0x8A5A34, v);
            case GRASS: return adj(0x58B04C, v);
            case SAND:  return adj(0xE0CA7A, v);
            case WOOD:  return adj(0x6B462A, v / 2);
            case PLANT: return adj(0x3FA34D, v);
            case SEED:  return adj(0xA8D040, v / 2);
            case BOOM:  return adj((hash(x, y) & 7) == 0 ? 0x6A6A74 : 0x3C3C44, v / 2);
            case OIL:   return adj(0x4A3B2A, v / 3);
            case ACID:  return adj(0x8CFF3C, v);
            case WATER: {
                int s = (int) (10 * Math.sin(x * 0.55 + y * 0.35 + frame * 0.12));
                return adj(0x2E62C8, v / 3 + s);
            }
            case LAVA: {
                int n = hash(x + (int) (frame / 5), y) & 255;
                int c = lerpC(0xE25822, 0xFFC825, n / 255.0);
                double hh = Math.max(0, Math.min(1, (temp[i] - 600) / 400.0));
                return scaleC(c, (float) (0.45 + 0.55 * hh)); // cooling lava darkens before it crusts
            }
            case ICE: {
                int sh = (int) (7 * Math.sin(x * 0.5 + y * 0.7));
                return adj(0xA8D4EE, v / 3 + sh);
            }
            case SNOW:  return adj(0xF2F5FC, v / 4);
            case HOUSE: return adj((y & 1) == 0 ? 0x9A6A38 : 0x8A5C30, v / 3); // plank rows
            case BRICK: return adj(((x + (y & 1) * 2) & 3) == 0 ? 0x8A7A72 : 0xA89890, v / 4);
            case GLASS: return adj(0xCFEAF2, v / 4);
            case HEATER: return lerpC(0xB03010, 0xFF7038, 0.5 + 0.5 * Math.sin(frame * 0.12));
            case COOLER: return adj((hash(x, y) & 7) == 0 ? 0xC8E8F8 : 0x5FB0E0, v / 3);
            case FIRE: {
                double lt = (life[i] & 0xff) / 45.0;
                return lerpC(0xC81E00, 0xFFE060, Math.min(1, lt + rnd.nextDouble() * 0.3));
            }
            case EMBER: return (frame + i) % 3 == 0 ? 0xFFD060 : 0xFF8020;
            case SMOKE: return adj(0x5A5A62, v / 2);
            case STEAM: return adj(0xC8D8DC, v / 3);
        }
        return 0xFF00FF;
    }

    static final int[] HEAT_T = {-150, 15, 100, 300, 600, 1000, 1400};
    static final int[] HEAT_C = {0x80C8FF, 0x181830, 0x502048, 0xA02020, 0xE06010, 0xFFD040, 0xFFFFFF};

    int heatColor(int T) {
        if (T <= HEAT_T[0]) return HEAT_C[0];
        for (int k = 1; k < HEAT_T.length; k++)
            if (T <= HEAT_T[k])
                return lerpC(HEAT_C[k - 1], HEAT_C[k], (T - HEAT_T[k - 1]) / (double) (HEAT_T[k] - HEAT_T[k - 1]));
        return HEAT_C[HEAT_C.length - 1];
    }

    void blur(float[] a, float[] tmp) {
        for (int y = 0; y < H; y++) { // horizontal
            int row = y * W;
            for (int x = 0; x < W; x++) {
                float s = 0; int n2 = 0;
                for (int d = -2; d <= 2; d++) {
                    int xx = x + d;
                    if (xx >= 0 && xx < W) { s += a[row + xx]; n2++; }
                }
                tmp[row + x] = s / n2;
            }
        }
        for (int x = 0; x < W; x++) { // vertical
            for (int y = 0; y < H; y++) {
                float s = 0; int n2 = 0;
                for (int d = -2; d <= 2; d++) {
                    int yy = y + d;
                    if (yy >= 0 && yy < H) { s += tmp[yy * W + x]; n2++; }
                }
                a[y * W + x] = s / n2;
            }
        }
    }

    void linePx(int x0, int y0, int x1, int y1, int c) {
        int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1, err = dx - dy;
        while (true) {
            if (in(x0, y0)) px[y0 * W + x0] = c;
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 > -dy) { err -= dy; x0 += sx; }
            if (e2 < dx) { err += dx; y0 += sy; }
        }
    }

    // =============================================================
    // HUD (drawn in screen space over the scaled frame)
    // =============================================================
    void hud(Graphics2D g, int w, int h) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // hearts
        for (int k = 0; k < 5; k++) drawHeart(g, 12 + k * 22, 12, k < hp);

        // element palette
        int cw = 38, total = PALETTE.length * cw;
        int x0 = (w - total) / 2, y0 = h - 44;
        g.setFont(new Font("Monospaced", Font.BOLD, 11));
        for (int k = 0; k < PALETTE.length; k++) {
            int x = x0 + k * cw;
            g.setColor(new Color(0, 0, 0, 130));
            g.fillRoundRect(x, y0, cw - 6, 30, 6, 6);
            g.setColor(new Color(PCOL[k]));
            g.fillRect(x + 5, y0 + 5, cw - 16, 20);
            if (k == sel) {
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(2));
                g.drawRoundRect(x, y0, cw - 6, 30, 6, 6);
            }
            if (k < 12) {
                g.setColor(new Color(255, 255, 255, 180));
                g.drawString(String.valueOf("1234567890-=".charAt(k)), x + 3, y0 + 12);
            }
        }
        g.setColor(pinMode ? new Color(140, 225, 255) : Color.WHITE);
        g.setFont(new Font("Monospaced", Font.BOLD, 14));
        byte selMat = PALETTE[sel];
        String name = PNAME[sel] + (pinMode ? " [static]"
                : (selMat == STONE || selMat == DIRT || selMat == WOOD) ? " [falling]" : "");
        g.drawString(name, w / 2 - g.getFontMetrics().stringWidth(name) / 2, y0 - 8);

        // hints
        g.setFont(new Font("Monospaced", Font.PLAIN, 11));
        g.setColor(new Color(255, 255, 255, 120));
        g.drawString("LMB cast · RMB dig/smite · E/wheel element · K folk · F static · [ ] brush · M meteor · R weather · T thermal · ,/. speed · P pause · N new", 12, h - 8);
        if (weather == 1) { g.setColor(new Color(160, 190, 255, 200)); g.drawString("~ raining ~", w - 100, 20); }
        if (weather == 2) { g.setColor(new Color(220, 235, 255, 220)); g.drawString("* snowing *", w - 100, 20); }
        if (thermal) { g.setColor(new Color(255, 160, 90, 220)); g.drawString("THERMAL", w - 100, 38); }

        // chronicle of nations
        int ky = 56;
        g.setFont(new Font("Monospaced", Font.BOLD, 12));
        for (Kingdom k : kingdoms) {
            int pop = 0;
            for (Folk f : folks) if (!f.dead && f.k == k) pop++;
            g.setColor(new Color(k.color));
            g.fillRect(w - 190, ky, 10, 10);
            g.setColor(new Color(255, 255, 255, 210));
            g.drawString(k.name + " " + ROMAN[k.era] + " · " + pop + (k.wars.isEmpty() ? "" : "  [at war]"),
                    w - 174, ky + 10);
            ky += 16;
        }
        g.setColor(new Color(255, 255, 255, 130));
        g.setFont(new Font("Monospaced", Font.PLAIN, 11));
        g.drawString("folk " + folks.size() + " · dead " + deaths, w - 190, ky + 10);
        if (paused) { g.setColor(new Color(255, 220, 120, 230)); g.drawString("|| PAUSED (P)", w - 190, ky + 26); }
        else if (speed > 1) { g.setColor(new Color(160, 255, 160, 200)); g.drawString(">> speed x" + speed + " (,/.)", w - 190, ky + 26); }

        // brush ring at cursor (cyan when placing static)
        g.setColor(pinMode ? new Color(120, 220, 255, 150) : new Color(255, 255, 255, 90));
        g.setStroke(new BasicStroke(1.5f));
        int rr = brush * SCALE;
        g.drawOval(mx - rr, my - rr, rr * 2, rr * 2);

        // casting beam from wand to cursor
        if (lmb) {
            int wx = (int) ((faceR ? plx + PW : plx - 1) * SCALE) + SCALE / 2;
            int wy = (int) ((ply + 2) * SCALE);
            g.setColor(new Color(255, 230, 140, 90));
            g.setStroke(new BasicStroke(3));
            g.drawLine(wx, wy, mx, my);
            g.setColor(new Color(255, 255, 255, 160));
            for (int k = 0; k < 5; k++) {
                double t = rnd.nextDouble();
                g.fillRect((int) (wx + (mx - wx) * t) + rnd.nextInt(7) - 3,
                           (int) (wy + (my - wy) * t) + rnd.nextInt(7) - 3, 2, 2);
            }
        }
    }

    void drawHeart(Graphics2D g, int x, int y, boolean full) {
        g.setColor(full ? new Color(0xE83A4E) : new Color(40, 40, 48, 200));
        g.fillOval(x, y, 9, 9);
        g.fillOval(x + 7, y, 9, 9);
        int[] xs = {x, x + 16, x + 8};
        int[] ys = {y + 6, y + 6, y + 16};
        g.fillPolygon(xs, ys, 3);
    }

    // =============================================================
    // tiny utils
    // =============================================================
    boolean in(int x, int y) { return x >= 0 && x < W && y >= 0 && y < H; }
    static int clampi(int v, int a, int b) { return v < a ? a : v > b ? b : v; }

    static int hash(int x, int y) {
        int h = x * 374761393 + y * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return h ^ (h >>> 16);
    }

    static int lerpC(int c1, int c2, double t) {
        t = t < 0 ? 0 : t > 1 ? 1 : t;
        int r = (int) (((c1 >> 16) & 255) + (((c2 >> 16) & 255) - ((c1 >> 16) & 255)) * t);
        int g = (int) (((c1 >> 8) & 255) + (((c2 >> 8) & 255) - ((c1 >> 8) & 255)) * t);
        int b = (int) ((c1 & 255) + ((c2 & 255) - (c1 & 255)) * t);
        return (r << 16) | (g << 8) | b;
    }

    static int adj(int c, int d) {
        int r = clampi(((c >> 16) & 255) + d, 0, 255);
        int g = clampi(((c >> 8) & 255) + d, 0, 255);
        int b = clampi((c & 255) + d, 0, 255);
        return (r << 16) | (g << 8) | b;
    }

    static int scaleC(int c, float f) {
        int r = clampi((int) (((c >> 16) & 255) * f), 0, 255);
        int g = clampi((int) (((c >> 8) & 255) * f), 0, 255);
        int b = clampi((int) ((c & 255) * f), 0, 255);
        return (r << 16) | (g << 8) | b;
    }

    // =============================================================
    // game loop + windowing
    // =============================================================
    public void run() {
        long last = System.nanoTime();
        double acc = 0, step = 1e9 / 60;
        while (true) {
            long now = System.nanoTime();
            acc += now - last; last = now;
            if (acc > 6 * step) acc = 6 * step;
            boolean did = false;
            while (acc >= step) {
                if (!paused) for (int s = 0; s < speed; s++) tick();
                acc -= step; did = true;
            }
            if (did) draw();
            try { Thread.sleep(1); } catch (InterruptedException e) { return; }
        }
    }

    void draw() {
        BufferStrategy bs = getBufferStrategy();
        if (bs == null) return;
        renderFrame();
        Graphics2D g = (Graphics2D) bs.getDrawGraphics();
        int ox = (int) ((rnd.nextDouble() * 2 - 1) * shake);
        int oy = (int) ((rnd.nextDouble() * 2 - 1) * shake);
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, W * SCALE, H * SCALE);
        g.drawImage(img, ox, oy, W * SCALE, H * SCALE, null);
        hud(g, W * SCALE, H * SCALE);
        g.dispose();
        bs.show();
    }

    void initInput() {
        addKeyListener(new KeyAdapter() {
            public void keyPressed(KeyEvent e) {
                int c = e.getKeyCode();
                if (c < keys.length) keys[c] = true;
                if (c >= KeyEvent.VK_1 && c <= KeyEvent.VK_9 && c - KeyEvent.VK_1 < PALETTE.length) sel = c - KeyEvent.VK_1;
                if (c == KeyEvent.VK_0) sel = 9;
                if (c == KeyEvent.VK_MINUS) sel = 10;
                if (c == KeyEvent.VK_EQUALS) sel = 11;
                if (c == KeyEvent.VK_E) sel = (sel + 1) % PALETTE.length;
                if (c == KeyEvent.VK_OPEN_BRACKET) brush = Math.max(1, brush - 1);
                if (c == KeyEvent.VK_CLOSE_BRACKET) brush = Math.min(6, brush + 1);
                if (c == KeyEvent.VK_F) pinMode = !pinMode;
                if (c == KeyEvent.VK_T) thermal = !thermal;
                if (c == KeyEvent.VK_K) sel = PALETTE.length - 1; // folk brush
                if (c == KeyEvent.VK_P) paused = !paused;
                if (c == KeyEvent.VK_COMMA) speed = Math.max(1, speed / 2);
                if (c == KeyEvent.VK_PERIOD) speed = Math.min(8, speed * 2);
                if (c == KeyEvent.VK_R) weather = (weather + 1) % 3;
                if (c == KeyEvent.VK_N) gen();
                if (c == KeyEvent.VK_M && meteorCd == 0) {
                    meteorCd = 40;
                    double sx = rnd.nextInt(W);
                    meteors.add(new double[]{sx, -4, (plx - sx) / 110.0, 1.3});
                }
            }
            public void keyReleased(KeyEvent e) {
                if (e.getKeyCode() < keys.length) keys[e.getKeyCode()] = false;
            }
        });
        MouseAdapter ma = new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) lmb = true;
                if (e.getButton() == MouseEvent.BUTTON3) rmb = true;
            }
            public void mouseReleased(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) lmb = false;
                if (e.getButton() == MouseEvent.BUTTON3) rmb = false;
            }
            public void mouseMoved(MouseEvent e) { mx = e.getX(); my = e.getY(); }
            public void mouseDragged(MouseEvent e) { mx = e.getX(); my = e.getY(); }
            public void mouseWheelMoved(MouseWheelEvent e) {
                sel = Math.floorMod(sel + e.getWheelRotation(), PALETTE.length);
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
        addMouseWheelListener(ma);
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--shot")) {
            shotMode(args.length > 1 ? args[1] : "shot.png",
                     args.length > 2 ? Double.parseDouble(args[2]) : 0.12,
                     args.length > 3 ? args[3] : "normal");
            return;
        }
        Pixelmancer game = new Pixelmancer();
        game.gen();
        JFrame f = new JFrame("PIXELMANCER — falling-sand sorcery");
        game.setPreferredSize(new Dimension(W * SCALE, H * SCALE));
        f.add(game);
        f.pack();
        f.setResizable(false);
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        f.setLocationRelativeTo(null);
        f.setVisible(true);
        game.createBufferStrategy(2);
        game.initInput();
        game.setFocusable(true);
        game.requestFocus();
        new Thread(game, "game").start();
    }

    /** Headless demo screenshot: simulate a while, blow something up, save a PNG. */
    static void shotMode(String out, double timeOfDay, String mode) throws Exception {
        Pixelmancer p = new Pixelmancer();
        p.gen();
        if (mode.equals("folk")) { // let history happen, then force a war for the cameras
            for (int k = 0; k < 6000; k++) { p.weather = 0; p.tick(); }
            if (p.kingdoms.size() >= 2) p.declareWar(p.kingdoms.get(0), p.kingdoms.get(1));
            for (int k = 0; k < 3000; k++) { p.weather = 0; p.tick(); }
            p.dayT = timeOfDay;
            p.tick();
            p.renderFrame();
            BufferedImage big2 = new BufferedImage(W * SCALE, H * SCALE, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2 = big2.createGraphics();
            g2.drawImage(p.img, 0, 0, W * SCALE, H * SCALE, null);
            p.hud(g2, W * SCALE, H * SCALE);
            g2.dispose();
            ImageIO.write(big2, "png", new File(out));
            System.out.println("wrote " + out + " (folk " + p.folks.size() + ", kingdoms " + p.kingdoms.size()
                    + ", deaths " + p.deaths + ")");
            return;
        }
        if (mode.equals("snow")) p.weather = 2;
        for (int k = 0; k < 120; k++) { if (mode.equals("snow")) p.weather = 2; p.tick(); }
        // pour some lava on a hill, quench half of it, drop a meteor — for science
        int lx = W / 4, ly = Math.max(4, p.groundY(lx) - 20);
        for (int dy = 0; dy < 4; dy++) for (int dx = 0; dx < 8; dx++)
            p.set((ly + dy) * W + lx + dx, LAVA);
        for (int k = 0; k < 60; k++) { if (mode.equals("snow")) p.weather = 2; p.tick(); }
        for (int dy = 0; dy < 3; dy++) for (int dx = 0; dx < 5; dx++)
            if (p.m[(ly - 8 + dy) * W + lx + dx] == EMPTY) p.set((ly - 8 + dy) * W + lx + dx, WATER);
        p.meteors.add(new double[]{W * 0.75, -4, 0.1, 1.5});
        for (int k = 0; k < (mode.equals("snow") ? 320 : 150); k++) {
            if (mode.equals("snow")) p.weather = 2;
            p.tick();
        }
        p.dayT = timeOfDay;
        if (mode.equals("thermal")) p.thermal = true;
        p.tick();
        p.renderFrame();
        BufferedImage big = new BufferedImage(W * SCALE, H * SCALE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = big.createGraphics();
        g.drawImage(p.img, 0, 0, W * SCALE, H * SCALE, null);
        p.hud(g, W * SCALE, H * SCALE);
        g.dispose();
        ImageIO.write(big, "png", new File(out));
        System.out.println("wrote " + out);
    }
}
