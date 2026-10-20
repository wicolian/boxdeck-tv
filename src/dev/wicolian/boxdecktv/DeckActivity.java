package dev.wicolian.boxdecktv;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

// Every herdr pane with an agent in it, on every machine, read from boxdeck. Waiting panes first
// (they want you), then working ones. Agents outside herdr have no status, so they are one summary
// line per machine instead of rows that say nothing. Up and down scroll, Back goes home.
public class DeckActivity extends Activity {
    private static final int REFRESH_MS = 15000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private TextView stamp;
    private boolean term, running;
    private int fg, fgSoft, fgDim, fillOn;
    private Typeface regular, bold;
    private float scale;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences p = getSharedPreferences(Deck.PREFS, 0);
        term = "Terminal".equals(p.getString("wallpaper", null));
        fg = term ? MainActivity.PHOSPHOR : Color.WHITE;
        fgSoft = term ? 0xC839FF7A : 0xE0FFFFFF;
        fgDim = term ? 0x7839FF7A : 0x99FFFFFF;
        fillOn = term ? 0x1F39FF7A : 0x38FFFFFF;
        regular = term ? Typeface.MONOSPACE : Typeface.create("sans-serif", Typeface.NORMAL);
        bold = term ? Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) : Typeface.create("sans-serif-medium", Typeface.NORMAL);
        scale = getResources().getDisplayMetrics().widthPixels / 1920f;
        getWindow().setBackgroundDrawable(new ColorDrawable(term ? 0xFF030504 : 0xFF0E0B0C));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(px(96), px(72), px(96), px(48));
        LinearLayout top = new LinearLayout(this);
        TextView title = text(px(34), fg, regular);
        title.setText(term ? "$ herdr panes --all-boxes" : "Agents");
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        stamp = text(px(24), fgDim, regular);
        top.addView(stamp);
        page.addView(top);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, 0, 1f);
        sp.topMargin = px(28);
        page.addView(scroll, sp);
        setContentView(page);
        TextView loading = text(px(28), fgDim, regular);
        loading.setText("reading boxdeck...");
        list.addView(loading);
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
        // Keep focus on the same pane across refreshes.
        View f = getCurrentFocus();
        Object focusedKey = f == null ? null : f.getTag();
        list.removeAllViews();
        stamp.setText("updated " + new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()));
        if (error != null) {
            TextView t = text(px(28), fgSoft, regular);
            t.setText(error);
            list.addView(t);
            return;
        }
        View restore = null;
        for (Deck.Box b : boxes) {
            LinearLayout head = new LinearLayout(this);
            head.setGravity(Gravity.BOTTOM);
            TextView name = text(px(32), fg, bold);
            name.setText(b.name);
            head.addView(name);
            TextView sum = text(px(24), fgDim, regular);
            sum.setText(!b.ok ? "   down" : !b.listed ? "   " + b.count + " agents" + (b.error != null ? ", " + b.error : ", no read-only token for it")
                    : "   " + b.working() + " working · " + b.waiting() + " waiting");
            if (!b.ok) sum.setTextColor(AgentsPanel.WAITING);
            head.addView(sum);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
            hp.topMargin = list.getChildCount() == 0 ? 0 : px(40);
            hp.bottomMargin = px(12);
            list.addView(head, hp);

            Map<String, Integer> outside = new TreeMap<>();
            int panes = 0;
            for (Deck.Agent a : b.agents) {
                if (!a.inPane()) { outside.put(a.kind, outside.containsKey(a.kind) ? outside.get(a.kind) + 1 : 1); continue; }
                View row = row(a);
                list.addView(row);
                panes++;
                if (a.key.equals(focusedKey)) restore = row;
            }
            if (b.listed && panes == 0 && outside.isEmpty()) {
                TextView t = text(px(26), fgDim, regular);
                t.setText("  no agents running");
                list.addView(t);
            }
            if (!outside.isEmpty()) {
                StringBuilder s = new StringBuilder("  + ");
                int n = 0;
                for (int c : outside.values()) n += c;
                s.append(n).append(" outside herdr: ");
                String sep = "";
                for (Map.Entry<String, Integer> e : outside.entrySet()) { s.append(sep).append(e.getValue()).append(' ').append(e.getKey()); sep = ", "; }
                TextView t = text(px(24), fgDim, regular);
                t.setText(s);
                LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-1, -2);
                op.topMargin = px(10);
                list.addView(t, op);
            }
        }
        if (restore != null) restore.requestFocus();
        else for (int i = 0; i < list.getChildCount(); i++) if (list.getChildAt(i).isFocusable()) { list.getChildAt(i).requestFocus(); break; }
    }

    // One pane: status, the pane's title, then where and how long, quieter, on the right.
    private View row(Deck.Agent a) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(px(16), px(14), px(16), px(14));
        r.setFocusable(true);
        r.setTag(a.key);
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(14));
        bg.setColor(0);
        r.setBackground(bg);
        r.setOnFocusChangeListener((v, has) -> bg.setColor(has ? fillOn : 0));

        int color = a.waiting() ? AgentsPanel.WAITING : fg;
        TextView mark = text(px(28), color, bold);
        mark.setText(a.waiting() ? "◐" : "●");
        r.addView(mark, new LinearLayout.LayoutParams(px(48), -2));
        TextView state = text(px(24), color, regular);
        state.setText(a.waiting() ? "waiting" : "working");
        r.addView(state, new LinearLayout.LayoutParams(px(150), -2));
        TextView title = text(px(28), a.waiting() ? fg : fgSoft, regular);
        title.setText(a.title != null && !a.title.isEmpty() ? a.title : a.kind);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView meta = text(px(22), fgDim, regular);
        String model = a.model == null || a.model.isEmpty() ? a.kind : a.model.replace("claude-", "");
        meta.setText(shortCwd(a.cwd) + "  ·  " + model + "  ·  " + Deck.age(a.secs));
        meta.setSingleLine(true);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-2, -2);
        mp.leftMargin = px(24);
        r.addView(meta, mp);
        return r;
    }

    // "~/codes/databrain/frontend-mono-worktrees/v2only-r3-final" -> "…/frontend-mono-worktrees/v2only-r3-final"
    private static String shortCwd(String cwd) {
        if (cwd == null) return "";
        String[] parts = cwd.split("/");
        if (parts.length <= 3) return cwd;
        return "…/" + parts[parts.length - 2] + "/" + parts[parts.length - 1];
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
