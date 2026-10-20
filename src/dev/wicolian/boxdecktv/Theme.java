package dev.wicolian.boxdecktv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.List;

// The look of every screen: home, panes, screensaver. Six colours per theme, taken from the Omarchy
// desktop themes (https://omarchy.org), plus Terminal (green phosphor) and Classic (the original
// tv-detox wallpapers, white glass). A theme is a world, not a tint: the canvas, the surfaces and the
// text all come from it, and two colours carry meaning everywhere:
//   accent  where you are (focus) and what is running
//   warn    what wants you (an agent waiting, a machine down, low quota, failing CI)
// Picked on the TV (Settings > Theme), or set from adb:
//   --es theme "Tokyo Night"
//   --es theme_custom "bg=#1a1b26,surface=#24283b,fg=#c0caf5,dim=#565f89,accent=#7aa2f7,warn=#e0af68"
final class Theme {
    static final String CLASSIC = "Classic", TERMINAL = "Terminal", CUSTOM = "Custom";

    final String name;
    final int bg, surface, fg, dim, accent, warn;
    final boolean terminal, classic;

    private Theme(String name, int bg, int surface, int fg, int dim, int accent, int warn) {
        this.name = name;
        this.bg = bg;
        this.surface = surface;
        this.fg = fg;
        this.dim = dim;
        this.accent = accent;
        this.warn = warn;
        terminal = TERMINAL.equals(name);
        classic = CLASSIC.equals(name);
    }

    private static final List<Theme> BUILT_IN = new ArrayList<>();
    static {
        //            name            bg          surface     fg          dim         accent      warn
        add("Tokyo Night",   0x1a1b26, 0x24283b, 0xc0caf5, 0x565f89, 0x7aa2f7, 0xe0af68);
        add("Catppuccin",    0x1e1e2e, 0x313244, 0xcdd6f4, 0x6c7086, 0xcba6f7, 0xf9e2af);
        add("Gruvbox",       0x282828, 0x3c3836, 0xebdbb2, 0x928374, 0x8ec07c, 0xfabd2f);
        add("Nord",          0x2e3440, 0x3b4252, 0xeceff4, 0x7b88a1, 0x88c0d0, 0xebcb8b);
        add("Everforest",    0x2d353b, 0x3d484d, 0xd3c6aa, 0x859289, 0xa7c080, 0xdbbc7f);
        add("Kanagawa",      0x1f1f28, 0x2a2a37, 0xdcd7ba, 0x727169, 0x7e9cd8, 0xe6c384);
        add("Rose Pine",     0x191724, 0x26233a, 0xe0def4, 0x6e6a86, 0xc4a7e7, 0xf6c177);
        add("Matte Black",   0x121212, 0x1e1e1e, 0xeaeaea, 0x8a8a8d, 0xd8d8d8, 0xffb627);
        add("Osaka Jade",    0x111c18, 0x1a2b25, 0xc1c497, 0x5a7364, 0x549e6a, 0xe5c736);
        add("Ristretto",     0x2c2525, 0x403838, 0xe6d9db, 0x948a8b, 0xf38d70, 0xf9cc6c);
        add(TERMINAL,        0x030806, 0x0a120d, 0x39ff7a, 0x2c8a4d, 0x39ff7a, 0xffc447);
        add(CLASSIC,         0x15100f, 0x231d1f, 0xffffff, 0x99ffffff & 0xffffff, 0xffffff, 0xffc447);
    }

    private static void add(String name, int bg, int surface, int fg, int dim, int accent, int warn) {
        BUILT_IN.add(new Theme(name, 0xFF000000 | bg, 0xFF000000 | surface, 0xFF000000 | fg,
                0xFF000000 | dim, 0xFF000000 | accent, 0xFF000000 | warn));
    }

    // Built-in themes, then Custom when one is saved.
    static List<Theme> all(SharedPreferences p) {
        List<Theme> out = new ArrayList<>(BUILT_IN);
        Theme custom = custom(p.getString("theme_custom", null));
        if (custom != null) out.add(custom);
        return out;
    }

    // The saved theme. Older installs saved only a wallpaper: "Terminal" stays Terminal, any other
    // wallpaper stays Classic, and a fresh install starts on Tokyo Night.
    static Theme current(SharedPreferences p) {
        String name = p.getString("theme", null);
        if (name == null) {
            String paper = p.getString("wallpaper", null);
            name = paper == null ? "Tokyo Night" : TERMINAL.equals(paper) ? TERMINAL : CLASSIC;
        }
        for (Theme t : all(p)) if (t.name.equalsIgnoreCase(name)) return t;
        return BUILT_IN.get(0);
    }

    static boolean exists(SharedPreferences p, String name) {
        for (Theme t : all(p)) if (t.name.equalsIgnoreCase(name)) return true;
        return false;
    }

    // "bg=#1a1b26,surface=#24283b,fg=#c0caf5,dim=#565f89,accent=#7aa2f7,warn=#e0af68"; any missing
    // colour falls back to Tokyo Night's, a colour that doesn't parse makes the whole theme invalid.
    static Theme custom(String spec) {
        if (spec == null || spec.trim().isEmpty()) return null;
        Theme base = BUILT_IN.get(0);
        int bg = base.bg, surface = base.surface, fg = base.fg, dim = base.dim, accent = base.accent, warn = base.warn;
        try {
            for (String part : spec.split(",")) {
                String[] kv = part.split("=", 2);
                if (kv.length != 2) continue;
                int c = Color.parseColor(kv[1].trim()) | 0xFF000000;
                switch (kv[0].trim().toLowerCase(java.util.Locale.ROOT)) {
                    case "bg": bg = c; break;
                    case "surface": surface = c; break;
                    case "fg": fg = c; break;
                    case "dim": dim = c; break;
                    case "accent": accent = c; break;
                    case "warn": warn = c; break;
                }
            }
        } catch (Exception e) {
            return null;
        }
        return new Theme(CUSTOM, bg, surface, fg, dim, accent, warn);
    }

    // Text steps, from the theme's own two text colours.
    int fgSoft() { return (fg & 0x00FFFFFF) | 0xD9000000; }
    int fgDim() { return terminal ? 0x7839FF7A : classic ? 0xA8FFFFFF : dim; }
    // A colour at an alpha, for fills and hairlines.
    static int alpha(int color, int a) { return (color & 0x00FFFFFF) | (a << 24); }
    // A lighter step of the surface, for things that float above it (menus).
    int raised() { return blend(surface, fg, 0.06f); }

    static int blend(int a, int b, float t) {
        return Color.rgb(Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    // Font: Inter (bundled; the default) or JetBrains Mono (bundled; Terminal always uses it). Inter is
    // drawn with tabular figures where numbers line up. Setting "font": "sans" or "mono".
    private static Typeface sans, sansBold, mono, monoBold;
    static boolean mono(SharedPreferences p) { return "mono".equals(p.getString("font", "sans")); }
    private static boolean useMono(SharedPreferences p) { return mono(p) || TERMINAL.equals(current(p).name); }
    static Typeface regular(Context c, SharedPreferences p) { load(c); return useMono(p) ? mono : sans; }
    static Typeface bold(Context c, SharedPreferences p) { load(c); return useMono(p) ? monoBold : sansBold; }
    private static void load(Context c) {
        if (sans != null) return;
        sans = asset(c, "fonts/Inter-Regular.ttf", Typeface.create("sans-serif", Typeface.NORMAL));
        sansBold = asset(c, "fonts/Inter-SemiBold.ttf", Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mono = asset(c, "fonts/JetBrainsMono-Regular.ttf", Typeface.MONOSPACE);
        monoBold = asset(c, "fonts/JetBrainsMono-Bold.ttf", Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
    }
    private static Typeface asset(Context c, String path, Typeface fallback) {
        try { return Typeface.createFromAsset(c.getAssets(), path); } catch (Exception e) { return fallback; }
    }
}
