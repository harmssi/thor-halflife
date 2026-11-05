package de.harmssi.thorhalflife;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Save games of the running game (basedir/gamedir/save/*.sav): name, map from the save header,
 * file time and the engine's save shot (*.bmp next to the .sav). Read only.
 */
final class Saves {
    private Saves() {}

    static final class Entry {
        String name, map;
        long time;
        Bitmap shot;
    }

    private static final int SAVEGAME_HEADER = ('V' << 24) + ('A' << 16) + ('S' << 8) + 'J';
    private static final Map<String, Entry> cache = new HashMap<>();

    /** Newest first. Header and shot are parsed once per file name + mtime. */
    static List<Entry> scan(File dir) {
        List<Entry> out = new ArrayList<>();
        File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".sav"));
        if (files == null) {
            return out;
        }
        for (File f : files) {
            String name = f.getName().substring(0, f.getName().length() - 4);
            File bmp = new File(dir, name + ".bmp");
            String key = name + "@" + f.lastModified() + "@" + bmp.lastModified();
            Entry e = cache.get(key);
            if (e == null) {
                e = new Entry();
                e.name = name;
                e.time = f.lastModified();
                e.map = mapName(f);
                if (bmp.isFile()) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inPreferredConfig = Bitmap.Config.RGB_565;
                    e.shot = BitmapFactory.decodeFile(bmp.getPath(), o);
                }
                cache.put(key, e);
            }
            out.add(e);
        }
        out.sort((a, b) -> Long.compare(b.time, a.time));
        return out;
    }

    /** "mapName" field of the GameHeader block (layout as in engine/server/sv_save.c SV_GetSaveComment), or "". */
    static String mapName(File f) {
        try (InputStream in = new FileInputStream(f)) {
            ByteBuffer h = ByteBuffer.wrap(readN(in, 20)).order(ByteOrder.LITTLE_ENDIAN);
            if (h.getInt() != SAVEGAME_HEADER) {
                return "";
            }
            h.getInt(); // version
            h.getInt(); // data size
            int tokenCount = h.getInt(), tokenSize = h.getInt();
            if (tokenCount < 0 || tokenCount > 0x10000 || tokenSize < 0 || tokenSize > 0x400000) {
                return "";
            }
            // the GameHeader block follows the token table and is small
            ByteBuffer b = ByteBuffer.wrap(readN(in, tokenSize + 4096)).order(ByteOrder.LITTLE_ENDIAN);
            String[] tokens = new String[tokenCount];
            int p = 0;
            for (int i = 0; i < tokenCount; i++) {
                int start = p;
                while (b.get(p) != 0) {
                    p++;
                }
                tokens[i] = p > start ? new String(b.array(), start, p - start, StandardCharsets.ISO_8859_1) : null;
                p++;
            }
            int blockSize = b.getShort(p);
            if (!"GameHeader".equals(tokens[b.getShort(p + 2)])) {
                return "";
            }
            int fields = b.get(p + 4);
            p += 4 + blockSize;
            for (int i = 0; i < fields; i++) {
                int size = b.getShort(p);
                String field = tokens[b.getShort(p + 2)];
                p += 4;
                if ("mapName".equals(field)) {
                    int end = p;
                    while (end < p + size && b.get(end) != 0) {
                        end++;
                    }
                    return new String(b.array(), p, end - p, StandardCharsets.ISO_8859_1);
                }
                p += size;
            }
        } catch (IOException | RuntimeException ignored) {
            // unreadable or truncated save: shown without a map name
        }
        return "";
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int got = 0;
        while (got < n) {
            int r = in.read(buf, got, n - got);
            if (r < 0) {
                break;
            }
            got += r;
        }
        return buf;
    }
}
