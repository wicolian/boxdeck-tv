package dev.wicolian.boxdecktv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

// The Shelf layout's agents: a headline that answers the one question ("does anything need me?"),
// a quiet line under it, and a row of agent cards, waiting first.
//
// The signature is the status light on each card, like the LEDs on a router across the room: amber and
// steady when an agent waits for you, the theme's accent and slowly breathing while it works. Nothing
// blinks. OK on a card opens every pane (DeckActivity). Polls every 20 seconds while Home is in front.
final class HeroRow extends HorizontalScrollView {
    private static final int POLL_MS = 20000, MAX_CARDS = 8;
    private static final int CARD_W = 540, CARD_H = 250, GAP = 32, RADIUS = 26;

    private final SharedPreferences prefs;
    private final Theme theme;
    private final Typeface regular, bold;
    private final float scale;
    private final TextView headline, stats, budget;
    private final LinearLayout row;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final int fgSoft, fgDim;
    // Working lights, breathed by one loop at 15 frames a second: a slow fade on a small dot looks the
    // same as at 60, for a quarter of the redraws on a TV chip that needs them for focus moves.
    private final List<View> breathing = new ArrayList<>();
    private static final int BREATH_MS = 2400, BREATH_STEP_MS = 66;
    private final Runnable breath = new Runnable() {
        @Override public void run() {
            if (!running || breathing.isEmpty()) return;
            double t = (android.os.SystemClock.uptimeMillis() % BREATH_MS) / (double) BREATH_MS;
            float a = (float) (0.45 + 0.55 * (0.5 - 0.5 * Math.cos(2 * Math.PI * t)));
            for (View v : breathing) v.setAlpha(a);
            main.postDelayed(this, BREATH_STEP_MS);
        }
    };
    private String shownKeys = "";
    private boolean running, entered;
    private Thread worker;

    HeroRow(Context c, SharedPreferences prefs, Theme theme, Typeface regular, Typeface bold, float scale,
            TextView headline, TextView stats, TextView budget) {
        super(c);
        this.prefs = prefs;
        this.theme = theme;
        this.regular = regular;
        this.bold = bold;
        this.scale = scale;
        this.headline = headline;
        this.stats = stats;
        this.budget = budget;
        fgSoft = theme.fgSoft();
        fgDim = theme.fgDim();
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setClipChildren(false);
        setClipToPadding(false);
        row = new LinearLayout(c);
        row.setClipChildren(false);
        row.setPadding(px(96), px(24), px(96), px(24));
        addView(row, new LayoutParams(-2, -2));
        headline.setText(greeting());
        if (!Deck.configured(prefs)) {
            note("Connect boxdeck", "See your agents here",
                    "Add a read-only boxdeck token with install.sh. Steps: github.com/wicolian/boxdeck-tv");
        } else {
            note("boxdeck", "Reading your agents…", "");
        }
    }

    private int px(int v) { return Math.round(v * scale); }

    void start() {
        if (running || !Deck.configured(prefs)) return;
        running = true;
        worker = new Thread(() -> {
            while (running) {
                Deck.Snapshot s = null;
                try { s = Deck.snapshot(prefs); } catch (Exception ignored) {}
                final Deck.Snapshot snap = s;
                main.post(() -> {
                    if (!running) return;
                    if (snap == null) {
                        headline.setText(greeting());
                        stats.setText("");
                        note("Offline", "Can't reach boxdeck", "Check that Tailscale is on, on the TV and on the box");
                    } else render(snap);
                });
                try { Thread.sleep(POLL_MS); } catch (InterruptedException e) { return; }
            }
        }, "boxdeck-hero");
        worker.setDaemon(true);
        worker.start();
    }

    void stop() {
        running = false;
        main.removeCallbacks(breath);
        if (worker != null) worker.interrupt();
        worker = null;
    }

    @Override
    protected void onDetachedFromWindow() {
        main.removeCallbacks(breath);
        breathing.clear();
        super.onDetachedFromWindow();
    }

    private String greeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h < 5 ? "Good night" : h < 12 ? "Good morning" : h < 18 ? "Good afternoon" : "Good evening";
    }

    private void render(Deck.Snapshot s) {
        fade(headline, headlineText(s));
        fade(stats, statsLine(s));
        fade(budget, budgetLine(s));

        List<Deck.Agent> panes = new ArrayList<>();
        int outside = 0;
        for (Deck.Box b : s.boxes) for (Deck.Agent a : b.agents) { if (a.inPane()) panes.add(a); else outside++; }
        // Waiting first (they want you), then working; inside each, the one that has run longest first.
        java.util.Collections.sort(panes, (x, y) -> x.waiting() != y.waiting() ? (x.waiting() ? -1 : 1) : Long.compare(y.secs, x.secs));
        if (panes.isEmpty()) {
            note("herdr", "Nothing running in herdr", outside > 0
                    ? outside + " agents run outside herdr. Start one in a herdr pane to see it here."
                    : "Start an agent in a herdr pane and it shows up here.");
            return;
        }
        StringBuilder keys = new StringBuilder();
        for (int i = 0; i < Math.min(MAX_CARDS, panes.size()); i++) keys.append(panes.get(i).key).append(panes.get(i).status).append(';');
        if (keys.toString().equals(shownKeys)) return; // same cards; only the ages moved
        shownKeys = keys.toString();

        View focus = findFocus();
        Object focused = focus == null ? null : focus.getTag();
        clearRow();
        View restore = null;
        for (int i = 0; i < Math.min(MAX_CARDS, panes.size()); i++) {
            Deck.Agent a = panes.get(i);
            String model = a.model == null || a.model.isEmpty() ? a.kind : a.model.replace("claude-", "");
            View card = card(a.key, a.waiting(), a.waiting() ? "Waiting for you" : "Working", Deck.age(a.secs),
                    a.title != null && !a.title.isEmpty() ? a.title : a.kind,
                    a.box + "  ·  " + model + "  ·  " + shortCwd(a.cwd));
            row.addView(card);
            if (a.key.equals(focused)) restore = card.findViewWithTag(a.key);
        }
        enter();
        if (restore != null) restore.requestFocus();
        if (!breathing.isEmpty()) { main.removeCallbacks(breath); main.post(breath); }
    }

    // One card that says why the row is empty, and what to do.
    private void note(String label, String title, String meta) {
        shownKeys = "";
        boolean hadFocus = findFocus() != null;
        clearRow();
        View card = card("empty", false, label, "", title, meta);
        row.addView(card);
        enter();
        if (hadFocus) card.findViewWithTag("empty").requestFocus();
    }

    private void clearRow() {
        main.removeCallbacks(breath);
        breathing.clear();
        row.removeAllViews();
    }

    // First fill: cards rise into place one after another. Later fills swap in place.
    private void enter() {
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            v.setAlpha(0f);
            v.setTranslationY(px(entered ? 0 : 28));
            v.animate().alpha(1f).translationY(0).setStartDelay(entered ? 0 : 120 + i * 60L).setDuration(entered ? 200 : 380)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
        }
        entered = true;
    }

    // Text that changes fades through, so the eye notices the change without a flash.
    private static void fade(TextView t, CharSequence text) {
        if (text.toString().equals(t.getText().toString())) return;
        if (t.getText().length() == 0) { t.setText(text); return; }
        t.animate().alpha(0.2f).setDuration(140).withEndAction(() -> {
            t.setText(text);
            t.animate().alpha(1f).setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
        }).start();
    }

    private View card(String key, boolean waiting, String status, String age, String title, String meta) {
        boolean empty = "empty".equals(key);
        int tone = empty ? fgDim : waiting ? theme.warn : theme.accent;

        // The wrapper keeps room for the focus zoom, so neighbours never overlap.
        FrameLayout wrap = new FrameLayout(getContext());
        wrap.setClipChildren(false);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(px(empty ? 1100 : CARD_W), px(CARD_H));
        wp.rightMargin = px(GAP);
        wrap.setLayoutParams(wp);

        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(px(30), px(28), px(30), px(26));
        // Solid colours only (see MainActivity.solid): the surface as it would look over the canvas,
        // and for a waiting card a warm tint from its top left, as one cheap linear gradient.
        int under = Theme.blend(theme.bg, android.graphics.Color.BLACK, 0.18f);
        int surface = Theme.blend(under, theme.surface, 0.95f) | 0xFF000000;
        final GradientDrawable face = waiting
                ? new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {Theme.blend(surface, theme.warn, 0.12f), surface, surface})
                : new GradientDrawable();
        if (!waiting) face.setColor(surface);
        face.setCornerRadius(px(RADIUS));
        face.setStroke(Math.max(1, px(2)), Theme.blend(surface, theme.fg, 0.08f));
        card.setBackground(face);

        // Status row: the light, the word, and the age on the right.
        LinearLayout top = new LinearLayout(getContext());
        top.setGravity(Gravity.CENTER_VERTICAL);
        View led = new View(getContext());
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(tone);
        led.setBackground(dot);
        top.addView(led, new LinearLayout.LayoutParams(px(14), px(14)));
        if (!empty && !waiting) breathe(led);
        TextView st = text(px(22), tone, bold);
        st.setText(status);
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(0, -2, 1f);
        stp.leftMargin = px(14);
        top.addView(st, stp);
        if (!age.isEmpty()) {
            TextView ag = text(px(22), fgDim, regular);
            ag.setText(age);
            ag.setFontFeatureSettings("tnum");
            top.addView(ag);
        }
        card.addView(top);

        TextView t = text(px(32), theme.fg, bold);
        t.setText(title);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setLineSpacing(px(4), 1f);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, 0, 1f);
        tp.topMargin = px(22);
        card.addView(t, tp);

        TextView m = text(px(21), fgDim, regular);
        m.setText(meta);
        m.setSingleLine(true);
        m.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(m, new LinearLayout.LayoutParams(-1, -2));

        card.setFocusable(true);
        card.setTag(key);
        card.setOnFocusChangeListener((v, has) -> {
            face.setStroke(Math.max(1, px(has ? 4 : 2)), has ? theme.accent : Theme.blend(surface, theme.fg, 0.08f));
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f)
                    .setDuration(220).setInterpolator(new DecelerateInterpolator(1.6f)).start();
            if (has) wrap.post(() -> {
                // Keep the focused card in view, with a little of the next one showing.
                int[] at = new int[2];
                wrap.getLocationInWindow(at);
                if (at[0] + wrap.getWidth() > getWidth() - px(120) || at[0] < px(96))
                    smoothScrollTo(Math.max(0, wrap.getLeft() - px(96)), 0);
            });
        });
        card.setOnClickListener(v -> {
            if (!Deck.configured(prefs)) return;
            getContext().startActivity(new Intent(getContext(), DeckActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        });
        wrap.addView(card, new FrameLayout.LayoutParams(-1, -1));
        return wrap;
    }

    // A working agent's light breathes: 45% to 100% and back, 2.4 seconds a cycle. Slow enough to read
    // as alive rather than as an alert. The loop (breath) runs only while Home is in front.
    private void breathe(View led) {
        breathing.add(led);
    }

    // "3 agents need you", "1 agent working", "All quiet": the count in the colour it stands for.
    private CharSequence headlineText(Deck.Snapshot s) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        int waiting = s.waiting(), working = s.working();
        if (waiting > 0) {
            AgentsPanel.append(out, String.valueOf(waiting), theme.warn);
            AgentsPanel.append(out, waiting == 1 ? " agent needs you" : " agents need you", theme.fg);
        } else if (working > 0) {
            AgentsPanel.append(out, String.valueOf(working), theme.accent);
            AgentsPanel.append(out, working == 1 ? " agent working" : " agents working", theme.fg);
        } else {
            AgentsPanel.append(out, "All quiet", theme.fg);
        }
        return out;
    }

    // Under the headline: what else is going on. Warn colour only for what wants you.
    private CharSequence statsLine(Deck.Snapshot s) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        String sep = "   ·   ";
        if (s.waiting() > 0 && s.working() > 0) AgentsPanel.append(out, s.working() + " working", fgSoft);
        for (Deck.Box b : s.boxes) {
            String gap = out.length() > 0 ? sep : "";
            if (!b.ok) AgentsPanel.append(out, gap + b.name + " is offline", theme.warn);
            else if (b.diskFull()) AgentsPanel.append(out, gap + b.name + " disk " + b.diskPct + "% full", theme.warn);
        }
        JSONObject prs = s.github == null ? null : s.github.optJSONObject("prs");
        if (prs != null) {
            AgentsPanel.append(out, (out.length() > 0 ? sep : "") + prs.optInt("open") + " PRs open", fgSoft);
            if (prs.optInt("failing") > 0) AgentsPanel.append(out, ", " + prs.optInt("failing") + " failing CI", theme.warn);
        }
        if (out.length() == 0) AgentsPanel.append(out, s.boxes.size() + (s.boxes.size() == 1 ? " machine" : " machines") + " online", fgDim);
        return out;
    }

    // Under the clock: Claude quota left and today's estimated cost. Context, not a call to act.
    private CharSequence budgetLine(Deck.Snapshot s) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        int five = s.left("fiveHour"), week = s.left("sevenDay");
        if (five >= 0) {
            AgentsPanel.append(out, "Claude  ", fgDim);
            AgentsPanel.append(out, five + "%", five < Deck.QUOTA_LOW ? theme.warn : fgSoft);
            AgentsPanel.append(out, " 5h", fgDim);
        }
        if (week >= 0) {
            AgentsPanel.append(out, "  ", fgDim);
            AgentsPanel.append(out, week + "%", week < Deck.QUOTA_LOW ? theme.warn : fgSoft);
            AgentsPanel.append(out, " 7d", fgDim);
        }
        AgentsPanel.append(out, String.format(java.util.Locale.ROOT, "   ~$%.0f today", s.cost()), fgDim);
        return out;
    }

    private static String shortCwd(String cwd) {
        if (cwd == null) return "";
        String[] parts = cwd.split("/");
        return parts.length <= 2 ? cwd : parts[parts.length - 1];
    }

    private TextView text(int size, int color, Typeface tf) {
        TextView t = new TextView(getContext());
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        return t;
    }
}
