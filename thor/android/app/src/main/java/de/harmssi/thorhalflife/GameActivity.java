package de.harmssi.thorhalflife;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.provider.Settings.Secure;
import android.util.Log;
import android.view.KeyEvent;
import android.view.WindowManager;

import org.libsdl.app.SDLActivity;

import java.io.File;

/**
 * Runs Xash3D FWGS (SDL2) full screen on the top display; the HEV deck goes to the second display.
 *
 * The engine calls back into this activity through JNI (engine/platform/android/android.c,
 * filesystem/android.c): getAndroidID/loadAndroidID/saveAndroidID, getWindowInsets, getAssets,
 * getAssetsList, getCallingPackage. These are adapted from FWGS XashActivity (GPL-3.0).
 *
 * Intent extras (all optional; the launcher fills them from the settings):
 *   rodir  = read-only data root with valve/ (default /sdcard/xash)
 *   basedir= writable root for config + saves (default /sdcard/ThorHalfLife)
 *   lang   = engine/game language for +ui_language (e.g. "german")
 *   single = true forces the single-screen mode (classic HUD on top)
 *   ref    = renderer library (gles3compat [default], gl4es, gles1)
 *   argv   = extra engine arguments
 */
public class GameActivity extends SDLActivity {
    static final String TAG = "ThorHalfLife";
    static final String EXTRA_RODIR = "rodir";
    static final String EXTRA_BASEDIR = "basedir";
    static final String EXTRA_LANG = "lang";
    static final String EXTRA_SINGLE = "single";
    static final String EXTRA_ARGV = "argv";
    static final String EXTRA_REF = "ref";
    static final String EXTRA_APPLY_BINDS = "apply_binds";

    static final String CLIENT_LIB = "libclient_android_arm64.so";

    private ThorScreens screens;
    private boolean dualScreen;
    private volatile float[] windowInsets = new float[4];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Intent intent = getIntent();
        dualScreen = !intent.getBooleanExtra(EXTRA_SINGLE, false) && ThorScreens.hasSecondScreen(this);
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().getAttributes().layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (mBrokenLibraries) {
            return; // SDL shows its own error dialog
        }
        if (dualScreen) {
            screens = new ThorScreens(this, clientLibPath(),
                    orDefault(intent.getStringExtra(EXTRA_RODIR), GameData.DEFAULT_RODIR));
            screens.start();
        }
        Log.i(TAG, "engine starting, dual=" + dualScreen);
    }

    String clientLibPath() {
        return getApplicationInfo().nativeLibraryDir + "/" + CLIENT_LIB;
    }

    // ------------------------------------------------------------------ SDL / engine setup

    @Override
    protected String[] getLibraries() {
        return new String[]{"SDL2", "xash"};
    }

    @Override
    protected String[] getArguments() {
        Intent intent = getIntent();
        String rodir = orDefault(intent.getStringExtra(EXTRA_RODIR), GameData.DEFAULT_RODIR);
        String basedir = orDefault(intent.getStringExtra(EXTRA_BASEDIR), GameData.DEFAULT_BASEDIR);
        File crashDir = new File(getFilesDir(), "crashes");
        crashDir.mkdirs();
        GameData.prepareBaseDir(this, new File(basedir), intent.getBooleanExtra(EXTRA_APPLY_BINDS, false), dualScreen);

        nativeSetenv("XASH3D_CRASH_DIR", crashDir.getAbsolutePath());
        nativeSetenv("XASH3D_GAME", "valve");
        nativeSetenv("XASH3D_GAMELIBDIR", getApplicationInfo().nativeLibraryDir);
        nativeSetenv("XASH3D_RODIR", rodir);
        nativeSetenv("XASH3D_BASEDIR", basedir);
        nativeSetenv("THOR_DECK", dualScreen ? "1" : "0");

        String ref = orDefault(intent.getStringExtra(EXTRA_REF), "gles3compat");
        StringBuilder argv = new StringBuilder("-console -log -ref ").append(ref);
        String lang = intent.getStringExtra(EXTRA_LANG);
        if (lang != null && !lang.isEmpty()) {
            argv.append(" +ui_language ").append(lang);
        }
        argv.append(" +touch_enable 0 +exec thor.cfg");
        String extra = intent.getStringExtra(EXTRA_ARGV);
        if (extra != null && !extra.trim().isEmpty()) {
            argv.append(' ').append(extra.trim());
        }
        Log.i(TAG, "rodir=" + rodir + " basedir=" + basedir + " argv=" + argv);
        return argv.toString().split(" ");
    }

    private static String orDefault(String v, String def) {
        return v == null || v.isEmpty() ? def : v;
    }

    @Override
    public void setOrientationBis(int w, int h, boolean resizable, String hint) {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    // ------------------------------------------------------------------ engine JNI callbacks

    @SuppressLint("HardwareIds")
    private String getAndroidID() {
        String id = Secure.getString(getContentResolver(), Secure.ANDROID_ID);
        return id != null ? id : "thor";
    }

    private void saveAndroidID(String id) {
        getSharedPreferences("xash_preferences", MODE_PRIVATE).edit().putString("xash_id", id).apply();
    }

    private String loadAndroidID() {
        return getSharedPreferences("xash_preferences", MODE_PRIVATE).getString("xash_id", "");
    }

    private float[] getWindowInsets() {
        return windowInsets;
    }

    private AssetManager getAssets(boolean isEngine) {
        return getAssets();
    }

    private String[] getAssetsList(boolean isEngine, String path) {
        try {
            String[] list = getAssets().list(path);
            return list != null ? list : new String[0];
        } catch (Exception e) {
            return new String[0];
        }
    }

    @Override
    public String getCallingPackage() {
        return getPackageName();
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (SDLActivity.mBrokenLibraries) {
            return false;
        }
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_UP) {
            return false; // keep the system volume keys
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (screens != null) {
            screens.pause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (screens != null) {
            screens.resume();
        }
    }

    @Override
    protected void onDestroy() {
        if (screens != null) {
            screens.stop();
            screens = null;
        }
        super.onDestroy();
        // The engine keeps global state: end the ":game" process so the next start is fresh.
        System.exit(0);
    }
}
