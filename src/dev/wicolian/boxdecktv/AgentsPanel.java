package dev.wicolian.boxdecktv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.List;

// A glance at every machine boxdeck knows, one block each, right of the clock:
//
//   $ boxdeck status
//   box   ● 2 working   ◐ 4 waiting   6 other
//   mac   ● 1 working
//   claude 5h ████████░░ 78% left · 7d 64% left
//   ~$652 today (list price)
//
// "waiting" is idle + done: an agent that stopped and wants you, so it is the only figure in the warning
// colour. OK on the panel opens every herdr pane (DeckActivity). Off until boxdeck is set up:
//   am start -n dev.wicolian.boxdecktv/.MainActivity --es boxdeck_url http://100.x.y.z:8100 --es boxdeck_token bdr_...
// Polls every 20 seconds while Home is in front, nothing else.
final class AgentsPanel extends LinearLayout {
    static final int WAITING = 0xFFFFC447;
    private static final int POLL_MS = 20000;

    private final SharedPreferences prefs;
    private final int fg, fgSoft, fgDim, textPx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextView body;
    private boolean running;
    private Thread worker;

    AgentsPanel(Context c, SharedPreferences prefs, int fg, int fgSoft, int fgDim, int focusFill, int radius,
                int textPx, Typeface regular) {
        super(c);
        this.prefs = prefs;
        this.fg = fg;
        this.fgSoft = fgSoft;
        this.fgDim = fgDim;
        this.textPx = textPx;
        setOrientation(VERTICAL);
        setGravity(Gravity.END);
        setPadding(textPx, textPx * 2 / 3, textPx, textPx * 2 / 3);
        TextView head = line(fgDim, regular);
        head.setText("$ boxdeck status");
        body = line(fgSoft, regular);
        body.setLineSpacing(textPx * 0.25f, 1f);
        addView(head);
        LayoutParams lp = new LayoutParams(-2, -2);
        lp.topMargin = textPx / 2;
        addView(body, lp);

        // Focusable like a tile: a quiet fill and a "> panes_" hint when focused, OK opens the panes.
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(radius);
        bg.setColor(0);
        setBackground(bg);
        setFocusable(true);
        setClickable(true);
        setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? focusFill : 0);
            head.setText(has ? "$ boxdeck status   > open panes_" : "$ boxdeck status");
            head.setTextColor(has ? fg : fgDim);
        });
        setOnClickListener(v -> getContext().startActivity(
                new Intent(getContext(), DeckActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
    }

    private TextView line(int color, Typeface tf) {
        TextView t = new TextView(getContext());
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setGravity(Gravity.END);
        t.setIncludeFontPadding(false);
        return t;
    }

    void start() {
        if (running) return;
        running = true;
        worker = new Thread(() -> {
            while (running) {
                CharSequence text = render();
                main.post(() -> { if (running) body.setText(text); });
                try { Thread.sleep(POLL_MS); } catch (InterruptedException e) { return; }
            }
        }, "boxdeck-panel");
        worker.setDaemon(true);
        worker.start();
    }

    void stop() {
        running = false;
        if (worker != null) worker.interrupt();
        worker = null;
    }

    private CharSequence render() {
        SpannableStringBuilder out = new SpannableStringBuilder();
        List<Deck.Box> boxes;
        try {
            boxes = Deck.fetch(prefs);
        } catch (Deck.Refused e) {
            append(out, "token refused (" + e.code + ") - mint a read-only one", fgDim);
            return out;
        } catch (Exception e) {
            append(out, "boxdeck unreachable - is Tailscale on?", fgDim);
            return out;
        }
        JSONObject usage = null;
        for (Deck.Box b : boxes) {
            if (out.length() > 0) out.append("\n");
            append(out, b.name + "  ", fg);
            if (!b.ok) { append(out, "down", WAITING); continue; }
            if (usage == null && b.usage != null) usage = b.usage;
            if (!b.listed) {
                append(out, b.count + " agents" + (b.error != null ? " (" + b.error + ")" : ""), fgDim);
                continue;
            }
            int working = b.working(), waiting = b.waiting(), other = b.agents.size() - working - waiting;
            String sep = "";
            if (working > 0) { append(out, "● " + working + " working", fg); sep = "   "; }
            if (waiting > 0) { append(out, sep + "◐ " + waiting + " waiting", WAITING); sep = "   "; }
            if (other > 0) append(out, sep + other + " other", fgDim);
            if (b.agents.isEmpty()) append(out, "no agents", fgDim);
        }
        appendQuota(out, usage, boxes);
        return out;
    }

    // Claude's quota is per account, so the first machine that reports it is enough. Cost is per
    // machine, so it is summed.
    private void appendQuota(SpannableStringBuilder out, JSONObject usage, List<Deck.Box> boxes) {
        JSONObject quota = usage == null ? null : usage.optJSONObject("quota");
        JSONObject claude = quota == null ? null : quota.optJSONObject("claude");
        JSONObject five = claude == null ? null : claude.optJSONObject("fiveHour");
        JSONObject week = claude == null ? null : claude.optJSONObject("sevenDay");
        out.append("\n");
        if (five != null && five.has("pct")) {
            int used = pct(five);
            StringBuilder bar = new StringBuilder();
            for (int i = 0; i < 10; i++) bar.append(i < Math.round(used / 10f) ? '█' : '░');
            append(out, "claude 5h ", fgDim);
            append(out, bar.toString(), used >= 80 ? WAITING : fg);
            append(out, " " + (100 - used) + "% left", fgSoft);
        } else {
            append(out, "claude 5h -", fgDim);
        }
        if (week != null && week.has("pct")) {
            int used = pct(week);
            append(out, " · 7d ", fgDim);
            append(out, (100 - used) + "% left", used >= 80 ? WAITING : fgSoft);
        }
        double cost = 0;
        for (Deck.Box b : boxes) {
            JSONObject today = b.usage == null ? null : b.usage.optJSONObject("today");
            if (today != null) for (String p : new String[] {"claude", "codex"}) {
                JSONObject t = today.optJSONObject(p);
                if (t != null) cost += t.optDouble("costUsd", 0);
            }
        }
        // boxdeck prices tokens at list price, so this is an estimate, not a bill.
        append(out, String.format(java.util.Locale.ROOT, "\n~$%.0f today (list price)", cost), fgDim);
    }

    private static int pct(JSONObject o) {
        return (int) Math.round(Math.max(0, Math.min(100, o.optDouble("pct", 0))));
    }

    static void append(SpannableStringBuilder out, String s, int color) {
        int start = out.length();
        out.append(s);
        out.setSpan(new ForegroundColorSpan(color), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}
