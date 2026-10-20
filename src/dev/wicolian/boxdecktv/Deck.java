package dev.wicolian.boxdecktv;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// Reads boxdeck (https://github.com/wicolian/boxdeck): the hub's /api/boxes lists every machine, then
// each machine's /api/agents lists its agents, with the herdr pane they run in when there is one.
// Every machine needs its own read-only token (bdr_): the hub's is boxdeck_token, another machine's
// is boxdeck_token_<name> (like boxdeck_token_mac). A machine without one shows its count only.
final class Deck {
    static final String PREFS = "config", URL_KEY = "boxdeck_url", TOKEN_KEY = "boxdeck_token";
    private static final int TIMEOUT_MS = 6000;

    static final class Agent {
        String box, kind, model, cwd, title, status, key;
        long secs;
        boolean waiting() { return "idle".equals(status) || "done".equals(status); }
        boolean working() { return "working".equals(status); }
        boolean inPane() { return status != null; }
    }

    static final class Box {
        String name, error;
        boolean ok = true, listed; // listed: its agents were read, not only counted
        int count, diskPct = -1;
        JSONObject usage;
        static final int DISK_FULL = 90; // percent: from here the disk shows, in amber
        boolean diskFull() { return diskPct >= DISK_FULL; }
        double costToday() {
            double c = 0;
            JSONObject today = usage == null ? null : usage.optJSONObject("today");
            if (today != null) for (String p : new String[] {"claude", "codex"}) {
                JSONObject t = today.optJSONObject(p);
                if (t != null) c += t.optDouble("costUsd", 0);
            }
            return c;
        }
        final List<Agent> agents = new ArrayList<>();
        int working() { int n = 0; for (Agent a : agents) if (a.working()) n++; return n; }
        int waiting() { int n = 0; for (Agent a : agents) if (a.waiting()) n++; return n; }
    }

    static final class Refused extends Exception {
        final int code;
        Refused(int code) { super("HTTP " + code); this.code = code; }
    }

    static boolean configured(SharedPreferences p) {
        return p.getString(URL_KEY, null) != null && p.getString(TOKEN_KEY, null) != null;
    }

    static List<Box> fetch(SharedPreferences p) throws Exception {
        String hub = p.getString(URL_KEY, ""), hubToken = p.getString(TOKEN_KEY, "");
        JSONArray all = new JSONArray(get(hub + "/api/boxes", hubToken));
        // Configured machines before discovered ones, so a machine keeps the name its token is saved under.
        JSONArray rows = new JSONArray();
        for (int pass = 0; pass < 2; pass++)
            for (int i = 0; i < all.length(); i++)
                if (all.getJSONObject(i).optBoolean("discovered") == (pass == 1)) rows.put(all.getJSONObject(i));
        List<Box> out = new ArrayList<>();
        // The hub can list a machine twice: once from its config and once found on the tailnet
        // ("discovered"). Keep the first row for each name or URL.
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.getJSONObject(i);
            String rowUrl = r.optString("url", "").replaceAll("/+$", "");
            if (!seen.add("n:" + r.optString("name")) | (!rowUrl.isEmpty() && !seen.add("u:" + rowUrl))) continue;
            Box b = new Box();
            b.name = r.optString("name", "box");
            b.ok = r.optBoolean("ok", true);
            b.count = r.optInt("agents", 0);
            b.usage = r.optJSONObject("usage");
            JSONObject health = r.optJSONObject("health");
            if (health != null) b.diskPct = health.optInt("diskPct", -1);
            boolean local = r.optBoolean("local");
            String url = local ? hub : r.optString("url", "").replaceAll("/+$", "");
            String token = local ? hubToken : p.getString(TOKEN_KEY + "_" + b.name, null);
            if (b.ok && token != null && !url.isEmpty()) {
                try {
                    JSONArray agents = new JSONArray(get(url + "/api/agents", token));
                    for (int j = 0; j < agents.length(); j++) b.agents.add(agent(b.name, agents.getJSONObject(j)));
                    b.listed = true;
                } catch (Exception e) {
                    b.error = e instanceof Refused ? "token refused" : "unreachable";
                }
            }
            // Waiting first, then working, then the rest; newest first inside each.
            Collections.sort(b.agents, (x, y) -> {
                int rx = x.waiting() ? 0 : x.working() ? 1 : 2, ry = y.waiting() ? 0 : y.working() ? 1 : 2;
                return rx != ry ? rx - ry : Long.compare(x.secs, y.secs);
            });
            out.add(b);
        }
        return out;
    }

    // Everything one poll knows: machines, the Claude quota (per account, so taken from the first
    // machine that reports it) and the GitHub summary, if boxdeck serves one.
    static final class Snapshot {
        final List<Box> boxes;
        final JSONObject github; // null when boxdeck has no /api/github or it is not ok
        Snapshot(List<Box> boxes, JSONObject github) { this.boxes = boxes; this.github = github; }

        JSONObject quota(String window) { // "fiveHour" or "sevenDay"
            for (Box b : boxes) {
                JSONObject q = b.usage == null ? null : b.usage.optJSONObject("quota");
                JSONObject c = q == null ? null : q.optJSONObject("claude");
                JSONObject w = c == null ? null : c.optJSONObject(window);
                if (w != null && w.has("pct")) return w;
            }
            return null;
        }

        // Percent left in a quota window, or -1 when nobody reports it.
        int left(String window) {
            JSONObject w = quota(window);
            return w == null ? -1 : 100 - (int) Math.round(Math.max(0, Math.min(100, w.optDouble("pct", 0))));
        }

        double cost() { double c = 0; for (Box b : boxes) c += b.costToday(); return c; }
        int waiting() { int n = 0; for (Box b : boxes) n += b.waiting(); return n; }
        int working() { int n = 0; for (Box b : boxes) n += b.working(); return n; }
    }

    static final int QUOTA_LOW = 20; // percent left: from here the quota shows, and alerts, in amber

    static Snapshot snapshot(SharedPreferences p) throws Exception {
        List<Box> boxes = fetch(p);
        JSONObject gh = null;
        try {
            JSONObject o = new JSONObject(get(p.getString(URL_KEY, "") + "/api/github", p.getString(TOKEN_KEY, "")));
            if (o.optBoolean("ok")) gh = o;
        } catch (Exception ignored) {} // older boxdeck, no gh on the hub, or gh is down: no PR line
        return new Snapshot(boxes, gh);
    }

    // "21:40" for an ISO time like "2026-10-20T19:39:59.77+00:00", in the TV's time zone.
    static String clock(String iso) {
        if (iso == null || iso.isEmpty()) return "";
        try {
            String s = iso.replaceAll("\\.\\d+", "").replace("Z", "+00:00");
            java.text.SimpleDateFormat in = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.ROOT);
            return new java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(in.parse(s));
        } catch (Exception e) {
            return "";
        }
    }

    private static Agent agent(String box, JSONObject o) {
        Agent a = new Agent();
        a.box = box;
        a.kind = o.optString("kind", "agent");
        a.model = o.optString("model", "");
        a.cwd = o.optString("cwd", "");
        a.secs = o.optLong("secs", 0);
        a.title = o.isNull("terminal_title_stripped") ? null : o.optString("terminal_title_stripped", null);
        a.status = o.isNull("agent_status") ? null : o.optString("agent_status", null);
        String tab = o.isNull("tab_id") ? null : o.optString("tab_id", null);
        a.key = box + "/" + (tab != null ? tab : "pid" + o.optLong("pid"));
        return a;
    }

    // "3d 4h", "1h 27m", "12m": how long an agent has run, short enough for a TV row.
    static String age(long secs) {
        long m = secs / 60, h = m / 60, d = h / 24;
        if (d > 0) return d + "d " + (h % 24) + "h";
        if (h > 0) return h + "h " + (m % 60) + "m";
        return Math.max(1, m) + "m";
    }

    static String get(String url, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new Refused(code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
                return buf.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }
}
