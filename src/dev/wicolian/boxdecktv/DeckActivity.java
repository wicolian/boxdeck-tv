package dev.wicolian.boxdecktv;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

// Every herdr pane with an agent in it, on every machine, as the same compact cards the home screen
// uses: one section per machine, three cards to a row, waiting first. Agents outside herdr have no
// status, so they are one quiet line per machine instead of cards that say nothing. Up and down
// scroll, Back goes home. Refreshes every 15 seconds while it is open.
public class DeckActivity extends Activity {
    private static final int REFRESH_MS = 15000, COLS = 3, CARD_W = 552, GAP = 36, MARGIN = 96;
    private static final int BREATH_MS = 2400, BREATH_STEP_MS = 66;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<View> breathing = new ArrayList<>();
    private LinearLayout list;
    private TextView summary;
    private boolean running, entered;
    private Theme theme;
    private Typeface regular, bold;
    private float scale;

    private final Runnable breath = new Runnable() {
        @Override public void run() {
            if (!running || breathing.isEmpty()) return;
            double t = (SystemClock.uptimeMillis() % BREATH_MS) / (double) BREATH_MS;
            float a = (float) (0.45 + 0.55 * (0.5 - 0.5 * Math.cos(2 * Math.PI * t)));
            for (View v : breathing) v.setAlpha(a);
            main.postDelayed(this, BREATH_STEP_MS);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences p = getSharedPreferences(Deck.PREFS, 0);
        theme = Theme.current(p);
        regular = Theme.regular(this, p);
        bold = Theme.bold(this, p);
        scale = getResources().getDisplayMetrics().widthPixels / 1920f;
        getWindow().setBackgroundDrawable(new ColorDrawable(theme.classic ? 0xFF0E0B0C : theme.bg));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(px(MARGIN), px(72), px(MARGIN), px(40));
        page.setClipChildren(false); // room for the focus zoom of the cards at the edges
        page.setClipToPadding(false);
        TextView title = text(px(48), theme.fg, bold);
        title.setText("Agents");
        page.addView(title);
        summary = text(px(24), theme.fgDim(), regular);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = px(12);
        page.addView(summary, sp);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, px(24), 0, px(24));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        scroll.addView(list);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1f);
        lp.topMargin = px(16);
        page.addView(scroll, lp);
        setContentView(page);
        summary.setText("Reading your agents…");
    }

    @Override
    protected void onResume() {
        super.onResume();
        running = true;
        refresh();
    }

    @Override
    protected void onPause() {
        running = false;
        main.removeCallbacksAndMessages(null);
        super.onPause();
    }

    private void refresh() {
        final SharedPreferences p = getSharedPreferences(Deck.PREFS, 0);
        new Thread(() -> {
            List<Deck.Box> boxes = null;
            String error = null;
            try { boxes = Deck.fetch(p); }
            catch (Deck.Refused e) { error = "boxdeck refused the token (" + e.code + "). Mint a read-only one: boxdeck token new --read-only tv"; }
            catch (Exception e) { error = "Can't reach boxdeck. Check that Tailscale is on, on the TV and on the box."; }
            final List<Deck.Box> b = boxes;
            final String err = error;
            main.post(() -> {
                if (!running) return;
                show(b, err);
                main.postDelayed(this::refresh, REFRESH_MS);
            });
        }, "boxdeck-deck").start();
    }

    private void show(List<Deck.Box> boxes, String error) {
        View f = getCurrentFocus();
        Object focusedKey = f == null ? null : f.getTag();
        main.removeCallbacks(breath);
        breathing.clear();
        list.removeAllViews();
        if (error != null) {
            summary.setText(error);
            return;
        }
        int working = 0, waiting = 0;
        for (Deck.Box b : boxes) { working += b.working(); waiting += b.waiting(); }
        summary.setText(waiting + " waiting  ·  " + working + " working  ·  " + boxes.size()
                + (boxes.size() == 1 ? " machine" : " machines"));

        View restore = null, first = null;
        for (Deck.Box b : boxes) {
            LinearLayout head = new LinearLayout(this);
            head.setGravity(Gravity.BOTTOM);
            TextView name = text(px(30), theme.fg, bold);
            name.setText(b.name);
            head.addView(name);
            TextView sum = text(px(22), b.ok ? theme.fgDim() : theme.warn, regular);
            sum.setText(!b.ok ? "    offline" : !b.listed ? "    " + b.count + " agents" + (b.error != null ? ", " + b.error : ", no read-only token for it")
                    : "    " + b.waiting() + " waiting · " + b.working() + " working");
            head.addView(sum);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
            hp.topMargin = list.getChildCount() == 0 ? px(8) : px(44);
            hp.bottomMargin = px(20);
            list.addView(head, hp);

            Map<String, Integer> outside = new TreeMap<>();
            LinearLayout row = null;
            int n = 0;
            for (Deck.Agent a : b.agents) {
                if (!a.inPane()) { outside.put(a.kind, outside.containsKey(a.kind) ? outside.get(a.kind) + 1 : 1); continue; }
                if (n % COLS == 0) {
                    row = new LinearLayout(this);
                    row.setClipChildren(false);
                    LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
                    rp.bottomMargin = px(GAP);
                    list.addView(row, rp);
                }
                FrameLayout card = AgentCard.make(this, theme, regular, bold, scale, CARD_W, a.key, a.waiting(), false,
                        a.waiting() ? "Waiting" : "Working", AgentCard.model(a) + "  ·  " + shortCwd(a.cwd), Deck.age(a.secs),
                        a.title != null && !a.title.isEmpty() ? a.title : a.kind, breathing, null);
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
                if (n % COLS != COLS - 1) cp.rightMargin = px(GAP);
                row.addView(card, cp);
                View face = card.getChildAt(0);
                if (first == null) first = face;
                if (a.key.equals(focusedKey)) restore = face;
                n++;
            }
            if (b.listed && n == 0 && outside.isEmpty()) {
                TextView t = text(px(24), theme.fgDim(), regular);
                t.setText("Nothing running here.");
                list.addView(t);
            }
            if (!outside.isEmpty()) {
                StringBuilder s = new StringBuilder();
                int total = 0;
                for (int c : outside.values()) total += c;
                s.append(total).append(" more outside herdr (");
                String sep = "";
                for (Map.Entry<String, Integer> e : outside.entrySet()) { s.append(sep).append(e.getValue()).append(' ').append(e.getKey()); sep = ", "; }
                s.append("). Start agents in herdr panes to see them here.");
                TextView t = text(px(22), theme.fgDim(), regular);
                t.setText(s);
                list.addView(t);
            }
        }
        if (!entered) {
            entered = true;
            for (int i = 0; i < list.getChildCount(); i++) {
                View v = list.getChildAt(i);
                v.setAlpha(0f);
                v.setTranslationY(px(20));
                v.animate().alpha(1f).translationY(0).setStartDelay(Math.min(i, 10) * 45L).setDuration(320)
                        .setInterpolator(new DecelerateInterpolator(2f)).start();
            }
        }
        View target = restore != null ? restore : first;
        if (target != null) target.requestFocus();
        if (!breathing.isEmpty()) main.post(breath);
    }

    // "~/codes/databrain/frontend-mono" -> "frontend-mono"
    private static String shortCwd(String cwd) {
        if (cwd == null || cwd.isEmpty()) return "";
        String[] parts = cwd.split("/");
        return parts[parts.length - 1];
    }

    private TextView text(int size, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        return t;
    }

    private int px(int v) { return Math.round(v * scale); }
}
