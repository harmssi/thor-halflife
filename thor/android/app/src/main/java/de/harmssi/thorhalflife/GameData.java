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
 * rodir  (read-only): the user's own game files, default /sdcard/xash/{valve,gearbox,bshift}.
 *         Never written. Opposing Force and Blue Shift also need the Half-Life base data (valve).
 * basedir (writable): /sdcard/ThorHalfLife/<gamedir> - config.cfg, saves, logs, thor.cfg.
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

    /** A supported game: its game folder (= engine gamedir) and the files that must be there. */
    static final class Game {
        final String dir;       // valve, gearbox, bshift
        final int titleRes;
        final String startMap;  // checked in <dir>/maps (or a pak0.pak)
        final String clientLib; // client library in the APK (the engine loads it as libclient_android_arm64.so)

        Game(String dir, int titleRes, String startMap, String clientLib) {
            this.dir = dir;
            this.titleRes = titleRes;
            this.startMap = startMap;
            this.clientLib = clientLib;
        }

        boolean isValve() {
            return dir.equals("valve");
        }
    }

    static final Game[] GAMES = {
            new Game("valve", R.string.game_valve, "c1a0", "libclient_android_arm64.so"),
            new Game("gearbox", R.string.game_gearbox, "of0a0", "libclient_gearbox.so"),
            new Game("bshift", R.string.game_bshift, "ba_tram1", "libclient_bshift.so"),
    };

    static Game game(String dir) {
        for (Game g : GAMES) {
            if (g.dir.equals(dir)) {
                return g;
            }
        }
        return GAMES[0];
    }

    static List<Check> check(Context ctx, String rodir, Game game) {
        List<Check> list = new ArrayList<>();
        File valve = new File(rodir, "valve");
        boolean access = hasStorageAccess();
        list.add(new Check(ctx.getString(R.string.check_access), access, true));
        if (game.isValve()) {
            list.add(new Check("valve/liblist.gam", new File(valve, "liblist.gam").isFile(), true));
            list.add(new Check("valve/halflife.wad", new File(valve, "halflife.wad").isFile(), true));
            list.add(new Check("valve/maps/c1a0.bsp", new File(valve, "maps/c1a0.bsp").isFile() || hasPak(valve), true));
            list.add(new Check("valve/sound, models", new File(valve, "models").isDirectory() || hasPak(valve), true));
            list.add(new Check(ctx.getString(R.string.check_hd), new File(rodir, "valve_hd").isDirectory(), false));
            return list;
        }
        // add-ons run on top of the Half-Life base data
        File dir = new File(rodir, game.dir);
        boolean base = new File(valve, "liblist.gam").isFile() && new File(valve, "halflife.wad").isFile();
        list.add(new Check(ctx.getString(R.string.check_base), base, true));
        list.add(new Check(game.dir + "/liblist.gam", new File(dir, "liblist.gam").isFile(), true));
        list.add(new Check(game.dir + "/maps/" + game.startMap + ".bsp",
                new File(dir, "maps/" + game.startMap + ".bsp").isFile() || hasPak(dir), true));
        list.add(new Check(game.dir + "/sound, models", new File(dir, "models").isDirectory() || hasPak(dir), true));
        list.add(new Check("HD pack (" + game.dir + "_hd)", new File(rodir, game.dir + "_hd").isDirectory(), false));
        return list;
    }

    /** Are the game files there at all (for the game selector; ignores file access)? */
    static boolean present(String rodir, Game game) {
        File dir = new File(rodir, game.dir);
        return new File(dir, "liblist.gam").isFile()
                && (game.isValve() || new File(rodir, "valve/liblist.gam").isFile());
    }

    private static boolean hasPak(File dir) {
        return new File(dir, "pak0.pak").isFile();
    }

    static boolean ready(Context ctx, String rodir, Game game) {
        for (Check c : check(ctx, rodir, game)) {
            if (c.required && !c.ok) {
                return false;
            }
        }
        return true;
    }

    /** Create the writable game dir and write thor.cfg (run by the engine via +exec thor.cfg). */
    static void prepareBaseDir(Context ctx, File basedir, Game game, boolean applyBinds, boolean dual) {
        File gdir = new File(basedir, game.dir);
        if (!gdir.isDirectory() && !gdir.mkdirs()) {
            Log.w(GameActivity.TAG, "cannot create " + gdir);
            return;
        }
        // Until the engine has saved its own config.cfg here (on a clean quit), the read-only
        // config from the data folder is used: keep applying the Thor layout until then.
        boolean firstStart = !new File(gdir, "config.cfg").isFile();
        if (firstStart) {
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
        if (firstStart) {
            // uncensored by default (the German Steam data ships a violence.cfg that turns it off)
            cfg.append("violence_hblood 1\nviolence_hgibs 1\nviolence_ablood 1\nviolence_agibs 1\n");
        }
        cfg.append("exec thor_user.cfg\n");
        write(new File(gdir, "thor.cfg"), cfg.toString());
        File user = new File(gdir, "thor_user.cfg");
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
