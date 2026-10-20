package dev.wicolian.boxdecktv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

// One agent, as a compact card. Used by the home screen's strip and by the panes screen, so an agent
// looks the same everywhere:
//
//   ● Waiting · box                    1h 44m
//   OAuth login status
//
// The status light is the signature: amber and steady when the agent waits for you, the theme's accent
// and breathing (see HeroRow.breath) while it works. Solid colours only: this TV's GPU is slow at
// blending, so nothing here is translucent.
final class AgentCard {
    static final int HEIGHT = 168, RADIUS = 22;

    interface Click { void on(); }

    // led: working lights are added here, for the caller's breathing loop.
    static FrameLayout make(Context c, Theme theme, Typeface regular, Typeface bold, float scale, int width,
                            String key, boolean waiting, boolean empty, String status, String box, String age,
                            String title, List<View> led, Click click) {
        int tone = empty ? theme.fgDim() : waiting ? theme.warn : theme.accent;
        int under = Theme.blend(theme.bg, Color.BLACK, 0.18f);
        int surface = Theme.blend(under, theme.surface, 0.95f) | 0xFF000000;
        int line = Theme.blend(surface, theme.fg, 0.08f);

        FrameLayout wrap = new FrameLayout(c);
        wrap.setClipChildren(false);
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(px(scale, 26), px(scale, 24), px(scale, 26), px(scale, 22));
        final GradientDrawable face = waiting
                ? new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {Theme.blend(surface, theme.warn, 0.12f), surface, surface})
                : new GradientDrawable();
        if (!waiting) face.setColor(surface);
        face.setCornerRadius(px(scale, RADIUS));
        face.setStroke(Math.max(1, px(scale, 2)), line);
        card.setBackground(face);

        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(c);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(tone);
        dot.setBackground(d);
        top.addView(dot, new LinearLayout.LayoutParams(px(scale, 12), px(scale, 12)));
        if (!empty && !waiting && led != null) led.add(dot);
        TextView st = text(c, px(scale, 21), tone, bold);
        st.setText(status);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.leftMargin = px(scale, 12);
        top.addView(st, sp);
        TextView where = text(c, px(scale, 21), theme.fgDim(), regular);
        where.setText(box == null || box.isEmpty() ? "" : "  ·  " + box);
        where.setSingleLine(true);
        where.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(where, new LinearLayout.LayoutParams(0, -2, 1f));
        if (age != null && !age.isEmpty()) {
            TextView ag = text(c, px(scale, 21), theme.fgDim(), regular);
            ag.setText(age);
            ag.setFontFeatureSettings("tnum");
            top.addView(ag);
        }
        card.addView(top);

        TextView t = text(c, px(scale, 28), theme.fg, bold);
        t.setText(title);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setLineSpacing(px(scale, 3), 1f);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = px(scale, 16);
        card.addView(t, tp);

        card.setFocusable(true);
        card.setTag(key);
        card.setOnFocusChangeListener((v, has) -> {
            face.setStroke(Math.max(1, px(scale, has ? 4 : 2)), has ? theme.accent : line);
            v.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f).setDuration(200)
                    .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        });
        if (click != null) card.setOnClickListener(v -> click.on());
        wrap.addView(card, new FrameLayout.LayoutParams(px(scale, width), px(scale, HEIGHT)));
        return wrap;
    }

    static String model(Deck.Agent a) {
        return a.model == null || a.model.isEmpty() ? a.kind : a.model.replace("claude-", "");
    }

    private static int px(float scale, int v) { return Math.round(v * scale); }

    private static TextView text(Context c, int size, int color, Typeface tf) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        return t;
    }
}
