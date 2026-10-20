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

// A glance at every machine boxdeck knows, right of the clock. At most five lines:
//
//   $ boxdeck status · ~$674 today
//   box   ● 2 working   ◐ 4 waiting   6 outside herdr
//   mac   ● 1 working   disk 92%
//   claude 5h ████████░░ 78% left · 7d 64% left
//   prs 5 open · ✕ 2 failing · 1 pending
//
// Amber (WAITING) is only for something to act on: an agent waiting, a machine down, a full disk,
// quota under 20%, failing CI. Everything else is green or dim. OK opens every herdr pane
// (DeckActivity). Off until boxdeck is set up:
//   am start -n dev.wicolian.boxdecktv/.MainActivity --es boxdeck_url http://100.x.y.z:8100 --es boxdeck_token bdr_...
// Polls every 20 seconds while Home is in front, nothing else.
final class AgentsPanel extends LinearLayout {
    static final int WAITING = 0xFFFFC447;
    private static final int POLL_MS = 20000;

    private final SharedPreferences prefs;
    private final int fg, fgSoft, fgDim, textPx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextView head, body;
    private String cost = "";
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
        head = line(fgDim, regular);
        body = line(fgSoft, regular);
        body.setLineSpacing(textPx * 0.25f, 1f);
        addView(head);
        LayoutParams lp = new LayoutParams(-2, -2);
        lp.topMargin = textPx / 2;
        addView(body, lp);
        showHead(false);

        // Focusable like a tile: a quiet fill and a "> open panes_" hint when focused; OK opens them.
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(radius);
        bg.setColor(0);
        setBackground(bg);
        setFocusable(true);
        setClickable(true);
        setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? focusFill : 0);
            showHead(has);
        });
        setOnClickListener(v -> getContext().startActivity(
                new Intent(getContext(), DeckActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
    }

    private void showHead(boolean focused) {
        head.setText((focused ? "> open panes_" : "$ boxdeck status") + cost);
        head.setTextColor(focused ? fg : fgDim);
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
                Deck.Snapshot s = null;
                String error = null;
                try { s = Deck.snapshot(prefs); }
                catch (Deck.Refused e) { error = "token refused (" + e.code + ") - mint a read-only one"; }
                catch (Exception e) { error = "boxdeck unreachable - is Tailscale on?"; }
                final Deck.Snapshot snap = s;
                final String err = error;
                main.post(() -> {
                    if (!running) return;
                    cost = snap == null ? "" : String.format(java.util.Locale.ROOT, " · ~$%.0f today", snap.cost());
                    showHead(hasFocus());
                    swap(snap == null ? dim(err) : render(snap));
                });
                try { Thread.sleep(POLL_MS); } catch (InterruptedException e) { return; }
            }
        }, "boxdeck-panel");
        worker.setDaemon(true);
        worker.start();
    }

    // A changed figure fades out and back in (120 ms + 180 ms), so the eye notices that something
    // moved without the panel flashing. An unchanged poll does nothing.
    private void swap(CharSequence text) {
        if (text.toString().equals(body.getText().toString())) return;
        if (body.getText().length() == 0) { body.setText(text); return; }
        body.animate().alpha(0.35f).setDuration(120).withEndAction(() -> {
            body.setText(text);
            body.animate().alpha(1f).setDuration(180).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        }).start();
    }

    void stop() {
        running = false;
        if (worker != null) worker.interrupt();
        worker = null;
    }

    private CharSequence dim(String s) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        append(out, s, fgDim);
        return out;
    }

    private CharSequence render(Deck.Snapshot s) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        for (Deck.Box b : s.boxes) {
            if (out.length() > 0) out.append("\n");
            append(out, b.name + "  ", fg);
            if (!b.ok) { append(out, "✕ offline", WAITING); continue; }
            String sep = "";
            if (!b.listed) {
                append(out, b.count + " agents" + (b.error != null ? " (" + b.error + ")" : ""), fgDim);
                sep = "   ";
            } else {
                int working = b.working(), waiting = b.waiting(), other = b.agents.size() - working - waiting;
                if (working > 0) { append(out, "● " + working + " working", fg); sep = "   "; }
                if (waiting > 0) { append(out, sep + "◐ " + waiting + " waiting", WAITING); sep = "   "; }
                if (other > 0) { append(out, sep + other + " outside herdr", fgDim); sep = "   "; }
                if (b.agents.isEmpty()) { append(out, "no agents", fgDim); sep = "   "; }
            }
            if (b.diskFull()) append(out, sep + "disk " + b.diskPct + "%", WAITING);
        }

        out.append("\n");
        int five = s.left("fiveHour"), week = s.left("sevenDay");
        if (five >= 0) {
            StringBuilder bar = new StringBuilder();
            for (int i = 0; i < 10; i++) bar.append(i < Math.round((100 - five) / 10f) ? '█' : '░');
            append(out, "claude 5h ", fgDim);
            append(out, bar.toString(), five < Deck.QUOTA_LOW ? WAITING : fg);
            append(out, " " + five + "% left", five < Deck.QUOTA_LOW ? WAITING : fgSoft);
        } else {
            append(out, "claude 5h -", fgDim);
        }
        if (week >= 0) {
            append(out, " · 7d ", fgDim);
            append(out, week + "% left", week < Deck.QUOTA_LOW ? WAITING : fgSoft);
        }

        JSONObject prs = s.github == null ? null : s.github.optJSONObject("prs");
        if (prs != null) {
            int open = prs.optInt("open"), failing = prs.optInt("failing"), pending = prs.optInt("pending");
            out.append("\n");
            append(out, "prs " + open + " open", fgSoft);
            if (failing > 0) append(out, " · ✕ " + failing + " failing", WAITING);
            if (pending > 0) append(out, " · " + pending + " pending", fgDim);
            if (open > 0 && failing == 0 && pending == 0) append(out, " · ✓ all passing", fgDim);
        }
        return out;
    }

    static void append(SpannableStringBuilder out, String s, int color) {
        int start = out.length();
        out.append(s);
        out.setSpan(new ForegroundColorSpan(color), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}
