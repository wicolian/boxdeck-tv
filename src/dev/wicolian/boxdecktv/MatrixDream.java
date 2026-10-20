package dev.wicolian.boxdecktv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.service.dreams.DreamService;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

// Screensaver: green code rain, with the time small in the bottom right corner. Pick it under
// Settings > Screensaver ("Matrix"), or: settings put secure screensaver_components dev.wicolian.boxdecktv/.MatrixDream
public class MatrixDream extends DreamService {

    // Two quiet lines above the clock, from boxdeck: usage ("5h 87% · 7d 63% · ~$684") and agents
    // ("◐ 4 waiting  ● 2 working"). Read every 30
    // seconds while the screensaver runs; empty when boxdeck is not set up or can't be reached, so
    // the screensaver never shows an error.
    private volatile int waiting = -1, working = -1, fiveLeft = -1, weekLeft = -1;
    private volatile double cost = -1;
    private volatile boolean dreaming;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setContentView(new Rain(this, this));
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        dreaming = true;
        final android.content.SharedPreferences p = getSharedPreferences(Deck.PREFS, 0);
        if (!Deck.configured(p)) return;
        Thread t = new Thread(() -> {
            while (dreaming) {
                try {
                    Deck.Snapshot snap = Deck.snapshot(p);
                    waiting = snap.waiting();
                    working = snap.working();
                    fiveLeft = snap.left("fiveHour");
                    weekLeft = snap.left("sevenDay");
                    cost = snap.cost();
                } catch (Exception e) {
                    waiting = working = fiveLeft = weekLeft = -1;
                    cost = -1;
                }
                try { Thread.sleep(30000); } catch (InterruptedException e) { return; }
            }
        }, "boxdeck-dream");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void onDreamingStopped() {
        dreaming = false;
        super.onDreamingStopped();
    }

    // The rain is drawn into a half-size bitmap and scaled up, so a slow TV only fills a quarter of the
    // pixels each frame. Each frame darkens what is there a little and draws one new glyph per column,
    // which leaves the fading trails.
    static final class Rain extends View {
        private static final String GLYPHS =
                "ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜﾝ0123456789Z:.=*+-<>|";
        private static final int FRAME_MS = 50;
        private final Random rnd = new Random();
        private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fade = new Paint();
        private final Paint blit = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint clock = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final SimpleDateFormat time = new SimpleDateFormat("HH:mm", Locale.ROOT);
        private Bitmap frame;
        private Canvas canvas;
        private int cell;
        private float[] drops;
        private final Rect dst = new Rect();
        private final MatrixDream dream;
        private final Paint status = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint plate = new Paint(Paint.ANTI_ALIAS_FLAG);

        Rain(Context c, MatrixDream dream) {
            super(c);
            this.dream = dream;
            status.setTypeface(Typeface.MONOSPACE);
            status.setTextAlign(Paint.Align.RIGHT);
            plate.setColor(Color.argb(200, 0, 0, 0)); // a dark plate under the clock, so rain never hides it
            setBackgroundColor(Color.BLACK);
            glyph.setTypeface(Typeface.MONOSPACE);
            fade.setColor(Color.argb(38, 0, 0, 0));
            clock.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            clock.setColor(MainActivity.PHOSPHOR);
            clock.setTextAlign(Paint.Align.RIGHT);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            if (w <= 0 || h <= 0) return;
            frame = Bitmap.createBitmap(Math.max(1, w / 2), Math.max(1, h / 2), Bitmap.Config.ARGB_8888);
            canvas = new Canvas(frame);
            canvas.drawColor(Color.BLACK);
            cell = Math.max(8, frame.getHeight() / 36);
            glyph.setTextSize(cell);
            drops = new float[frame.getWidth() / cell + 1];
            for (int i = 0; i < drops.length; i++) drops[i] = -rnd.nextInt(40);
            clock.setTextSize(h / 18f);
            status.setTextSize(h / 40f);
            dst.set(0, 0, w, h);
        }

        @Override
        protected void onDraw(Canvas out) {
            if (frame == null) return;
            canvas.drawRect(0, 0, frame.getWidth(), frame.getHeight(), fade);
            int rows = frame.getHeight() / cell;
            for (int i = 0; i < drops.length; i++) {
                if (drops[i] >= 0) {
                    // The leading glyph is near white, the trail behind it the usual green.
                    glyph.setColor(rnd.nextInt(10) == 0 ? 0xFFD8FFE4 : MainActivity.PHOSPHOR);
                    char ch = GLYPHS.charAt(rnd.nextInt(GLYPHS.length()));
                    canvas.drawText(String.valueOf(ch), i * cell, (drops[i] + 1) * cell, glyph);
                }
                drops[i] += 1;
                if (drops[i] > rows && rnd.nextInt(40) == 0) drops[i] = -rnd.nextInt(20);
            }
            out.drawBitmap(frame, null, dst, blit);
            float pad = getHeight() / 24f, right = getWidth() - pad, base = getHeight() - pad;
            String now = time.format(new Date());
            // Agents line above the clock: waiting in amber (it wants you), working in green.
            String wait = dream.waiting > 0 ? "◐ " + dream.waiting + " waiting" : "";
            String work = dream.working > 0 ? "● " + dream.working + " working" : "";
            String gap = wait.isEmpty() || work.isEmpty() ? "" : "   ";
            float lineY = base - clock.getTextSize() * 1.15f;
            // Usage, one line up, dim: it is context, not a call to act, unless a quota is low.
            int five = dream.fiveLeft, week = dream.weekLeft;
            String use = five < 0 ? "" : "claude 5h " + five + "% left" + (week < 0 ? "" : " \u00B7 7d " + week + "% left")
                    + (dream.cost < 0 ? "" : String.format(Locale.ROOT, " \u00B7 ~$%.0f today", dream.cost));
            boolean low = (five >= 0 && five < Deck.QUOTA_LOW) || (week >= 0 && week < Deck.QUOTA_LOW);
            float useY = lineY - status.getTextSize() * 1.5f;
            float width = Math.max(clock.measureText(now), Math.max(status.measureText(wait + gap + work), status.measureText(use)));
            float top = !use.isEmpty() ? useY - status.getTextSize()
                    : (wait + work).isEmpty() ? base - clock.getTextSize() : lineY - status.getTextSize();
            out.drawRoundRect(right - width - pad / 2, top - pad / 2, right + pad / 2, base + pad / 2, pad / 2, pad / 2, plate);
            if (!use.isEmpty()) {
                status.setColor(low ? AgentsPanel.WAITING : 0x9939FF7A);
                out.drawText(use, right, useY, status);
            }
            out.drawText(now, right, base, clock);
            if (!work.isEmpty()) { status.setColor(MainActivity.PHOSPHOR); out.drawText(work, right, lineY, status); }
            if (!wait.isEmpty()) {
                status.setColor(AgentsPanel.WAITING);
                out.drawText(wait, right - status.measureText(gap + work), lineY, status);
            }
            postInvalidateDelayed(FRAME_MS);
        }
    }
}
