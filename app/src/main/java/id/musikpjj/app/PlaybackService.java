package id.musikpjj.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

/**
 * Layanan latar depan (foreground service) bertipe mediaPlayback.
 * Menjaga proses tetap hidup saat layar mati / aplikasi di latar belakang, menampilkan
 * notifikasi kontrol media + MediaSession (layar kunci, tombol headset), dan menahan wake lock.
 * Suara itu sendiri tetap diputar oleh elemen <audio> di WebView.
 */
public class PlaybackService extends Service {
    private static final String TAG = "MusikPJJ";
    private static final String CHANNEL = "playback";
    private static final int NOTIF_ID = 7;
    private static final String ACT_TOGGLE = "id.musikpjj.app.TOGGLE";
    private static final String ACT_PREV = "id.musikpjj.app.PREV";
    private static final String ACT_NEXT = "id.musikpjj.app.NEXT";
    private static final String ACT_CLOSE = "id.musikpjj.app.CLOSE";

    // Status terakhir (dibaca saat layanan baru dibuat).
    private static volatile boolean sPlaying;
    private static volatile String sTitle = "";
    private static volatile String sArtist = "";
    private static volatile long sPos, sDur;
    private static volatile PlaybackService sInst;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private MediaSessionCompat session;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private boolean noisyRegistered;
    private boolean closing;

    /* ---------- API statis ---------- */

    /** Dipanggil dari thread JavaScript. */
    static void push(Context app, boolean playing, String title, String artist, long pos, long dur) {
        sPlaying = playing; sTitle = title == null ? "" : title; sArtist = artist == null ? "" : artist;
        sPos = pos; sDur = dur;
        final PlaybackService s = sInst;
        if (s != null) {
            MAIN.post(s::refresh);
        } else if (playing) {
            try {
                ContextCompat.startForegroundService(app, new Intent(app, PlaybackService.class));
            } catch (Exception e) {
                Log.w(TAG, "Tidak bisa memulai layanan: " + e);
            }
        }
    }

    static void shutdown() {
        sPlaying = false;
        final PlaybackService s = sInst;
        if (s != null) MAIN.post(s::close);
    }

    /* ---------- siklus hidup ---------- */

    @Override
    public void onCreate() {
        super.onCreate();
        sInst = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Pemutaran musik", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);

        session = new MediaSessionCompat(this, "MusikPJJ");
        session.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { MainActivity.js("window.__mp&&__mp.act('play')"); }
            @Override public void onPause() { MainActivity.js("window.__mp&&__mp.act('pause')"); }
            @Override public void onSkipToNext() { MainActivity.js("window.__mp&&__mp.act('next')"); }
            @Override public void onSkipToPrevious() { MainActivity.js("window.__mp&&__mp.act('prev')"); }
            @Override public void onSeekTo(long pos) { MainActivity.js("window.__mp&&__mp.act('seek'," + pos + ")"); }
            @Override public void onStop() { close(); }
        });
        session.setActive(true);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "musikpjj:playback");
        wake.setReferenceCounted(false);
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            int mode = Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifi = wm.createWifiLock(mode, "musikpjj:wifi");
            wifi.setReferenceCounted(false);
        } catch (Exception e) {
            Log.w(TAG, "WifiLock: " + e);
        }

        ContextCompat.registerReceiver(this, noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_EXPORTED);
        noisyRegistered = true;

        updateSession();
        int type = Build.VERSION.SDK_INT >= 29 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK : 0;
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type);
        updateLocks();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACT_TOGGLE.equals(a)) MainActivity.js("window.__mp&&__mp.act('toggle')");
        else if (ACT_PREV.equals(a)) MainActivity.js("window.__mp&&__mp.act('prev')");
        else if (ACT_NEXT.equals(a)) MainActivity.js("window.__mp&&__mp.act('next')");
        else if (ACT_CLOSE.equals(a)) {
            MainActivity.js("window.__mp&&__mp.act('pause')");
            close();
        }
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        if (sInst == this) sInst = null;
        if (noisyRegistered) { try { unregisterReceiver(noisy); } catch (Exception ignored) { } }
        releaseLocks();
        if (session != null) { session.setActive(false); session.release(); }
        super.onDestroy();
    }

    /* ---------- pembaruan ---------- */

    private void refresh() {
        if (closing) return;
        updateSession();
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification());
        updateLocks();
    }

    private void close() {
        closing = true;
        sPlaying = false;
        if (sInst == this) sInst = null;
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void updateSession() {
        MediaMetadataCompat.Builder m = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title())
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, sArtist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, sDur > 0 ? sDur : -1);
        session.setMetadata(m.build());
        long actions = PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_SEEK_TO
                | PlaybackStateCompat.ACTION_STOP;
        session.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(sPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
                        sPos, sPlaying ? 1f : 0f, SystemClock.elapsedRealtime())
                .build());
    }

    private String title() { return sTitle.isEmpty() ? getString(R.string.app_name) : sTitle; }

    private PendingIntent action(String a, int code) {
        Intent i = new Intent(this, PlaybackService.class).setAction(a);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_music)
                .setContentTitle(title())
                .setContentText(sArtist.isEmpty() ? null : sArtist)
                .setContentIntent(content)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(sPlaying)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(android.R.drawable.ic_media_previous, "Sebelumnya", action(ACT_PREV, 1))
                .addAction(sPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        sPlaying ? "Jeda" : "Putar", action(ACT_TOGGLE, 2))
                .addAction(android.R.drawable.ic_media_next, "Berikutnya", action(ACT_NEXT, 3))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Tutup", action(ACT_CLOSE, 4))
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    private void updateLocks() {
        if (sPlaying) {
            wake.acquire(4 * 60 * 60 * 1000L); // diperpanjang tiap pembaruan (tiap beberapa detik)
            if (wifi != null && !wifi.isHeld()) wifi.acquire();
        } else {
            releaseLocks();
        }
    }

    private void releaseLocks() {
        if (wake != null && wake.isHeld()) wake.release();
        if (wifi != null && wifi.isHeld()) wifi.release();
    }

    /** Cabut headset / pindah dari Bluetooth: jeda otomatis. */
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            MainActivity.js("window.__mp&&__mp.act('pause')");
        }
    };
}
