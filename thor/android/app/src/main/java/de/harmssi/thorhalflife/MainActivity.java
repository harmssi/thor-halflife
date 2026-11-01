package de.harmssi.thorhalflife;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Launcher: title, data check, PLAY, settings (screens, language EN/DE/ES/FR, controller layout),
 * licenses and the trademark notice. Fully controller-navigable (D-pad / stick + A).
 */
public class MainActivity extends Activity {
    private static final String PREFS = "thorhalflife";
    static final String P_SINGLE = "single";
    static final String P_BINDS_DONE = "binds_done";
    static final String P_RODIR = "rodir";
    static final String P_REF = "renderer";
    static final String[] RENDERERS = {"gles3compat", "gl4es", "gles1"};

    private float f = 1f; // scale: design grid is 1920x1080
    private SharedPreferences prefs;
    private LinearLayout checkList;
    private TextView playButton, accessButton, statusLine;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int w = Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
        f = w / 1920f;
        setContentView(buildUi());
        hideSystemBars();
    }

    private void hideSystemBars() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshChecks();
    }

    private float px(float design) {
        return design * f;
    }

    private String rodir() {
        return prefs.getString(P_RODIR, GameData.DEFAULT_RODIR);
    }

    // ------------------------------------------------------------------ UI

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new Backdrop());

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.HORIZONTAL);
        main.setPadding((int) px(72), (int) px(56), (int) px(72), (int) px(40));
        root.addView(main, new FrameLayout.LayoutParams(-1, -1));

        // ---- left column: title, data check, play
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        main.addView(left, new LinearLayout.LayoutParams(0, -1, 1.15f));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        left.addView(head, new LinearLayout.LayoutParams(-1, -2));
        View mark = new View(this) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(Canvas c) {
                drawMark(c, getWidth() / 2f, getHeight() / 2f, getHeight() * 0.46f, p);
            }
        };
        head.addView(mark, new LinearLayout.LayoutParams((int) px(84), (int) px(84)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding((int) px(24), 0, 0, 0);
        TextView title = text("THOR HALF-LIFE", Ui.interBold(), 44, Ui.TEXT);
        title.setLetterSpacing(0.12f);
        titles.addView(title);
        TextView tag = label(getString(R.string.tagline), 15, Ui.TEXT_3);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.topMargin = (int) px(10);
        titles.addView(tag, tlp);
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));

        // data check card
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding((int) px(32), (int) px(28), (int) px(32), (int) px(28));
        card.setBackground(panelBg(Ui.PANEL, Ui.LINE, Ui.RADIUS));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, 0, 1f);
        clp.topMargin = (int) px(44);
        left.addView(card, clp);
        card.addView(label(getString(R.string.data_check), 15, Ui.TEXT_3));
        TextView path = text(rodir() + "/valve", Ui.mono(), 18, Ui.TEXT_2);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, -2);
        plp.topMargin = (int) px(12);
        card.addView(path, plp);
        checkList = new LinearLayout(this);
        checkList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.topMargin = (int) px(14);
        card.addView(checkList, cl);
        View spacer = new View(this);
        card.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView saves = para(getString(R.string.save_dir, GameData.DEFAULT_BASEDIR + "/valve"));
        card.addView(saves);

        // play row
        LinearLayout playRow = new LinearLayout(this);
        playRow.setOrientation(LinearLayout.HORIZONTAL);
        playRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams prl = new LinearLayout.LayoutParams(-1, (int) px(96));
        prl.topMargin = (int) px(24);
        left.addView(playRow, prl);
        playButton = label(getString(R.string.play), 22, Ui.BG);
        playButton.setGravity(Gravity.CENTER);
        playButton.setFocusable(true);
        playButton.setClickable(true);
        StateListDrawable pb = new StateListDrawable();
        pb.addState(new int[]{android.R.attr.state_focused}, panelBg(Ui.AMBER_2, Ui.TEXT, Ui.RADIUS, 3));
        pb.addState(new int[]{android.R.attr.state_pressed}, panelBg(Ui.AMBER_2, Ui.AMBER, Ui.RADIUS, 2));
        pb.addState(new int[]{}, panelBg(Ui.AMBER, Ui.AMBER, Ui.RADIUS, 1));
        playButton.setBackground(pb);
        playButton.setOnClickListener(v -> launch());
        playRow.addView(playButton, new LinearLayout.LayoutParams((int) px(360), -1));
        accessButton = pill(getString(R.string.grant_access));
        accessButton.setGravity(Gravity.CENTER);
        accessButton.setOnClickListener(v -> requestAccess());
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, -1);
        alp.leftMargin = (int) px(16);
        playRow.addView(accessButton, alp);
        statusLine = text("", Ui.inter(), 17, Ui.TEXT_3);
        statusLine.setPadding((int) px(24), 0, 0, 0);
        playRow.addView(statusLine, new LinearLayout.LayoutParams(0, -2, 1f));

        // ---- right column: settings + legal
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, -1, 1f);
        rlp.leftMargin = (int) px(48);
        main.addView(right, rlp);
        String version = "";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            version = "v" + pi.versionName;
        } catch (Exception ignored) {
        }
        TextView ver = text(version, Ui.mono(), 18, Ui.TEXT_3);
        ver.setGravity(Gravity.END);
        right.addView(ver, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1f);
        slp.topMargin = (int) px(40);
        right.addView(buildSettings(), slp);

        LinearLayout legal = new LinearLayout(this);
        legal.setOrientation(LinearLayout.HORIZONTAL);
        legal.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
        llp.topMargin = (int) px(20);
        right.addView(legal, llp);
        TextView tm = text(getString(R.string.trademark), Ui.inter(), 14, Ui.TEXT_3);
        legal.addView(tm, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView lic = pill(getString(R.string.licenses));
        lic.setOnClickListener(v -> showLicenses());
        LinearLayout.LayoutParams lip = new LinearLayout.LayoutParams(-2, -2);
        lip.leftMargin = (int) px(16);
        legal.addView(lic, lip);

        playButton.requestFocus();
        return root;
    }

    private View buildSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) px(32), (int) px(28), (int) px(32), (int) px(30));
        box.setBackground(panelBg(Ui.PANEL, Ui.LINE, Ui.RADIUS));
        box.addView(label(getString(R.string.settings), 15, Ui.TEXT_3));

        addSegment(box, R.string.set_screens, P_SINGLE, new int[]{R.string.screens_dual, R.string.screens_single}, new int[]{0, 1}, 0);
        addSegment(box, R.string.set_renderer, P_REF, new int[]{R.string.ref_gles3, R.string.ref_gl4es, R.string.ref_gles1}, new int[]{0, 1, 2}, 0);
        addLanguageRow(box);

        TextView l = label(getString(R.string.set_controller), 13, Ui.TEXT_3);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
        llp.topMargin = (int) px(30);
        box.addView(l, llp);
        TextView reset = pill(getString(R.string.controller_reset));
        reset.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, (int) px(64));
        rlp.topMargin = (int) px(12);
        box.addView(reset, rlp);
        reset.setOnClickListener(v -> {
            prefs.edit().putBoolean(P_BINDS_DONE, false).apply();
            reset.setText(getString(R.string.controller_reset_done).toUpperCase(Locale.ROOT));
        });
        TextView layout = para(getString(R.string.controller_layout));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-1, -2);
        lp2.topMargin = (int) px(14);
        box.addView(layout, lp2);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(box);
        return sv;
    }

    private void addSegment(LinearLayout box, int labelRes, String key, int[] names, int[] values, int def) {
        TextView l = label(getString(labelRes), 13, Ui.TEXT_3);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
        llp.topMargin = (int) px(30);
        box.addView(l, llp);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, (int) px(64));
        rlp.topMargin = (int) px(12);
        box.addView(row, rlp);
        TextView[] chips = new TextView[names.length];
        int current = prefs.getInt(key, def);
        for (int i = 0; i < names.length; i++) {
            final int value = values[i];
            TextView chip = text(getString(names[i]), Ui.interMedium(), 20, Ui.TEXT_2);
            chip.setGravity(Gravity.CENTER);
            chip.setFocusable(true);
            chip.setClickable(true);
            chips[i] = chip;
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -1, 1f);
            if (i > 0) {
                clp.leftMargin = (int) px(8);
            }
            row.addView(chip, clp);
            chip.setOnClickListener(v -> {
                prefs.edit().putInt(key, value).apply();
                for (int j = 0; j < chips.length; j++) {
                    styleChip(chips[j], values[j] == value);
                }
            });
            styleChip(chip, value == current);
        }
    }

    /** Language: app UI + deck + engine menu language. Follows the system by default. */
    private void addLanguageRow(LinearLayout box) {
        TextView l = label(getString(R.string.set_language), 13, Ui.TEXT_3);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
        llp.topMargin = (int) px(30);
        box.addView(l, llp);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, (int) px(64));
        rlp.topMargin = (int) px(12);
        box.addView(row, rlp);
        final String[] tags = {"", "en", "de", "es", "fr"};
        final String[] names = {getString(R.string.lang_system), "EN", "DE", "ES", "FR"};
        android.app.LocaleManager lm = getSystemService(android.app.LocaleManager.class);
        String current = "";
        if (lm != null && !lm.getApplicationLocales().isEmpty()) {
            current = lm.getApplicationLocales().get(0).getLanguage();
        }
        for (int i = 0; i < tags.length; i++) {
            final String tag = tags[i];
            TextView chip = text(names[i], i == 0 ? Ui.interMedium() : Ui.mono(), 18, Ui.TEXT_2);
            chip.setGravity(Gravity.CENTER);
            chip.setFocusable(true);
            chip.setClickable(true);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -1, i == 0 ? 1.6f : 1f);
            if (i > 0) {
                clp.leftMargin = (int) px(8);
            }
            row.addView(chip, clp);
            styleChip(chip, tag.equals(current));
            chip.setOnClickListener(v -> {
                if (lm != null) {
                    lm.setApplicationLocales(tag.isEmpty() ? android.os.LocaleList.getEmptyLocaleList()
                            : android.os.LocaleList.forLanguageTags(tag));
                }
            });
        }
    }

    private void refreshChecks() {
        if (checkList == null) {
            return;
        }
        checkList.removeAllViews();
        List<GameData.Check> checks = GameData.check(this, rodir());
        boolean ready = true;
        for (GameData.Check c : checks) {
            if (c.required && !c.ok) {
                ready = false;
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView mark = text(c.ok ? "OK" : c.required ? "--" : "··", Ui.monoBold(), 16, c.ok ? Ui.GREEN : c.required ? Ui.RED : Ui.TEXT_3);
            row.addView(mark, new LinearLayout.LayoutParams((int) px(56), -2));
            row.addView(text(c.label + (c.required ? "" : "  (" + getString(R.string.optional) + ")"), Ui.inter(), 19,
                    c.ok ? Ui.TEXT : Ui.TEXT_2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = (int) px(10);
            checkList.addView(row, lp);
        }
        boolean access = GameData.hasStorageAccess();
        accessButton.setVisibility(access ? View.GONE : View.VISIBLE);
        playButton.setEnabled(ready);
        playButton.setAlpha(ready ? 1f : 0.45f);
        statusLine.setText(ready ? getString(R.string.ready_hint) : getString(R.string.not_ready_hint));
    }

    private void requestAccess() {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    // ------------------------------------------------------------------ launch

    static String engineLanguage() {
        switch (Locale.getDefault().getLanguage()) {
            case "de": return "german";
            case "es": return "spanish";
            case "fr": return "french";
            default: return "english";
        }
    }

    private void launch() {
        if (!GameData.ready(this, rodir())) {
            refreshChecks();
            return;
        }
        Intent i = new Intent(this, GameActivity.class);
        i.putExtra(GameActivity.EXTRA_RODIR, rodir());
        i.putExtra(GameActivity.EXTRA_BASEDIR, GameData.DEFAULT_BASEDIR);
        i.putExtra(GameActivity.EXTRA_LANG, engineLanguage());
        i.putExtra(GameActivity.EXTRA_SINGLE, prefs.getInt(P_SINGLE, 0) == 1);
        int ref = Math.max(0, Math.min(RENDERERS.length - 1, prefs.getInt(P_REF, 0)));
        i.putExtra(GameActivity.EXTRA_REF, RENDERERS[ref]);
        boolean applyBinds = !prefs.getBoolean(P_BINDS_DONE, false);
        i.putExtra(GameActivity.EXTRA_APPLY_BINDS, applyBinds);
        if (applyBinds) {
            prefs.edit().putBoolean(P_BINDS_DONE, true).apply();
        }
        startActivity(i);
    }

    // ------------------------------------------------------------------ dialogs

    private void showLicenses() {
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.licenses_intro)).append("\n\n");
        try {
            String[] files = getAssets().list("licenses");
            if (files != null) {
                for (String fn : files) {
                    sb.append("==== ").append(fn).append(" ====\n\n");
                    try (InputStream in = getAssets().open("licenses/" + fn)) {
                        sb.append(new String(in.readAllBytes(), StandardCharsets.UTF_8)).append("\n\n");
                    }
                }
            }
        } catch (Exception ignored) {
        }
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) px(40), (int) px(32), (int) px(40), (int) px(32));
        box.setBackground(panelBg(Ui.PANEL, Ui.LINE_2, Ui.RADIUS));
        box.addView(label(getString(R.string.licenses), 15, Ui.TEXT_3));
        ScrollView sv = new ScrollView(this);
        sv.setFocusable(true);
        TextView t = text(sb.toString(), Ui.mono(), 15, Ui.TEXT_2);
        t.setLineSpacing(0, 1.25f);
        sv.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1f);
        lp.topMargin = (int) px(16);
        box.addView(sv, lp);
        TextView close = pill(getString(R.string.close));
        close.setOnClickListener(v -> d.dismiss());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
        cp.topMargin = (int) px(16);
        cp.gravity = Gravity.END;
        box.addView(close, cp);
        d.setContentView(box);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(0));
            d.getWindow().setLayout((int) px(1400), (int) px(900));
        }
        d.show();
        close.requestFocus();
    }

    // ------------------------------------------------------------------ helpers

    static void drawMark(Canvas c, float cx, float cy, float r, Paint p) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(r * 0.07f);
        p.setColor(Ui.alpha(Ui.AMBER, 0.6f));
        Path hex = new Path();
        for (int i = 0; i < 6; i++) {
            double a = Math.toRadians(60 * i - 90);
            float x = cx + (float) Math.cos(a) * r, y = cy + (float) Math.sin(a) * r;
            if (i == 0) hex.moveTo(x, y);
            else hex.lineTo(x, y);
        }
        hex.close();
        c.drawPath(hex, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Ui.AMBER);
        float a = r * 0.18f, b = r * 0.58f;
        c.drawRect(cx - a, cy - b, cx + a, cy + b * 0.7f, p);
        c.drawRect(cx - b, cy - a - b * 0.15f, cx + b, cy + a - b * 0.15f, p);
        p.setColor(Ui.alpha(Ui.AMBER, 0.45f));
        c.drawRect(cx - b, cy + b * 0.85f, cx + b, cy + b * 0.95f, p);
    }

    private void styleChip(TextView chip, boolean selected) {
        StateListDrawable s = new StateListDrawable();
        int fill = selected ? Ui.alpha(Ui.AMBER, 0.14f) : Ui.BG_2;
        int line = selected ? Ui.AMBER : Ui.LINE;
        s.addState(new int[]{android.R.attr.state_focused}, panelBg(Ui.PANEL_2, Ui.TEXT, Ui.RADIUS_SM, 2));
        s.addState(new int[]{android.R.attr.state_pressed}, panelBg(Ui.PANEL_2, Ui.AMBER, Ui.RADIUS_SM, 2));
        s.addState(new int[]{}, panelBg(fill, line, Ui.RADIUS_SM, selected ? 2 : 1));
        chip.setBackground(s);
        chip.setTextColor(selected ? Ui.AMBER_2 : Ui.TEXT_2);
    }

    private TextView pill(String s) {
        TextView t = label(s, 14, Ui.TEXT);
        t.setPadding((int) px(28), (int) px(18), (int) px(28), (int) px(18));
        t.setFocusable(true);
        t.setClickable(true);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, panelBg(Ui.PANEL_2, Ui.TEXT, Ui.RADIUS_SM, 2));
        bg.addState(new int[]{android.R.attr.state_pressed}, panelBg(Ui.PANEL_2, Ui.AMBER, Ui.RADIUS_SM, 2));
        bg.addState(new int[]{}, panelBg(Ui.PANEL, Ui.LINE, Ui.RADIUS_SM, 1));
        t.setBackground(bg);
        return t;
    }

    private GradientDrawable panelBg(int fill, int line, float radius) {
        return panelBg(fill, line, radius, 1);
    }

    private GradientDrawable panelBg(int fill, int line, float radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(px(radius));
        d.setStroke(Math.max(1, (int) (stroke * Math.max(1f, f))), line);
        return d;
    }

    private TextView text(String s, android.graphics.Typeface tf, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTypeface(tf);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private TextView label(String s, float size, int color) {
        TextView t = text(s, Ui.interSemi(), size, color);
        t.setLetterSpacing(Ui.LABEL_TRACKING);
        t.setAllCaps(true);
        return t;
    }

    private TextView para(String s) {
        TextView t = text(s, Ui.inter(), 17, Ui.TEXT_3);
        t.setLineSpacing(0, 1.3f);
        return t;
    }

    private final class Backdrop extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        @Override
        public void draw(Canvas c) {
            float w = getBounds().width(), h = getBounds().height();
            c.drawColor(Ui.BG);
            p.setShader(new RadialGradient(px(160), px(110), px(950), Ui.alpha(Ui.AMBER, 0.10f), 0, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);
            // faint hazard band at the bottom edge
            Ui.stripes(c, new android.graphics.RectF(0, h - px(10), w, h), Ui.alpha(Ui.AMBER, 0.10f), p);
            Ui.scanlines(c, w, h, p);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.OPAQUE;
        }
    }
}
