package de.harmssi.thorhalflife;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Where the game data lives and what the engine needs.
 *
 * rodir  (read-only): the user's own Half-Life files, default /sdcard/xash/valve. Never written.
 * basedir (writable): /sdcard/ThorHalfLife/valve - config.cfg, saves, logs, thor.cfg.
 */
final class GameData {
    private GameData() {}

    static final String DEFAULT_RODIR = "/sdcard/xash";
    static final String DEFAULT_BASEDIR = "/sdcard/ThorHalfLife";

    /** One line of the data checklist. */
    static final class Check {
        final String label;
        final boolean ok;
        final boolean required;

        Check(String label, boolean ok, boolean required) {
            this.label = label;
            this.ok = ok;
            this.required = required;
        }
    }

    static boolean hasStorageAccess() {
        return Environment.isExternalStorageManager();
    }

    static List<Check> check(Context ctx, String rodir) {
        List<Check> list = new ArrayList<>();
        File valve = new File(rodir, "valve");
        boolean access = hasStorageAccess();
        list.add(new Check(ctx.getString(R.string.check_access), access, true));
        list.add(new Check("valve/liblist.gam", new File(valve, "liblist.gam").isFile(), true));
        list.add(new Check("valve/halflife.wad", new File(valve, "halflife.wad").isFile(), true));
        list.add(new Check("valve/maps/c1a0.bsp", new File(valve, "maps/c1a0.bsp").isFile() || hasPak(valve), true));
        list.add(new Check("valve/sound, models", new File(valve, "models").isDirectory() || hasPak(valve), true));
        list.add(new Check(ctx.getString(R.string.check_hd), new File(rodir, "valve_hd").isDirectory(), false));
        return list;
    }

    private static boolean hasPak(File valve) {
        return new File(valve, "pak0.pak").isFile();
    }

    static boolean ready(Context ctx, String rodir) {
        for (Check c : check(ctx, rodir)) {
            if (c.required && !c.ok) {
                return false;
            }
        }
        return true;
    }

    /** Create the writable game dir and write thor.cfg (run by the engine via +exec thor.cfg). */
    static void prepareBaseDir(Context ctx, File basedir, boolean applyBinds, boolean dual) {
        File valve = new File(basedir, "valve");
        if (!valve.isDirectory() && !valve.mkdirs()) {
            Log.w(GameActivity.TAG, "cannot create " + valve);
            return;
        }
        // Until the engine has saved its own config.cfg here (on a clean quit), the read-only
        // config from the data folder is used: keep applying the Thor layout until then.
        if (!new File(valve, "config.cfg").isFile()) {
            applyBinds = true;
        }
        StringBuilder cfg = new StringBuilder();
        cfg.append("// Thor Half-Life: written by the app on every launch, do not edit\n");
        cfg.append("thor_deck ").append(dual ? 1 : 0).append('\n');
        cfg.append("touch_enable 0\n");
        if (applyBinds) {
            cfg.append("// first launch / reset: Thor gamepad layout (assets/gamepad_presets/thor.cfg)\n");
            cfg.append("joy_loadpreset thor\n");
        }
        cfg.append("exec thor_user.cfg\n");
        write(new File(valve, "thor.cfg"), cfg.toString());
        File user = new File(valve, "thor_user.cfg");
        if (!user.exists()) {
            write(user, "// Thor Half-Life: your own extra commands, executed on every launch\n");
        }
    }

    private static void write(File f, String s) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(GameActivity.TAG, "write " + f + " failed", e);
        }
    }
}
