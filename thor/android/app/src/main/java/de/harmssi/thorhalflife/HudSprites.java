package de.harmssi.thorhalflife;

import android.graphics.Bitmap;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Weapon silhouettes for the deck, decoded at runtime from the user's own game files
 * (<game>/sprites/weapon_*.txt + the HUD sprite sheets, falling back to valve for the weapons an
 * add-on shares with Half-Life). Nothing from the game ships with the app.
 *
 * Output bitmaps are white with alpha = sprite brightness; the deck tints them when drawing.
 */
final class HudSprites {
    private static final String TAG = "ThorHalfLife";
    private final File[] roots;
    private final Map<String, Bitmap> cache = new HashMap<>();
    private final Map<String, Boolean> pending = new HashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable onLoaded;

    HudSprites(String rodir, String game, Runnable onLoaded) {
        // the game's own folders first (HD pack, then base), then Half-Life (valve_hd, valve)
        java.util.List<File> r = new java.util.ArrayList<>();
        if (game != null && !game.equals("valve")) {
            r.add(new File(rodir, game + "_hd/sprites"));
            r.add(new File(rodir, game + "/sprites"));
        }
        r.add(new File(rodir, "valve_hd/sprites"));
        r.add(new File(rodir, "valve/sprites"));
        this.roots = r.toArray(new File[0]);
        this.onLoaded = onLoaded;
    }

    /** Weapon icon mask or null (loads in the background on first request). */
    synchronized Bitmap weapon(String weaponName) {
        Bitmap b = cache.get(weaponName);
        if (b != null || pending.containsKey(weaponName)) {
            return b;
        }
        pending.put(weaponName, Boolean.TRUE);
        worker.execute(() -> {
            Bitmap bmp = null;
            try {
                bmp = loadWeapon(weaponName);
            } catch (Throwable t) {
                Log.w(TAG, "weapon sprite " + weaponName + ": " + t);
            }
            synchronized (HudSprites.this) {
                if (bmp != null) {
                    cache.put(weaponName, bmp);
                }
            }
            if (bmp != null && onLoaded != null) {
                onLoaded.run();
            }
        });
        return null;
    }

    void shutdown() {
        worker.shutdownNow();
    }

    private File find(String rel) {
        for (File r : roots) {
            File f = new File(r, rel);
            if (f.isFile()) {
                return f;
            }
        }
        return null;
    }

    private Bitmap loadWeapon(String weaponName) throws Exception {
        File txt = find(weaponName + ".txt");
        if (txt == null) {
            return null;
        }
        String[] lines = new String(readAll(txt), StandardCharsets.ISO_8859_1).split("\n");
        // prefer a dedicated 1280 sprite, then the 640 sheet
        String[] best = null;
        int bestScore = -1;
        for (String line : lines) {
            String[] t = line.trim().split("\\s+");
            if (t.length < 7 || !t[0].equals("weapon")) {
                continue;
            }
            int res;
            try {
                res = Integer.parseInt(t[1]);
            } catch (NumberFormatException e) {
                continue;
            }
            int score = res == 1280 ? 3 : res == 640 ? 2 : res == 2560 ? 1 : 0;
            if (score > bestScore && find(t[2] + ".spr") != null) {
                bestScore = score;
                best = t;
            }
        }
        if (best == null) {
            return null;
        }
        int x = Integer.parseInt(best[3]), y = Integer.parseInt(best[4]);
        int w = Integer.parseInt(best[5]), h = Integer.parseInt(best[6]);
        return decode(find(best[2] + ".spr"), x, y, w, h);
    }

    /** Decode frame 0 of a GoldSrc sprite (IDSP v2) and cut out a rect as an alpha mask. */
    static Bitmap decode(File spr, int rx, int ry, int rw, int rh) throws Exception {
        ByteBuffer b = ByteBuffer.wrap(readAll(spr)).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt() != 0x50534449 || b.getInt() != 2) { // "IDSP", version 2
            return null;
        }
        b.getInt(); // type
        int texFormat = b.getInt();
        b.getFloat(); // radius
        b.getInt(); // max width
        b.getInt(); // max height
        int frames = b.getInt();
        b.getFloat(); // beam length
        b.getInt(); // sync type
        int palCount = b.getShort() & 0xFFFF;
        byte[] pal = new byte[palCount * 3];
        b.get(pal);
        if (frames < 1) {
            return null;
        }
        int group = b.getInt();
        if (group != 0) {
            return null; // frame groups are not used by HUD sheets
        }
        b.getInt(); // origin x
        b.getInt(); // origin y
        int fw = b.getInt(), fh = b.getInt();
        byte[] px = new byte[fw * fh];
        b.get(px);
        rx = Math.max(0, rx);
        ry = Math.max(0, ry);
        rw = Math.min(rw, fw - rx);
        rh = Math.min(rh, fh - ry);
        if (rw <= 0 || rh <= 0) {
            return null;
        }
        // a rect without any bright pixel is not an icon (Opposing Force's weapon_penguin.txt points
        // at the empty, striped part of 640hudof03): no silhouette instead of a grey block
        int peak = 0;
        for (int yy = 0; yy < rh; yy++) {
            for (int xx = 0; xx < rw; xx++) {
                int idx = px[(ry + yy) * fw + rx + xx] & 0xFF;
                if (!(texFormat == 3 && idx == 255)) {
                    peak = Math.max(peak, texFormat == 2 ? idx : lum(pal, idx));
                }
            }
        }
        if (peak < 120) {
            return null;
        }
        int[] argb = new int[rw * rh];
        for (int yy = 0; yy < rh; yy++) {
            for (int xx = 0; xx < rw; xx++) {
                int idx = px[(ry + yy) * fw + rx + xx] & 0xFF;
                int a;
                if (texFormat == 2) { // SPR_INDEXALPHA
                    a = idx;
                } else if (texFormat == 3) { // SPR_ALPHTEST
                    a = idx == 255 ? 0 : lum(pal, idx);
                } else { // additive / normal: brightness = coverage
                    a = lum(pal, idx);
                }
                // HUD sheets have a dim, non-black background: cut it off and stretch the rest
                a = Math.max(0, Math.min(255, (a - 48) * 255 / 160));
                argb[yy * rw + xx] = (a << 24) | 0x00FFFFFF;
            }
        }
        return Bitmap.createBitmap(argb, rw, rh, Bitmap.Config.ARGB_8888);
    }

    private static int lum(byte[] pal, int idx) {
        if (idx * 3 + 2 >= pal.length) {
            return 0;
        }
        int r = pal[idx * 3] & 0xFF, g = pal[idx * 3 + 1] & 0xFF, bl = pal[idx * 3 + 2] & 0xFF;
        return Math.max(r, Math.max(g, bl));
    }

    private static byte[] readAll(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            return in.readAllBytes();
        }
    }
}
