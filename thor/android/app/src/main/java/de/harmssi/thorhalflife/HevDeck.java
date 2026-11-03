package de.harmssi.thorhalflife;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * HEV companion deck for the Thor's bottom screen (display 4, 1240x1080).
 *
 * Header (health, armor, suit status + flashlight) · arsenal (slot columns 1-5, Opposing Force 1-7;
 * tap = select) ·
 * pickups · hazards + compass · location (chapter, breadcrumb map) · quick buttons.
 *
 * All data is a snapshot from the client library (cl_dll/thor_deck.cpp). Every action goes back as
 * a console command and runs through the normal client command path (like a key bind); weapon
 * selection uses the same ServerCmd("weapon_xxx") path as the HUD weapon menu.
 *
 * Layout is designed on a 1240x1080 grid and scaled to the actual view size.
 */
public class HevDeck extends View {
    // DMG_* bits from cl_dll/health.h
    static final int DMG_BURN = 1 << 3, DMG_FREEZE = 1 << 4, DMG_SHOCK = 1 << 8, DMG_DROWN = 1 << 14,
            DMG_NERVEGAS = 1 << 16, DMG_POISON = 1 << 17, DMG_RADIATION = 1 << 18, DMG_ACID = 1 << 20,
            DMG_SLOWBURN = 1 << 21, DMG_SLOWFREEZE = 1 << 22;
    static final int MIN_SLOTS = 5, MIN_ROWS = 4;

    static final class Weapon {
        int id, slot, pos, clip, ammo1, max1, ammo2, max2;
        boolean selectable;
        String name;
        final RectF bounds = new RectF();
    }

    static final class Feed {
        int seq, count;
        char kind;
        String name;
        float age;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final String clientLib;
    private final String game;
    private final HudSprites sprites;
    // arsenal grid: columns = weapon slots of the game (snapshot "slots"), rows grow with the
    // fullest slot (Opposing Force: 5 throwables) and never shrink during a session
    private int slots = MIN_SLOTS, rows = MIN_ROWS;
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF iconDst = new RectF();

    // --- snapshot
    private boolean connected, inGame, deckMode;
    private String map = "";
    private int hp, ap, suit, dead, flOn, flBat, dmg, geiger, train, water, ground, cur, sel;
    private float air = -1, fallV, speed, yaw, t;
    private final float[] pain = new float[4];
    private final float[] pos = new float[3];
    private final List<Weapon> weapons = new ArrayList<>();
    private final List<Feed> feed = new ArrayList<>();
    private float lastT = -1;
    private long lastTChange;
    private boolean paused;
    private int newestFeed = -1;
    private long feedFlashAt;

    // breadcrumb trail (per map)
    private final float[] trail = new float[2 * 1200];
    private int trailN;
    private String trailMap = "";
    private String previousMap = ""; // last real map before this one (Blue Shift chapter names)
    private long trailWaitUntil;

    // animation
    private float shownHp, shownAp, shownFl;
    private long hpHitAt, apHitAt;
    private int prevHp = -1, prevAp = -1;

    // --- layout (design units)
    private float s = 1f;
    private final RectF pHealth = new RectF(), pArmor = new RectF(), pStatus = new RectF();
    private final RectF pArsenal = new RectF(), pFeed = new RectF(), pHazard = new RectF(), pLoc = new RectF();
    private final RectF[] buttons = {new RectF(), new RectF(), new RectF(), new RectF(), new RectF()};
    static final int B_FLASH = 0, B_USE = 1, B_SAVE = 2, B_LOAD = 3, B_MENU = 4;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint micro, microDim, monoHuge, monoBig, monoMid, monoSmall, body, bodyDim, bodySemi, chipText;

    // --- touch
    private int downButton = -1;
    private Weapon downWeapon;
    private long pressAt;
    private int pressedButton = -1;
    private int pressedWeapon = -1;
    private long loadArmedAt;
    private String toast;
    private long toastAt;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            poll();
            invalidate();
            handler.postDelayed(this, 50);
        }
    };

    public HevDeck(Context context, String clientLib, String game, String rodir) {
        super(context);
        this.clientLib = clientLib;
        this.game = game == null ? "valve" : game;
        this.sprites = new HudSprites(rodir, this.game, this::postInvalidate);
        Ui.init(context);
        setBackgroundColor(Ui.BG);
        setHapticFeedbackEnabled(true);
    }

    public void start() {
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    public void stop() {
        handler.removeCallbacks(tick);
    }

    public void release() {
        stop();
        sprites.shutdown();
    }

    // Opposing Force: PCV + night vision; Blue Shift: security guard armor vest
    private int armorLabel() {
        return game.equals("gearbox") ? R.string.deck_armor_pcv : game.equals("bshift") ? R.string.deck_armor_vest : R.string.deck_armor;
    }

    private int lightLabel() {
        return game.equals("gearbox") ? R.string.deck_nightvision : R.string.deck_flashlight;
    }

    private int noSuitLabel() {
        return game.equals("gearbox") ? R.string.deck_no_suit_hint_pcv : game.equals("bshift") ? R.string.deck_no_suit_hint_vest : R.string.deck_no_suit_hint;
    }

    private String str(int id) {
        return getContext().getString(id);
    }

    // ------------------------------------------------------------------ data

    private void poll() {
        String json;
        try {
            json = NativeBridge.snapshot(clientLib);
        } catch (Throwable e) {
            json = null;
        }
        connected = json != null;
        if (!connected) {
            inGame = false;
            return;
        }
        try {
            JSONObject o = new JSONObject(json);
            map = o.optString("map", "");
            deckMode = o.optInt("deck") != 0;
            t = (float) o.optDouble("t", 0);
            hp = o.optInt("hp");
            ap = o.optInt("ap");
            suit = o.optInt("suit");
            dead = o.optInt("dead");
            flOn = o.optInt("fl");
            flBat = o.optInt("flbat");
            dmg = o.optInt("dmg");
            geiger = o.optInt("geiger");
            train = o.optInt("train");
            water = o.optInt("water");
            air = (float) o.optDouble("air", -1);
            ground = o.optInt("ground", 1);
            fallV = (float) o.optDouble("fallv", 0);
            speed = (float) o.optDouble("speed", 0);
            yaw = (float) o.optDouble("yaw", 0);
            slots = Math.max(MIN_SLOTS, Math.min(10, o.optInt("slots", MIN_SLOTS)));
            cur = o.optInt("cur");
            sel = o.optInt("sel");
            JSONArray pa = o.optJSONArray("pain");
            for (int i = 0; i < 4; i++) {
                pain[i] = pa != null ? (float) pa.optDouble(i, 0) : 0;
            }
            JSONArray po = o.optJSONArray("pos");
            for (int i = 0; i < 3; i++) {
                pos[i] = po != null ? (float) po.optDouble(i, 0) : 0;
            }
            // keep the last drawn tile bounds per weapon: the list is rebuilt on every poll and a tap
            // between a poll and the next draw must still hit its tile
            java.util.Map<Integer, RectF> oldBounds = new java.util.HashMap<>();
            for (Weapon ow : weapons) {
                oldBounds.put(ow.id, ow.bounds);
            }
            weapons.clear();
            JSONArray w = o.optJSONArray("w");
            if (w != null) {
                for (int i = 0; i < w.length(); i++) {
                    JSONArray a = w.getJSONArray(i);
                    Weapon x = new Weapon();
                    x.id = a.getInt(0);
                    x.name = a.getString(1);
                    x.slot = a.getInt(2);
                    x.pos = a.getInt(3);
                    x.clip = a.getInt(4);
                    x.ammo1 = a.getInt(5);
                    x.max1 = a.getInt(6);
                    x.ammo2 = a.getInt(7);
                    x.max2 = a.getInt(8);
                    x.selectable = a.getInt(9) != 0;
                    RectF ob = oldBounds.get(x.id);
                    if (ob != null) {
                        x.bounds.set(ob);
                    }
                    weapons.add(x);
                }
                int[] perSlot = new int[16];
                for (Weapon x : weapons) {
                    if (x.slot >= 0 && x.slot < perSlot.length) {
                        rows = Math.max(rows, Math.min(6, ++perSlot[x.slot]));
                    }
                }
            }
            feed.clear();
            JSONArray f = o.optJSONArray("feed");
            if (f != null) {
                for (int i = 0; i < f.length(); i++) {
                    JSONArray a = f.getJSONArray(i);
                    Feed x = new Feed();
                    x.seq = a.getInt(0);
                    String k = a.getString(1);
                    x.kind = k.isEmpty() ? 'i' : k.charAt(0);
                    x.name = a.getString(2);
                    x.count = a.getInt(3);
                    x.age = (float) a.getDouble(4);
                    feed.add(x);
                }
            }
        } catch (Exception e) {
            return;
        }
        inGame = !map.isEmpty();
        long now = SystemClock.uptimeMillis();
        if (t != lastT) {
            lastT = t;
            lastTChange = now;
        }
        paused = inGame && now - lastTChange > 400;

        if (!feed.isEmpty() && feed.get(0).seq != newestFeed) {
            if (newestFeed >= 0) {
                feedFlashAt = now;
            }
            newestFeed = feed.get(0).seq;
        }
        if (prevHp >= 0 && hp < prevHp) {
            hpHitAt = now;
        }
        if (prevAp >= 0 && ap < prevAp) {
            apHitAt = now;
        }
        prevHp = hp;
        prevAp = ap;

        // breadcrumb trail
        if (!map.equals(trailMap)) {
            if (!trailMap.isEmpty()) {
                previousMap = trailMap;
            }
            trailMap = map;
            trailN = 0;
            trailWaitUntil = now + 1500;
        }
        if (inGame && dead == 0 && !paused && now > trailWaitUntil) {
            boolean add = trailN == 0;
            if (!add) {
                float dx = pos[0] - trail[2 * (trailN - 1)], dy = pos[1] - trail[2 * (trailN - 1) + 1];
                add = dx * dx + dy * dy > 48 * 48;
                if (dx * dx + dy * dy > 2000 * 2000) {
                    trailN = 0; // teleport / level transition: start a new trail
                }
            }
            if (add) {
                if (trailN == trail.length / 2) {
                    System.arraycopy(trail, 2, trail, 0, trail.length - 2);
                    trailN--;
                }
                trail[2 * trailN] = pos[0];
                trail[2 * trailN + 1] = pos[1];
                trailN++;
            }
        }
    }

    private void send(String cmd) {
        try {
            NativeBridge.command(clientLib, cmd);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        s = Math.min(w / 1240f, h / 1080f);
        float ox = (w - 1240 * s) / 2f, oy = (h - 1080 * s) / 2f;
        set(pHealth, ox, oy, 24, 24, 400, 212);
        set(pArmor, ox, oy, 440, 24, 400, 212);
        set(pStatus, ox, oy, 856, 24, 360, 212);
        set(pArsenal, ox, oy, 24, 252, 1192, 448);
        set(pFeed, ox, oy, 24, 716, 400, 228);
        set(pHazard, ox, oy, 440, 716, 400, 228);
        set(pLoc, ox, oy, 856, 716, 360, 228);
        float bw = (1192 - 4 * 12) / 5f;
        for (int i = 0; i < 5; i++) {
            set(buttons[i], ox, oy, 24 + i * (bw + 12), 960, bw, 96);
        }
        micro = Ui.label(13 * s, Ui.TEXT_3);
        microDim = Ui.label(11 * s, Ui.TEXT_3);
        monoHuge = Ui.text(Ui.monoBold(), 96 * s, Ui.AMBER);
        monoBig = Ui.text(Ui.monoBold(), 30 * s, Ui.TEXT);
        monoMid = Ui.text(Ui.mono(), 20 * s, Ui.TEXT_2);
        monoSmall = Ui.text(Ui.mono(), 15 * s, Ui.TEXT_3);
        body = Ui.text(Ui.interMedium(), 20 * s, Ui.TEXT);
        bodyDim = Ui.text(Ui.inter(), 17 * s, Ui.TEXT_3);
        bodySemi = Ui.text(Ui.interSemi(), 19 * s, Ui.TEXT);
        chipText = Ui.label(14 * s, Ui.TEXT);
    }

    private void set(RectF r, float ox, float oy, float x, float y, float w, float h) {
        r.set(ox + x * s, oy + y * s, ox + (x + w) * s, oy + (y + h) * s);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        if (micro == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        float k = 0.25f;
        shownHp += (hp - shownHp) * k;
        shownAp += (ap - shownAp) * k;
        shownFl += (flBat - shownFl) * k;

        c.drawColor(Ui.BG);
        Ui.scanlines(c, getWidth(), getHeight(), paint);

        // dead: the server strips the suit bit, show health 0 instead of "no suit" dashes
        boolean live = inGame && (suit != 0 || dead != 0);
        drawVital(c, pHealth, str(R.string.deck_health), live ? Math.max(0, hp) : -1, shownHp, 25, hpHitAt, now, true);
        drawVital(c, pArmor, str(armorLabel()), live ? ap : -1, shownAp, 0, apHitAt, now, false);
        drawStatus(c, now);
        drawArsenal(c, now);
        drawFeed(c, now);
        drawHazards(c, now);
        drawLocation(c);
        drawButtons(c, now);

        if (toast != null && now - toastAt < 1600) {
            float a = Math.min(1f, (1600 - (now - toastAt)) / 300f);
            float tw = chipText.measureText(toast) + 48 * s;
            RectF r = new RectF(getWidth() / 2f - tw / 2, pArsenal.bottom - 64 * s, getWidth() / 2f + tw / 2, pArsenal.bottom - 20 * s);
            paint.setColor(Ui.alpha(Ui.PANEL_2, 0.96f * a));
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f * s);
            paint.setColor(Ui.alpha(Ui.AMBER, a));
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, paint);
            paint.setStyle(Paint.Style.FILL);
            chipText.setColor(Ui.alpha(Ui.AMBER_2, a));
            c.drawText(toast, r.centerX() - chipText.measureText(toast) / 2, r.centerY() + 5 * s, chipText);
        }
    }

    private void label(Canvas c, String text, float x, float y) {
        c.drawText(text.toUpperCase(), x, y, micro);
    }

    private void drawVital(Canvas c, RectF r, String title, int value, float shown, int low, long hitAt, long now, boolean health) {
        boolean flash = now - hitAt < 450;
        int accent = value >= 0 && value <= low && health ? Ui.RED : Ui.AMBER;
        int accent2 = value >= 0 && value <= low && health ? Ui.RED_2 : Ui.AMBER_2;
        int line = flash ? Ui.alpha(Ui.RED, 0.9f) : Ui.LINE;
        Ui.panel(c, r, Ui.RADIUS * s, Ui.PANEL, line, paint);
        if (value >= 0 && value <= low && health) {
            // critical: slow pulse
            float pulse = 0.5f + 0.5f * (float) Math.sin(now / 220.0);
            paint.setColor(Ui.alpha(Ui.RED, 0.06f + 0.08f * pulse));
            c.drawRoundRect(r, Ui.RADIUS * s, Ui.RADIUS * s, paint);
        }
        float x = r.left + 24 * s;
        label(c, title, x, r.top + 36 * s);
        // small glyph: cross (health) or shield (armor)
        drawGlyph(c, health, r.right - 40 * s, r.top + 31 * s, 11 * s, value >= 0 ? accent : Ui.TEXT_3);

        String num = value < 0 ? "---" : String.valueOf(Math.round(shown));
        monoHuge.setColor(value < 0 ? Ui.TEXT_3 : accent);
        c.drawText(num, x - 4 * s, r.top + 136 * s, monoHuge);
        float nw = monoHuge.measureText(num);
        monoMid.setColor(Ui.TEXT_3);
        c.drawText("/100", x + nw + 6 * s, r.top + 136 * s, monoMid);

        RectF bar = new RectF(x, r.bottom - 50 * s, r.right - 24 * s, r.bottom - 34 * s);
        Ui.cells(c, bar, 20, value < 0 ? 0 : shown / 100f, accent2, Ui.LINE, paint);
        microDim.setColor(Ui.TEXT_3);
        String sub = value < 0 ? "" : health ? (value <= low ? str(R.string.status_critical) : "") : (value == 0 ? str(R.string.deck_armor_none) : "");
        if (!sub.isEmpty()) {
            microDim.setColor(health ? Ui.RED_2 : Ui.TEXT_3);
            c.drawText(sub.toUpperCase(), x, r.bottom - 14 * s, microDim);
        }
    }

    private void drawGlyph(Canvas c, boolean cross, float cx, float cy, float rr, int color) {
        paint.setColor(color);
        if (cross) {
            float t = rr * 0.36f;
            c.drawRect(cx - t, cy - rr, cx + t, cy + rr, paint);
            c.drawRect(cx - rr, cy - t, cx + rr, cy + t, paint);
        } else {
            Path p = new Path();
            p.moveTo(cx, cy - rr * 1.05f);
            p.lineTo(cx + rr, cy - rr * 0.6f);
            p.lineTo(cx + rr * 0.85f, cy + rr * 0.3f);
            p.lineTo(cx, cy + rr * 1.1f);
            p.lineTo(cx - rr * 0.85f, cy + rr * 0.3f);
            p.lineTo(cx - rr, cy - rr * 0.6f);
            p.close();
            c.drawPath(p, paint);
        }
    }

    private void drawStatus(Canvas c, long now) {
        RectF r = pStatus;
        Ui.panel(c, r, Ui.RADIUS * s, Ui.PANEL, Ui.LINE, paint);
        float x = r.left + 24 * s;
        label(c, str(R.string.deck_suit), x, r.top + 36 * s);

        String status;
        int color;
        if (!connected || !inGame) {
            status = str(R.string.status_standby);
            color = Ui.TEXT_3;
        } else if (dead != 0) {
            status = str(R.string.status_flatline);
            color = Ui.RED;
        } else if (paused) {
            status = str(R.string.status_paused);
            color = Ui.TEAL;
        } else if (suit == 0) {
            status = str(R.string.status_nosuit);
            color = Ui.TEXT_2;
        } else if (hp <= 25) {
            status = str(R.string.status_critical);
            color = Ui.RED;
        } else if (hp <= 50 || dmg != 0) {
            status = str(R.string.status_warning);
            color = Ui.AMBER;
        } else {
            status = str(R.string.status_nominal);
            color = Ui.GREEN;
        }
        // status chip
        float cw = chipText.measureText(status.toUpperCase()) + 44 * s;
        RectF chip = new RectF(x, r.top + 56 * s, x + cw, r.top + 94 * s);
        paint.setColor(Ui.alpha(color, 0.14f));
        c.drawRoundRect(chip, chip.height() / 2, chip.height() / 2, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f);
        paint.setColor(Ui.alpha(color, 0.6f));
        c.drawRoundRect(chip, chip.height() / 2, chip.height() / 2, paint);
        paint.setStyle(Paint.Style.FILL);
        float blink = color == Ui.RED ? 0.4f + 0.6f * (0.5f + 0.5f * (float) Math.sin(now / 160.0)) : 1f;
        paint.setColor(Ui.alpha(color, blink));
        c.drawCircle(chip.left + 18 * s, chip.centerY(), 5 * s, paint);
        chipText.setColor(color);
        c.drawText(status.toUpperCase(), chip.left + 32 * s, chip.centerY() + 5 * s, chipText);

        // speed (right of chip)
        if (inGame && dead == 0) {
            monoSmall.setColor(Ui.TEXT_3);
            String sp = Math.round(speed) + " u/s";
            c.drawText(sp, r.right - 24 * s - monoSmall.measureText(sp), chip.centerY() + 5 * s, monoSmall);
        }

        // flashlight
        float fy = r.top + 134 * s;
        label(c, str(lightLabel()), x, fy);
        String st = flOn != 0 ? str(R.string.deck_on) : str(R.string.deck_off);
        chipText.setColor(flOn != 0 ? Ui.AMBER_2 : Ui.TEXT_3);
        c.drawText(st.toUpperCase(), r.right - 24 * s - chipText.measureText(st.toUpperCase()), fy, chipText);
        RectF bar = new RectF(x, fy + 20 * s, r.right - 24 * s - 70 * s, fy + 30 * s);
        boolean lowFl = flBat <= 20;
        Ui.bar(c, bar, inGame && suit != 0 ? shownFl / 100f : 0, lowFl ? Ui.RED : Ui.AMBER, lowFl ? Ui.RED_2 : Ui.AMBER_2, paint);
        monoSmall.setColor(lowFl ? Ui.RED_2 : Ui.TEXT_2);
        String pct = inGame && suit != 0 ? flBat + "%" : "--";
        c.drawText(pct, r.right - 24 * s - monoSmall.measureText(pct), fy + 31 * s, monoSmall);
        if (flOn != 0) {
            // beam glyph
            paint.setColor(Ui.alpha(Ui.AMBER, 0.25f));
            Path p = new Path();
            float bx = x + micro.measureText(str(lightLabel()).toUpperCase()) + 18 * s;
            p.moveTo(bx, fy - 6 * s);
            p.lineTo(bx + 28 * s, fy - 16 * s);
            p.lineTo(bx + 28 * s, fy + 4 * s);
            p.close();
            c.drawPath(p, paint);
        }
    }

    private void drawArsenal(Canvas c, long now) {
        RectF r = pArsenal;
        Ui.panel(c, r, Ui.RADIUS * s, Ui.PANEL, Ui.LINE, paint);
        float x0 = r.left + 24 * s;
        label(c, str(R.string.deck_arsenal), x0, r.top + 36 * s);
        Weapon curW = null;
        for (Weapon w : weapons) {
            if (w.id == cur) {
                curW = w;
            }
        }
        if (curW != null) {
            String n = Names.weapon(curW.name).toUpperCase();
            chipText.setColor(Ui.AMBER_2);
            c.drawText(n, r.right - 24 * s - chipText.measureText(n), r.top + 36 * s, chipText);
        }

        float colGap = 12 * s;
        float left = r.left + 16 * s, right = r.right - 16 * s;
        float cw = (right - left - colGap * (slots - 1)) / slots;
        float top = r.top + 56 * s;
        float bottom = r.bottom - 16 * s;
        float rowGap = 8 * s;
        float th = (bottom - top - 26 * s - rowGap * (rows - 1)) / rows;

        for (int col = 0; col < slots; col++) {
            float cx = left + col * (cw + colGap);
            monoSmall.setColor(Ui.TEXT_3);
            c.drawText(String.valueOf(col + 1), cx + 4 * s, top + 14 * s, monoSmall);
            paint.setColor(Ui.LINE);
            c.drawRect(cx + 22 * s, top + 8 * s, cx + cw, top + 9 * s, paint);
            int row = 0;
            for (Weapon w : weapons) {
                if (w.slot != col) {
                    continue;
                }
                if (row >= rows) {
                    w.bounds.setEmpty();
                    continue;
                }
                float ty = top + 26 * s + row * (th + rowGap);
                w.bounds.set(cx, ty, cx + cw, ty + th);
                drawWeapon(c, w, now);
                row++;
            }
            for (; row < rows; row++) {
                float ty = top + 26 * s + row * (th + rowGap);
                RectF e = new RectF(cx, ty, cx + cw, ty + th);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1f);
                paint.setColor(0x10FFFFFF);
                c.drawRoundRect(e, Ui.RADIUS_SM * s, Ui.RADIUS_SM * s, paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }

        if (!inGame || weapons.isEmpty() || dead != 0) {
            String msg = !connected || !inGame ? str(R.string.deck_standby_hint)
                    : dead != 0 ? str(R.string.deck_dead_hint)
                    : suit == 0 ? str(noSuitLabel()) : str(R.string.deck_no_weapons);
            body.setColor(Ui.TEXT_2);
            float mw = body.measureText(msg);
            RectF box = new RectF(r.centerX() - mw / 2 - 28 * s, r.centerY() - 30 * s, r.centerX() + mw / 2 + 28 * s, r.centerY() + 30 * s);
            Ui.panel(c, box, Ui.RADIUS_SM * s, Ui.alpha(Ui.BG_2, 0.94f), Ui.LINE_2, paint);
            c.drawText(msg, r.centerX() - mw / 2, r.centerY() + 7 * s, body);
        }
    }

    private void drawWeapon(Canvas c, Weapon w, long now) {
        RectF b = w.bounds;
        boolean active = w.id == cur;
        boolean pending = w.id == sel && !active;
        boolean pressed = pressedWeapon == w.id && now - pressAt < 220;
        int fill = active ? Ui.alpha(Ui.AMBER, 0.13f) : pressed ? Ui.PANEL_2 : Ui.BG_2;
        int line = active ? Ui.AMBER : Ui.LINE;
        Ui.panel(c, b, Ui.RADIUS_SM * s, fill, line, paint);
        if (active) {
            paint.setColor(Ui.AMBER);
            c.drawRoundRect(b.left, b.top + 10 * s, b.left + 4 * s, b.bottom - 10 * s, 2 * s, 2 * s, paint);
        }
        if (pending) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f * s);
            paint.setColor(Ui.AMBER_2);
            paint.setPathEffect(new DashPathEffect(new float[]{6 * s, 5 * s}, 0));
            c.drawRoundRect(b, Ui.RADIUS_SM * s, Ui.RADIUS_SM * s, paint);
            paint.setPathEffect(null);
            paint.setStyle(Paint.Style.FILL);
        }
        float x = b.left + 14 * s;
        // weapon silhouette from the user's own HUD sprites (if available)
        android.graphics.Bitmap icon = sprites.weapon(w.name);
        if (icon != null) {
            float maxW = b.width() * 0.5f, maxH = b.height() - 48 * s;
            float sc = Math.min(maxW / icon.getWidth(), maxH / icon.getHeight());
            float iw = icon.getWidth() * sc, ih = icon.getHeight() * sc;
            float ir = b.right - 10 * s, ib = b.bottom - 12 * s;
            iconDst.set(ir - iw, ib - ih, ir, ib);
            int tint = active ? Ui.AMBER : w.selectable ? Ui.alpha(Ui.TEXT_2, 0.55f) : Ui.alpha(Ui.TEXT_3, 0.35f);
            iconPaint.setColorFilter(new android.graphics.PorterDuffColorFilter(tint, android.graphics.PorterDuff.Mode.SRC_IN));
            c.drawBitmap(icon, null, iconDst, iconPaint);
        }
        String name = Names.weapon(w.name);
        bodySemi.setColor(!w.selectable ? Ui.TEXT_3 : active ? Ui.AMBER_2 : Ui.TEXT);
        bodySemi.setTextSize(17 * s);
        c.drawText(ellipsize(name, bodySemi, b.width() - 28 * s), x, b.top + 26 * s, bodySemi);

        // ammo line
        float ay = b.bottom - 16 * s;
        if (w.max1 == -1 || w.ammo1 < 0) {
            if (icon == null) {
                c.drawText(str(R.string.deck_melee).toUpperCase(), x, ay, microDim);
            }
        } else {
            int total = Math.max(0, w.clip) + Math.max(0, w.ammo1);
            int col = total == 0 ? Ui.RED_2 : active ? Ui.TEXT : Ui.TEXT_2;
            monoBig.setTextSize(24 * s);
            monoBig.setColor(col);
            String main = w.clip >= 0 ? String.valueOf(w.clip) : String.valueOf(w.ammo1);
            c.drawText(main, x, ay, monoBig);
            float mx = x + monoBig.measureText(main) + 6 * s;
            if (w.clip >= 0) {
                monoSmall.setColor(Ui.TEXT_3);
                String res = "/ " + w.ammo1;
                c.drawText(res, mx, ay, monoSmall);
                mx += monoSmall.measureText(res) + 8 * s;
            }
            if (w.ammo2 >= 0) {
                String sec = "+" + w.ammo2;
                monoSmall.setColor(w.ammo2 > 0 ? Ui.AMBER_2 : Ui.TEXT_3);
                c.drawText(sec, mx, ay, monoSmall);
            }
            // reserve bar
            if (w.max1 > 0) {
                RectF bar = new RectF(x, b.bottom - 8 * s, b.right - 14 * s, b.bottom - 5 * s);
                Ui.bar(c, bar, w.ammo1 / (float) w.max1, Ui.alpha(Ui.AMBER, 0.6f), Ui.alpha(Ui.AMBER_2, 0.7f), paint);
            }
        }
        if (!w.selectable) {
            microDim.setColor(Ui.RED_2);
            String e = str(R.string.deck_empty).toUpperCase();
            c.drawText(e, b.right - 12 * s - microDim.measureText(e), b.top + 25 * s, microDim);
            microDim.setColor(Ui.TEXT_3);
        }
    }

    private static String ellipsize(String t, Paint p, float max) {
        if (p.measureText(t) <= max) {
            return t;
        }
        while (t.length() > 1 && p.measureText(t + "…") > max) {
            t = t.substring(0, t.length() - 1);
        }
        return t + "…";
    }

    private void drawFeed(Canvas c, long now) {
        RectF r = pFeed;
        Ui.panel(c, r, Ui.RADIUS * s, Ui.PANEL, Ui.LINE, paint);
        float x = r.left + 24 * s;
        label(c, str(R.string.deck_pickups), x, r.top + 36 * s);
        if (feed.isEmpty() || !inGame) {
            c.drawText(str(R.string.deck_no_pickups), x, r.top + 80 * s, bodyDim);
            return;
        }
        float y = r.top + 58 * s;
        float rowH = 32 * s;
        for (int i = 0; i < feed.size() && i < 5; i++) {
            Feed f = feed.get(i);
            float a = f.age < 20 ? 1f : Math.max(0.35f, 1f - (f.age - 20) / 60f);
            boolean fresh = i == 0 && now - feedFlashAt < 900;
            if (fresh) {
                paint.setColor(Ui.alpha(Ui.AMBER, 0.12f * (1f - (now - feedFlashAt) / 900f)));
                c.drawRoundRect(r.left + 12 * s, y, r.right - 12 * s, y + rowH - 4 * s, 6 * s, 6 * s, paint);
            }
            String kind = f.kind == 'a' ? str(R.string.kind_ammo) : f.kind == 'w' ? str(R.string.kind_weapon) : str(R.string.kind_item);
            int kc = f.kind == 'w' ? Ui.AMBER : f.kind == 'a' ? Ui.TEXT_2 : Ui.TEAL;
            microDim.setColor(Ui.alpha(kc, a));
            c.drawText(kind.toUpperCase(), x, y + 20 * s, microDim);
            body.setTextSize(18 * s);
            body.setColor(Ui.alpha(Ui.TEXT, a));
            String n = Names.pickup(f.kind, f.name);
            c.drawText(ellipsize(n, body, r.width() - 190 * s), x + 92 * s, y + 21 * s, body);
            body.setTextSize(20 * s);
            if (f.count > 0) {
                monoMid.setColor(Ui.alpha(Ui.AMBER_2, a));
                String cnt = "+" + f.count;
                c.drawText(cnt, r.right - 24 * s - monoMid.measureText(cnt), y + 21 * s, monoMid);
            }
            y += rowH;
        }
    }

    private void drawHazards(Canvas c, long now) {
        RectF r = pHazard;
        List<String> active = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        boolean rad = (dmg & DMG_RADIATION) != 0 || (geiger > 0 && geiger < 1000);
        if (inGame) {
            if (rad) { active.add(str(R.string.hz_radiation)); colors.add(Ui.AMBER); }
            if ((dmg & (DMG_BURN | DMG_SLOWBURN)) != 0) { active.add(str(R.string.hz_fire)); colors.add(Ui.RED); }
            if ((dmg & DMG_SHOCK) != 0) { active.add(str(R.string.hz_shock)); colors.add(Ui.TEAL_2); }
            if ((dmg & (DMG_POISON | DMG_NERVEGAS)) != 0) { active.add(str(R.string.hz_toxic)); colors.add(Ui.GREEN); }
            if ((dmg & DMG_ACID) != 0) { active.add(str(R.string.hz_acid)); colors.add(Ui.GREEN); }
            if ((dmg & (DMG_FREEZE | DMG_SLOWFREEZE)) != 0) { active.add(str(R.string.hz_freeze)); colors.add(Ui.TEAL_2); }
            if ((dmg & DMG_DROWN) != 0) { active.add(str(R.string.hz_drown)); colors.add(Ui.RED); }
            if (ground == 0 && fallV > 580) { active.add(str(R.string.hz_fall)); colors.add(fallV > 1024 ? Ui.RED : Ui.AMBER); }
            if (train != 0) { active.add(str(R.string.hz_train)); colors.add(Ui.TEXT_2); }
        }
        boolean warn = !active.isEmpty();
        Ui.panel(c, r, Ui.RADIUS * s, Ui.PANEL, warn ? Ui.alpha(Ui.AMBER, 0.55f) : Ui.LINE, paint);
        if (warn) {
            Ui.stripes(c, new RectF(r.right - 120 * s, r.top + 1, r.right - 1, r.top + 10 * s), Ui.alpha(Ui.AMBER, 0.25f), paint);
        }
        float x = r.left + 24 * s;
        label(c, str(R.string.deck_hazards), x, r.top + 36 * s);

        float y = r.top + 52 * s;
        // geiger meter
        float g = geiger > 0 && geiger < 1000 ? (1000 - geiger) / 1000f : 0f;
        microDim.setColor(Ui.TEXT_3);
        c.drawText("GEIGER", x, y + 14 * s, microDim);
        RectF gb = new RectF(x + 76 * s, y + 6 * s, r.right - 24 * s, y + 14 * s);
        Ui.cells(c, gb, 16, inGame ? g : 0, g > 0.6f ? Ui.RED_2 : Ui.AMBER, Ui.LINE, paint);
        y += 28 * s;
        // air meter (only under water)
        if (inGame && air >= 0) {
            microDim.setColor(Ui.TEAL_2);
            c.drawText(str(R.string.hz_air).toUpperCase(), x, y + 14 * s, microDim);
            RectF ab = new RectF(x + 76 * s, y + 6 * s, r.right - 84 * s, y + 14 * s);
            float af = Math.max(0f, air) / 12f;
            Ui.bar(c, ab, af, af < 0.34f ? Ui.RED : Ui.TEAL, af < 0.34f ? Ui.RED_2 : Ui.TEAL_2, paint);
            monoSmall.setColor(af < 0.34f ? Ui.RED_2 : Ui.TEAL_2);
            String as = String.format(java.util.Locale.ROOT, "%.0fs", Math.max(0f, air));
            c.drawText(as, r.right - 24 * s - monoSmall.measureText(as), y + 15 * s, monoSmall);
        } else {
            microDim.setColor(Ui.TEXT_3);
            c.drawText(str(R.string.hz_air).toUpperCase(), x, y + 14 * s, microDim);
            monoSmall.setColor(inGame && water > 0 ? Ui.TEAL_2 : Ui.TEXT_3);
            String ws = !inGame ? "--" : water > 0 ? str(R.string.hz_water) : str(R.string.hz_air_ok);
            c.drawText(ws, x + 76 * s, y + 15 * s, monoSmall);
        }
        y += 30 * s;
        // active hazard chips
        if (!warn) {
            bodyDim.setTextSize(16 * s);
            c.drawText(inGame ? str(R.string.deck_no_hazards) : "--", x, y + 22 * s, bodyDim);
            bodyDim.setTextSize(17 * s);
        } else {
            float cx = x;
            float cy = y + 2 * s;
            for (int i = 0; i < active.size(); i++) {
                String t = active.get(i).toUpperCase();
                int col = colors.get(i);
                float w = chipText.measureText(t) + 28 * s;
                if (cx + w > r.right - 20 * s) {
                    cx = x;
                    cy += 36 * s;
                }
                RectF chip = new RectF(cx, cy, cx + w, cy + 28 * s);
                float pulse = 0.6f + 0.4f * (0.5f + 0.5f * (float) Math.sin(now / 200.0 + i));
                paint.setColor(Ui.alpha(col, 0.16f * pulse + 0.06f));
                c.drawRoundRect(chip, 6 * s, 6 * s, paint);
                chipText.setColor(col);
                c.drawText(t, chip.left + 14 * s, chip.centerY() + 5 * s, chipText);
                cx += w + 8 * s;
            }
        }
        drawCompass(c, new RectF(r.left + 16 * s, r.bottom - 50 * s, r.right - 16 * s, r.bottom - 14 * s));
    }

    /** Horizontal compass tape; heading 0 = +Y (north on the map view), damage directions as red ticks. */
    private void drawCompass(Canvas c, RectF r) {
        paint.setColor(Ui.BG_2);
        c.drawRoundRect(r, 6 * s, 6 * s, paint);
        float heading = inGame ? ((90f - yaw) % 360f + 360f) % 360f : 0f;
        float span = 120f; // degrees visible
        float pxPerDeg = r.width() / span;
        c.save();
        c.clipRect(r);
        String[] card = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        for (int d = -180; d <= 540; d += 15) {
            float rel = d - heading;
            while (rel < -180) rel += 360;
            while (rel > 180) rel -= 360;
            if (Math.abs(rel) > span / 2 + 10) {
                continue;
            }
            int dd = ((d % 360) + 360) % 360;
            float px = r.centerX() + rel * pxPerDeg;
            boolean major = dd % 45 == 0;
            paint.setColor(major ? Ui.TEXT_2 : Ui.LINE_2);
            c.drawRect(px - 0.5f * s, r.bottom - (major ? 12 : 7) * s, px + 0.5f * s, r.bottom - 3 * s, paint);
            if (major) {
                String t = card[dd / 45];
                microDim.setColor(dd == 0 ? Ui.AMBER : Ui.TEXT_2);
                c.drawText(t, px - microDim.measureText(t) / 2 + 2 * s, r.top + 15 * s, microDim);
            }
        }
        c.restore();
        // centre marker + heading value
        paint.setColor(Ui.AMBER);
        Path p = new Path();
        p.moveTo(r.centerX() - 6 * s, r.top);
        p.lineTo(r.centerX() + 6 * s, r.top);
        p.lineTo(r.centerX(), r.top + 7 * s);
        p.close();
        c.drawPath(p, paint);
        // damage direction (front/right/rear/left) on the edges
        if (inGame) {
            float[] pa = pain;
            if (pa[1] > 0) { paint.setColor(Ui.alpha(Ui.RED, Math.min(1f, pa[1]))); c.drawRect(r.right - 6 * s, r.top, r.right, r.bottom, paint); }
            if (pa[3] > 0) { paint.setColor(Ui.alpha(Ui.RED, Math.min(1f, pa[3]))); c.drawRect(r.left, r.top, r.left + 6 * s, r.bottom, paint); }
            if (pa[0] > 0) { paint.setColor(Ui.alpha(Ui.RED, Math.min(1f, pa[0]))); c.drawRect(r.centerX() - 30 * s, r.bottom - 3 * s, r.centerX() + 30 * s, r.bottom, paint); }
            if (pa[2] > 0) { paint.setColor(Ui.alpha(Ui.RED, Math.min(1f, pa[2]))); c.drawRect(r.left, r.bottom - 3 * s, r.left + 40 * s, r.bottom, paint);
                c.drawRect(r.right - 40 * s, r.bottom - 3 * s, r.right, r.bottom, paint); }
        }
    }

    private void drawLocation(Canvas c) {
        RectF r = pLoc;
        Ui.panel(c, r, Ui.RADIUS * s, Ui.PANEL, Ui.LINE, paint);
        float x = r.left + 24 * s;
        label(c, str(R.string.deck_location), x, r.top + 36 * s);
        if (inGame) {
            monoSmall.setColor(Ui.TEXT_3);
            String m = map.toUpperCase();
            c.drawText(m, r.right - 24 * s - monoSmall.measureText(m), r.top + 36 * s, monoSmall);
        }
        String ch = inGame ? Names.chapter(map, previousMap) : null;
        bodySemi.setTextSize(20 * s);
        bodySemi.setColor(ch != null ? Ui.AMBER_2 : Ui.TEXT_2);
        String title = !inGame ? "--" : ch != null ? ch : map;
        c.drawText(ellipsize(title, bodySemi, r.width() - 48 * s), x, r.top + 66 * s, bodySemi);

        // breadcrumb map: north-up, fitted to the panel
        RectF m = new RectF(r.left + 16 * s, r.top + 82 * s, r.right - 16 * s, r.bottom - 14 * s);
        paint.setColor(Ui.BG_2);
        c.drawRoundRect(m, 6 * s, 6 * s, paint);
        paint.setColor(0x0CFFFFFF);
        for (float gx = m.left + 24 * s; gx < m.right; gx += 24 * s) c.drawRect(gx, m.top, gx + 1, m.bottom, paint);
        for (float gy = m.top + 24 * s; gy < m.bottom; gy += 24 * s) c.drawRect(m.left, gy, m.right, gy + 1, paint);
        if (!inGame || trailN == 0) {
            return;
        }
        float minX = pos[0], maxX = pos[0], minY = pos[1], maxY = pos[1];
        for (int i = 0; i < trailN; i++) {
            minX = Math.min(minX, trail[2 * i]);
            maxX = Math.max(maxX, trail[2 * i]);
            minY = Math.min(minY, trail[2 * i + 1]);
            maxY = Math.max(maxY, trail[2 * i + 1]);
        }
        float span = Math.max(512f, Math.max(maxX - minX, maxY - minY));
        float pad = 14 * s;
        float sc = Math.min((m.width() - 2 * pad) / span, (m.height() - 2 * pad) / span);
        float cxw = (minX + maxX) / 2f, cyw = (minY + maxY) / 2f;
        c.save();
        c.clipRect(m);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f * s);
        paint.setStrokeCap(Paint.Cap.ROUND);
        Path path = new Path();
        for (int i = 0; i < trailN; i++) {
            float px = m.centerX() + (trail[2 * i] - cxw) * sc;
            float py = m.centerY() - (trail[2 * i + 1] - cyw) * sc;
            if (i == 0) path.moveTo(px, py);
            else path.lineTo(px, py);
        }
        paint.setColor(Ui.alpha(Ui.AMBER, 0.45f));
        c.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        // player arrow
        float px = m.centerX() + (pos[0] - cxw) * sc;
        float py = m.centerY() - (pos[1] - cyw) * sc;
        double a = Math.toRadians(yaw);
        float dx = (float) Math.cos(a), dy = (float) -Math.sin(a);
        float size = 9 * s;
        Path arrow = new Path();
        arrow.moveTo(px + dx * size * 1.4f, py + dy * size * 1.4f);
        arrow.lineTo(px - dx * size * 0.8f - dy * size * 0.8f, py - dy * size * 0.8f + dx * size * 0.8f);
        arrow.lineTo(px - dx * size * 0.3f, py - dy * size * 0.3f);
        arrow.lineTo(px - dx * size * 0.8f + dy * size * 0.8f, py - dy * size * 0.8f - dx * size * 0.8f);
        arrow.close();
        paint.setColor(Ui.AMBER_2);
        c.drawPath(arrow, paint);
        c.restore();
    }

    private void drawButtons(Canvas c, long now) {
        String[] names = {str(game.equals("gearbox") ? R.string.btn_nightvision : R.string.btn_flashlight), str(R.string.btn_use), str(R.string.btn_quicksave),
                str(R.string.btn_quickload), str(R.string.btn_menu)};
        String[] hints = {"F", "E", "F6", "F7", "ESC"};
        boolean armed = now - loadArmedAt < 2500;
        for (int i = 0; i < 5; i++) {
            RectF b = buttons[i];
            boolean pressed = pressedButton == i && now - pressAt < 220;
            boolean lit = (i == B_FLASH && flOn != 0 && inGame) || (i == B_LOAD && armed);
            boolean enabled = i == B_MENU || inGame || (i == B_LOAD && connected);
            int fill = pressed ? Ui.alpha(Ui.AMBER, 0.22f) : lit ? Ui.alpha(Ui.AMBER, 0.12f) : Ui.PANEL;
            Ui.panel(c, b, Ui.RADIUS * s, fill, lit || pressed ? Ui.AMBER : Ui.LINE, paint);
            String n = (i == B_LOAD && armed ? str(R.string.btn_confirm) : names[i]).toUpperCase();
            chipText.setColor(!enabled ? Ui.TEXT_3 : lit ? Ui.AMBER_2 : Ui.TEXT);
            chipText.setTextSize(15 * s);
            float fit = chipText.measureText(n);
            if (fit > b.width() - 28 * s) {
                chipText.setTextSize(15 * s * (b.width() - 28 * s) / fit);
            }
            c.drawText(n, b.centerX() - chipText.measureText(n) / 2, b.centerY() + 2 * s, chipText);
            chipText.setTextSize(14 * s);
            monoSmall.setColor(Ui.TEXT_3);
            c.drawText(hints[i], b.centerX() - monoSmall.measureText(hints[i]) / 2, b.bottom - 16 * s, monoSmall);
            if (i == B_FLASH) {
                paint.setColor(flOn != 0 && inGame ? Ui.AMBER : Ui.LINE_2);
                c.drawCircle(b.left + 22 * s, b.top + 22 * s, 5 * s, paint);
            }
        }
    }

    // ------------------------------------------------------------------ touch

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downButton = -1;
                downWeapon = null;
                for (int i = 0; i < buttons.length; i++) {
                    if (buttons[i].contains(x, y)) {
                        downButton = i;
                        pressedButton = i;
                        pressedWeapon = -1;
                        pressAt = SystemClock.uptimeMillis();
                    }
                }
                if (downButton < 0) {
                    for (Weapon w : weapons) {
                        if (!w.bounds.isEmpty() && w.bounds.contains(x, y)) {
                            downWeapon = w;
                            pressedWeapon = w.id;
                            pressedButton = -1;
                            pressAt = SystemClock.uptimeMillis();
                        }
                    }
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                if (downButton >= 0 && buttons[downButton].contains(x, y)) {
                    button(downButton);
                } else if (downWeapon != null && downWeapon.bounds.contains(x, y)) {
                    selectWeapon(downWeapon);
                }
                downButton = -1;
                downWeapon = null;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                downButton = -1;
                downWeapon = null;
                return true;
            default:
                return true;
        }
    }

    private void selectWeapon(Weapon w) {
        if (!inGame || dead != 0) {
            return;
        }
        if (!w.selectable) {
            showToast(str(R.string.deck_empty));
            return;
        }
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        send("thor_select " + w.id);
    }

    private void button(int i) {
        long now = SystemClock.uptimeMillis();
        if (i != B_MENU && !(i == B_LOAD && connected) && !inGame) {
            return;
        }
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        switch (i) {
            case B_FLASH:
                send("impulse 100");
                break;
            case B_USE:
                send("thor_use");
                break;
            case B_SAVE:
                send("savequick");
                showToast(str(R.string.toast_saved));
                break;
            case B_LOAD:
                if (now - loadArmedAt < 2500) {
                    loadArmedAt = 0;
                    send("loadquick");
                    showToast(str(R.string.toast_loading));
                } else {
                    loadArmedAt = now;
                }
                break;
            case B_MENU:
                // same as the Escape key / Start button: opens the menu in game, closes it in the menu
                try {
                    org.libsdl.app.SDLActivity.onNativeKeyDown(android.view.KeyEvent.KEYCODE_ESCAPE);
                    handler.postDelayed(() -> org.libsdl.app.SDLActivity.onNativeKeyUp(android.view.KeyEvent.KEYCODE_ESCAPE), 60);
                } catch (Throwable t) {
                    send("cancelselect");
                }
                break;
            default:
                break;
        }
    }

    private void showToast(String t) {
        toast = t;
        toastAt = SystemClock.uptimeMillis();
    }
}
