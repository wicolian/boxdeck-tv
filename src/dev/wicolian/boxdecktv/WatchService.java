package dev.wicolian.boxdecktv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Runs in the background, so all of this works while Netflix is on:
//
// Toasts, only on a change and never on a repeat, at most one per poll, most urgent first:
//   ✕ mac is offline                                  a machine went down
//   ◐ box · Compat-plugin worker report is waiting    a herdr pane went from working to waiting
//   ✕ CI failing · databrainhq/frontend-mono#8551     one of your PRs started failing
//   Claude 5h quota: 18% left, resets 21:40           a quota window dropped under 20% left
//   box disk is 92% full                              a disk went over 90%
// The first poll after a start only learns the state, so a restart never floods the screen. While the
// screen is off nothing is shown; instead, when it comes back on after 30 minutes or more:
//   While you were away: 3 finished · 1 needs you · ~$40 spent
//
// It also keeps the TV reachable after a restart: wireless debugging back on (needs
// WRITE_SECURE_SETTINGS, granted once over adb by install.sh) and Tailscale connected.
// Started by the home screen, which Android opens at every boot. Polls every 30 seconds.
public class WatchService extends Service {
    private static final int POLL_MS = 30000, MAX_LINES = 2; // Android cuts a toast at two lines
    private static final long AWAY_MS = 30 * 60 * 1000L;
    private static final String CHANNEL = "watch";
    private final Handler main = new Handler(Looper.getMainLooper());

    // What the last poll saw, to alert on changes only.
    private final Map<String, String> paneStatus = new HashMap<>();
    private final Map<String, Boolean> boxOk = new HashMap<>(), diskFull = new HashMap<>();
    private final Map<String, String> prCi = new HashMap<>();
    private int fiveLeft = -1, weekLeft = -1;
    private boolean seeded;

    // The screen: off means no toasts; back on after a while means a summary.
    private volatile boolean screenOn = true, welcomeDue;
    private long offAt;
    private double offCost;
    private final Set<String> workingAtOff = new HashSet<>();
    private Deck.Snapshot lastSnapshot;

    private volatile boolean running;
    private Thread worker;

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                screenOn = false;
                offAt = System.currentTimeMillis();
                synchronized (workingAtOff) {
                    workingAtOff.clear();
                    if (lastSnapshot != null) {
                        offCost = lastSnapshot.cost();
                        for (Deck.Box b : lastSnapshot.boxes) for (Deck.Agent a : b.agents) if (a.working()) workingAtOff.add(a.key);
                    }
                }
            } else if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) {
                screenOn = true;
                if (offAt > 0 && System.currentTimeMillis() - offAt >= AWAY_MS) welcomeDue = true;
                if (worker != null) worker.interrupt(); // poll now, not in up to 30 seconds
            }
        }
    };

    static void start(Context c) {
        try { c.startForegroundService(new Intent(c, WatchService.class)); } catch (Exception ignored) {}
    }

    // Turns wireless debugging back on if it was turned off. Safe to call often.
    static void keepAdbOn(Context c) {
        if (c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) return;
        ContentResolver cr = c.getContentResolver();
        try {
            if (Settings.Global.getInt(cr, "adb_wifi_enabled", 0) != 1) Settings.Global.putInt(cr, "adb_wifi_enabled", 1);
        } catch (Exception ignored) {}
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Agent watch", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Watching your agents")
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .build();
        if (android.os.Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
        PowerManager pm = getSystemService(PowerManager.class);
        screenOn = pm == null || pm.isInteractive();
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screen, f);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // A sample of every toast, to check how they look over another app:
        //   adb shell am start -n dev.wicolian.boxdecktv/.MainActivity --ez demo true
        if (intent != null && intent.getBooleanExtra("demo", false))
            main.post(() -> Toast.makeText(getApplicationContext(),
                    "While you were away: 3 finished · 1 needs you · ~$40 spent\n+ 2 more on the home screen",
                    Toast.LENGTH_LONG).show());
        if (running) return START_STICKY;
        running = true;
        worker = new Thread(() -> {
            while (running) {
                keepAdbOn(this);
                try { poll(); } catch (Exception ignored) {}
                try { Thread.sleep(POLL_MS); } catch (InterruptedException e) { /* screen on: poll now */ }
            }
        }, "boxdeck-watch");
        worker.setDaemon(true);
        worker.start();
        return START_STICKY;
    }

    // Tailscale does not reconnect by itself after a restart, and Android's always-on VPN setting is
    // not honoured when set from adb. Tailscale's own automation broadcast does work, so it is sent
    // at start and whenever boxdeck can't be reached, at most every two minutes.
    private long lastConnect;
    private void connectTailscale() {
        long now = System.currentTimeMillis();
        if (now - lastConnect < 120000) return;
        lastConnect = now;
        try {
            sendBroadcast(new Intent("com.tailscale.ipn.CONNECT_VPN")
                    .setClassName("com.tailscale.ipn", "com.tailscale.ipn.IPNReceiver")
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES));
        } catch (Exception ignored) {}
    }

    private void poll() {
        SharedPreferences p = getSharedPreferences(Deck.PREFS, 0);
        if (!Deck.configured(p)) return;
        if (lastConnect == 0) connectTailscale();
        Deck.Snapshot s;
        try { s = Deck.snapshot(p); } catch (Exception e) { connectTailscale(); return; }

        List<String> offline = new ArrayList<>(), waiting = new ArrayList<>(), ci = new ArrayList<>(),
                quota = new ArrayList<>(), disk = new ArrayList<>();
        for (Deck.Box b : s.boxes) {
            Boolean wasOk = boxOk.put(b.name, b.ok);
            if (wasOk != null && wasOk && !b.ok) offline.add("✕ " + b.name + " is offline");
            Boolean wasFull = diskFull.put(b.name, b.diskFull());
            if (wasFull != null && !wasFull && b.diskFull()) disk.add(b.name + " disk is " + b.diskPct + "% full");
            for (Deck.Agent a : b.agents) {
                if (!a.inPane()) continue;
                String before = paneStatus.put(a.key, a.status);
                if (a.waiting() && "working".equals(before))
                    waiting.add("◐ " + a.box + " · " + (a.title != null ? a.title : a.kind) + " is waiting");
            }
        }
        crossed(quota, "5h", fiveLeft, fiveLeft = s.left("fiveHour"), s.quota("fiveHour"));
        crossed(quota, "7d", weekLeft, weekLeft = s.left("sevenDay"), s.quota("sevenDay"));
        JSONObject prs = s.github == null ? null : s.github.optJSONObject("prs");
        JSONArray items = prs == null ? null : prs.optJSONArray("items");
        if (items != null) for (int i = 0; i < items.length(); i++) {
            JSONObject pr = items.optJSONObject(i);
            if (pr == null) continue;
            String key = pr.optString("repo") + "#" + pr.optInt("number");
            String now = pr.optString("ci"), before = prCi.put(key, now);
            if ("failing".equals(now) && before != null && !"failing".equals(before)) ci.add("✕ CI failing · " + key);
        }

        boolean first = !seeded;
        seeded = true;
        lastSnapshot = s;
        if (first || !screenOn) return;

        List<String> lines = new ArrayList<>();
        if (welcomeDue) {
            welcomeDue = false;
            String w = welcome(s);
            if (w != null) lines.add(w);
            waiting.clear(); // counted in the summary
        }
        lines.addAll(offline);
        lines.addAll(waiting);
        lines.addAll(ci);
        lines.addAll(quota);
        lines.addAll(disk);
        if (lines.isEmpty()) return;
        StringBuilder text = new StringBuilder();
        // Two lines at most: the most urgent first, and a count for the rest rather than a cut-off line.
        int shown = lines.size() <= MAX_LINES ? lines.size() : MAX_LINES - 1;
        for (int i = 0; i < shown; i++) text.append(i > 0 ? "\n" : "").append(lines.get(i));
        if (lines.size() > shown) text.append("\n+ ").append(lines.size() - shown).append(" more on the home screen");
        final String msg = text.toString();
        main.post(() -> Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show());
    }

    // A quota window that just dropped under QUOTA_LOW percent left.
    private static void crossed(List<String> out, String label, int before, int now, JSONObject window) {
        if (before < 0 || now < 0 || !(before >= Deck.QUOTA_LOW && now < Deck.QUOTA_LOW)) return;
        String reset = window == null ? "" : Deck.clock(window.optString("resetsAt"));
        out.add("Claude " + label + " quota: " + now + "% left" + (reset.isEmpty() ? "" : ", resets " + reset));
    }

    // "While you were away: 3 finished · 1 needs you · ~$40 spent", or null when nothing happened.
    private String welcome(Deck.Snapshot s) {
        int finished = 0;
        Set<String> now = new HashSet<>();
        for (Deck.Box b : s.boxes) for (Deck.Agent a : b.agents) if (a.working()) now.add(a.key);
        synchronized (workingAtOff) { for (String k : workingAtOff) if (!now.contains(k)) finished++; }
        int needs = s.waiting();
        double spent = s.cost() >= offCost ? s.cost() - offCost : s.cost(); // the day rolled over
        List<String> parts = new ArrayList<>();
        if (finished > 0) parts.add(finished + " finished");
        if (needs > 0) parts.add(needs + " need" + (needs == 1 ? "s" : "") + " you");
        if (spent >= 1) parts.add(String.format(java.util.Locale.ROOT, "~$%.0f spent", spent));
        if (parts.isEmpty()) return null;
        return "While you were away: " + android.text.TextUtils.join(" · ", parts);
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        try { unregisterReceiver(screen); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
