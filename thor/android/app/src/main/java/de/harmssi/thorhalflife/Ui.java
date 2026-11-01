package de.harmssi.thorhalflife;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

/**
 * Thor Half-Life design system ("HEV terminal"), shared look with Thor Conquer.
 *
 * Near-black surfaces, quiet panels with 1-px lines, Inter for UI, UPPERCASE micro labels with wide
 * tracking, JetBrains Mono for numbers, 12/8 px radii, thin gradient bars. Accent: HEV amber/orange;
 * red for critical values, teal for neutral info (air, water).
 */
final class Ui {
    private Ui() {}

    // Surfaces
    static final int BG = 0xFF0A0907;
    static final int BG_2 = 0xFF0F0D0A;
    static final int PANEL = 0xFF14120E;
    static final int PANEL_2 = 0xFF1B1813;
    static final int LINE = 0xFF2A2620;
    static final int LINE_2 = 0xFF3A342B;

    // Text
    static final int TEXT = 0xFFF4F1EA;
    static final int TEXT_2 = 0xFFADA698;
    static final int TEXT_3 = 0xFF6F685C;

    // Accents
    static final int AMBER = 0xFFF5A524;   // HEV orange
    static final int AMBER_2 = 0xFFFFCB6B;
    static final int RED = 0xFFE5484D;
    static final int RED_2 = 0xFFF08A6E;
    static final int GREEN = 0xFF4CC38A;
    static final int TEAL = 0xFF4DB6C4;
    static final int TEAL_2 = 0xFF8FDCE6;

    static final float LABEL_TRACKING = 0.32f; // em
    static final float RADIUS = 12f;
    static final float RADIUS_SM = 8f;

    private static Typeface inter, interMedium, interSemi, interBold, mono, monoBold;

    static void init(Context ctx) {
        if (inter != null) {
            return;
        }
        inter = load(ctx, "fonts/Inter-Regular.ttf", Typeface.SANS_SERIF);
        interMedium = load(ctx, "fonts/Inter-Medium.ttf", inter);
        interSemi = load(ctx, "fonts/Inter-SemiBold.ttf", inter);
        interBold = load(ctx, "fonts/Inter-Bold.ttf", inter);
        mono = load(ctx, "fonts/JetBrainsMono-Regular.ttf", Typeface.MONOSPACE);
        monoBold = load(ctx, "fonts/JetBrainsMono-Bold.ttf", mono);
    }

    private static Typeface load(Context ctx, String path, Typeface fallback) {
        try {
            return Typeface.createFromAsset(ctx.getAssets(), path);
        } catch (Throwable t) {
            return fallback;
        }
    }

    static Typeface inter() { return inter; }
    static Typeface interMedium() { return interMedium; }
    static Typeface interSemi() { return interSemi; }
    static Typeface interBold() { return interBold; }
    static Typeface mono() { return mono; }
    static Typeface monoBold() { return monoBold; }

    static int alpha(int color, float a) {
        a = Math.max(0f, Math.min(1f, a));
        return (color & 0x00FFFFFF) | (Math.round(a * 255) << 24);
    }

    /** Colour for a 0..100 value: amber normally, red when low. */
    static int level(int value, int low) {
        return value <= low ? RED : AMBER;
    }

    static int level2(int value, int low) {
        return value <= low ? RED_2 : AMBER_2;
    }

    /** Text paint for an UPPERCASE micro label. */
    static Paint label(float size, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(interSemi);
        p.setTextSize(size);
        p.setLetterSpacing(LABEL_TRACKING);
        p.setColor(color);
        return p;
    }

    static Paint text(Typeface tf, float size, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(tf);
        p.setTextSize(size);
        p.setColor(color);
        return p;
    }

    /** Panel: filled rounded rect with a 1-px line. */
    static void panel(Canvas c, RectF r, float radius, int fill, int line, Paint scratch) {
        scratch.setStyle(Paint.Style.FILL);
        scratch.setShader(null);
        scratch.setColor(fill);
        c.drawRoundRect(r, radius, radius, scratch);
        scratch.setStyle(Paint.Style.STROKE);
        scratch.setStrokeWidth(1f);
        scratch.setColor(line);
        RectF in = new RectF(r.left + 0.5f, r.top + 0.5f, r.right - 0.5f, r.bottom - 0.5f);
        c.drawRoundRect(in, radius, radius, scratch);
        scratch.setStyle(Paint.Style.FILL);
    }

    /** Thin gradient progress bar (track + fill). */
    static void bar(Canvas c, RectF r, float fraction, int from, int to, Paint scratch) {
        float rad = r.height() / 2f;
        scratch.setStyle(Paint.Style.FILL);
        scratch.setShader(null);
        scratch.setColor(LINE);
        c.drawRoundRect(r, rad, rad, scratch);
        fraction = Math.max(0f, Math.min(1f, fraction));
        if (fraction > 0f) {
            RectF f = new RectF(r.left, r.top, r.left + Math.max(r.height(), r.width() * fraction), r.bottom);
            scratch.setShader(new LinearGradient(r.left, 0, r.right, 0, from, to, Shader.TileMode.CLAMP));
            c.drawRoundRect(f, rad, rad, scratch);
            scratch.setShader(null);
        }
    }

    /** Segmented HEV-style bar: n cells, filled up to fraction. */
    static void cells(Canvas c, RectF r, int n, float fraction, int on, int off, Paint scratch) {
        scratch.setStyle(Paint.Style.FILL);
        scratch.setShader(null);
        float gap = r.height() * 0.35f;
        float w = (r.width() - gap * (n - 1)) / n;
        int lit = Math.round(Math.max(0f, Math.min(1f, fraction)) * n);
        for (int i = 0; i < n; i++) {
            float x = r.left + i * (w + gap);
            scratch.setColor(i < lit ? on : off);
            c.drawRoundRect(x, r.top, x + w, r.bottom, 2f, 2f, scratch);
        }
    }

    /** Very faint scanlines (texture only, ~3 % opacity). */
    static void scanlines(Canvas c, float w, float h, Paint scratch) {
        scratch.setShader(null);
        scratch.setStyle(Paint.Style.FILL);
        scratch.setColor(0x07FFFFFF);
        for (float y = 0; y < h; y += 4f) {
            c.drawRect(0, y, w, y + 1f, scratch);
        }
    }

    /** Faint hazard stripes in a rect (warning panels). */
    static void stripes(Canvas c, RectF r, int color, Paint scratch) {
        c.save();
        c.clipRect(r);
        scratch.setShader(null);
        scratch.setStyle(Paint.Style.STROKE);
        scratch.setStrokeWidth(r.height() * 0.18f);
        scratch.setColor(color);
        float step = r.height() * 0.6f;
        for (float x = r.left - r.height(); x < r.right + r.height(); x += step) {
            c.drawLine(x, r.bottom, x + r.height(), r.top, scratch);
        }
        scratch.setStyle(Paint.Style.FILL);
        c.restore();
    }
}
