package id.musikpjj.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/** Membungkus situs pemutar musik dalam WebView; pemutaran latar belakang ditangani PlaybackService. */
public class MainActivity extends Activity {
    static final String ACTION_CHANGE_SERVER = "id.musikpjj.app.CHANGE_SERVER";
    private static final int REQ_FILE = 11, REQ_NOTIF = 12;
    private static final int BG = 0xFF0D1320;

    private static volatile MainActivity inst;

    private WebView web;
    private ProgressBar bar;
    private LinearLayout errBox;
    private TextView errMsg;
    private SharedPreferences prefs;
    private ValueCallback<Uri[]> fileCb;
    private String serverUrl;
    private AlertDialog serverDialog;

    /* ---------- dipanggil dari PlaybackService / PlayerBridge ---------- */

    static void js(final String code) {
        final MainActivity a = inst;
        if (a == null) return;
        a.runOnUiThread(() -> { if (a.web != null) a.web.evaluateJavascript(code, null); });
    }

    static void onPlaybackStarted() {
        final MainActivity a = inst;
        if (a != null) a.runOnUiThread(a::maybeAskBattery);
    }

    /* ---------- siklus hidup ---------- */

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        inst = this;
        prefs = getSharedPreferences("musik", MODE_PRIVATE);
        serverUrl = prefs.getString("server", null);
        buildUi();

        if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("notif_asked", false)
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean("notif_asked", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }

        if (serverUrl == null || ACTION_CHANGE_SERVER.equals(getIntent().getAction())) {
            askServer();
        } else {
            web.loadUrl(serverUrl);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (ACTION_CHANGE_SERVER.equals(intent.getAction())) askServer();
    }

    @Override
    protected void onDestroy() {
        if (inst == this) inst = null;
        // Activity benar-benar ditutup (mis. digeser dari recent apps): suara ikut berhenti, hentikan layanan.
        PlaybackService.shutdown();
        if (serverDialog != null) serverDialog.dismiss();
        if (web != null) {
            ((ViewGroup) web.getParent()).removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }

    // Sengaja TIDAK memanggil web.onPause()/pauseTimers(): itu akan menghentikan musik di latar belakang.

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (errBox.getVisibility() == View.VISIBLE) { moveTaskToBack(true); return; }
        if (web.canGoBack()) web.goBack();   // mis. menutup layar pemutar penuh
        else moveTaskToBack(true);           // musik tetap jalan di latar belakang
    }

    /* ---------- UI ---------- */

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + .5f); }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        web = new WebView(this);
        web.setBackgroundColor(BG);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.GONE);
        root.addView(bar, new FrameLayout.LayoutParams(-1, dp(3)));

        errBox = new LinearLayout(this);
        errBox.setOrientation(LinearLayout.VERTICAL);
        errBox.setGravity(Gravity.CENTER);
        errBox.setBackgroundColor(BG);
        errBox.setPadding(dp(32), dp(32), dp(32), dp(32));
        errBox.setVisibility(View.GONE);
        TextView t = new TextView(this);
        t.setText("Tidak bisa terhubung ke server");
        t.setTextColor(Color.WHITE); t.setTextSize(20); t.setGravity(Gravity.CENTER);
        errMsg = new TextView(this);
        errMsg.setTextColor(0xFFAAB4C8); errMsg.setGravity(Gravity.CENTER);
        errMsg.setPadding(0, dp(8), 0, dp(24));
        Button retry = new Button(this); retry.setText("Coba lagi");
        retry.setOnClickListener(v -> { errBox.setVisibility(View.GONE); web.reload(); });
        Button change = new Button(this); change.setText("Ubah alamat server");
        change.setOnClickListener(v -> askServer());
        errBox.addView(t); errBox.addView(errMsg); errBox.addView(retry); errBox.addView(change);
        root.addView(errBox, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        setupWebView();
    }

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false); // lagu berikutnya harus bisa jalan otomatis di latar belakang
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setTextZoom(100);
        s.setUserAgentString(s.getUserAgentString() + " MusikPJJApp/1.0");
        CookieManager.getInstance().setAcceptCookie(true);
        web.addJavascriptInterface(new PlayerBridge(this), "MPBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                if (!r.isForMainFrame()) return false;
                Uri u = r.getUrl();
                String host = serverUrl == null ? null : Uri.parse(serverUrl).getHost();
                if (("http".equals(u.getScheme()) || "https".equals(u.getScheme())) && u.getHost() != null && u.getHost().equals(host)) {
                    return false;
                }
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (ActivityNotFoundException ignored) { }
                return true;
            }
            @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap f) {
                errBox.setVisibility(View.GONE);
            }
            @Override public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript(PlayerBridge.HOOK_JS, null);
                CookieManager.getInstance().flush();
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) {
                    errMsg.setText(e.getDescription() + "\n\nPastikan HP terhubung internet dan alamat server benar (harus diawali https://).");
                    errBox.setVisibility(View.VISIBLE);
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int p) {
                bar.setProgress(p);
                bar.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
            }
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    startActivityForResult(p.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    fileCb = null;
                    Toast.makeText(MainActivity.this, "Tidak bisa membuka pemilih file.", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }
        });

        web.setDownloadListener((url, ua, cd, mime, len) -> {
            try {
                String name = URLUtil.guessFileName(url, cd, mime);
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                if (mime != null && !mime.isEmpty()) r.setMimeType(mime);
                String ck = CookieManager.getInstance().getCookie(url);
                if (ck != null) r.addRequestHeader("Cookie", ck);
                r.addRequestHeader("User-Agent", ua);
                r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(r);
                Toast.makeText(this, "Mengunduh " + name, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) { }
            }
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE && fileCb != null) {
            fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            fileCb = null;
            return;
        }
        super.onActivityResult(req, res, data);
    }

    /* ---------- alamat server ---------- */

    static String normalize(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        if (!s.matches("(?i)^https?://.*")) s = "https://" + s;
        Uri u = Uri.parse(s);
        if (u.getHost() == null || u.getHost().isEmpty()) return null;
        if (s.toLowerCase().endsWith("/index.php")) s = s.substring(0, s.length() - "index.php".length());
        String last = u.getLastPathSegment();
        if (!s.endsWith("/") && (last == null || !last.contains("."))) s += "/";
        return s;
    }

    private void askServer() {
        if (serverDialog != null && serverDialog.isShowing()) return;
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        et.setHint("https://situs-anda.com/musik/");
        et.setSingleLine(true);
        if (serverUrl != null) et.setText(serverUrl);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(et);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Alamat server musik")
                .setMessage("Isi alamat tempat aplikasi musik Anda dipasang (folder yang berisi index.php).")
                .setView(box)
                .setCancelable(serverUrl != null)
                .setPositiveButton("Simpan", null);
        if (serverUrl != null) b.setNegativeButton("Batal", null);
        serverDialog = b.create();
        serverDialog.setOnShowListener(d -> serverDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String n = normalize(et.getText().toString());
            if (n == null) { et.setError("Alamat tidak valid"); return; }
            boolean changed = !n.equals(serverUrl);
            serverUrl = n;
            prefs.edit().putString("server", n).apply();
            serverDialog.dismiss();
            errBox.setVisibility(View.GONE);
            if (changed || web.getUrl() == null) web.loadUrl(n); else web.reload();
        }));
        serverDialog.show();
    }

    /* ---------- hemat baterai ---------- */

    private void maybeAskBattery() {
        if (prefs.getBoolean("batt_asked", false) || isFinishing()) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        prefs.edit().putBoolean("batt_asked", true).apply();
        new AlertDialog.Builder(this)
                .setTitle("Agar musik tidak berhenti")
                .setMessage("Beberapa HP menghentikan aplikasi di latar belakang untuk menghemat baterai. "
                        + "Izinkan aplikasi ini berjalan tanpa pembatasan baterai supaya musik tetap terputar saat layar mati.")
                .setPositiveButton("Izinkan", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    }
                })
                .setNegativeButton("Nanti", null)
                .show();
    }
}
