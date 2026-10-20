package dev.wicolian.boxdecktv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Runs in the background so two things work even while Netflix is on:
//  1. "needs you" toasts: when a herdr pane's agent goes from working to idle or done, the TV shows
//     "◐ box · Compat-plugin worker report is waiting" for a few seconds. The first read after a start
//     only learns the current state, so a restart never floods the screen.
//  2. Wireless debugging stays on: Google TV turns it off at every restart, which locks the laptop
//     out. With WRITE_SECURE_SETTINGS granted once over adb
//       adb shell pm grant dev.wicolian.boxdecktv android.permission.WRITE_SECURE_SETTINGS
//     this turns it back on whenever it finds it off. Without the grant it does nothing.
// Started by the home screen, which Android opens at every boot. Polls every 30 seconds.
public class WatchService extends Service {
    private static final int POLL_MS = 30000;
    private static final String CHANNEL = "watch";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, String> last = new HashMap<>();
    private boolean seeded;
    private volatile boolean running;
    private Thread worker;

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
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running) return START_STICKY;
        running = true;
        worker = new Thread(() -> {
            while (running) {
                keepAdbOn(this);
                poll();
                try { Thread.sleep(POLL_MS); } catch (InterruptedException e) { return; }
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
        List<Deck.Box> boxes;
        try { boxes = Deck.fetch(p); } catch (Exception e) { connectTailscale(); return; }
        StringBuilder msg = new StringBuilder();
        int fresh = 0;
        for (Deck.Box b : boxes) for (Deck.Agent a : b.agents) {
            if (!a.inPane()) continue;
            String before = last.put(a.key, a.status);
            if (seeded && a.waiting() && "working".equals(before)) {
                fresh++;
                if (msg.length() > 0) msg.append('\n');
                msg.append("◐ ").append(a.box).append(" · ").append(a.title != null ? a.title : a.kind).append(" is waiting");
            }
        }
        seeded = true;
        if (fresh == 0) return;
        final String text = fresh > 3 ? fresh + " agents are waiting for you" : msg.toString();
        main.post(() -> Toast.makeText(getApplicationContext(), text, Toast.LENGTH_LONG).show());
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
