package id.musikpjj.app;

import android.content.Context;
import android.webkit.JavascriptInterface;

/** Dipanggil dari JavaScript halaman web (objek "MPBridge") untuk melaporkan status pemutar. */
class PlayerBridge {
    /** Skrip yang disuntikkan ke halaman: memantau elemen <audio id="audio"> milik aplikasi web. */
    static final String HOOK_JS =
        "(function(){"
      + "var au=document.getElementById('audio');"
      + "if(!au||window.__mpHooked||!window.MPBridge)return;window.__mpHooked=true;"
      + "function rep(){var ms=navigator.mediaSession,m=ms&&ms.metadata;"
      + "MPBridge.update(!au.paused&&!au.ended&&!!au.src,m?String(m.title||''):'',m?String(m.artist||''):'',"
      + "Math.round((au.currentTime||0)*1000),isFinite(au.duration)?Math.round(au.duration*1000):0);}"
      + "['play','playing','loadedmetadata','durationchange','seeked','loadstart'].forEach(function(e){au.addEventListener(e,rep)});"
      + "au.addEventListener('pause',function(){if(!au.ended)rep()});"
      + "['ended','emptied'].forEach(function(e){au.addEventListener(e,function(){setTimeout(rep,1500)})});"
      + "setInterval(function(){if(!au.paused)rep()},5000);"
      + "window.addEventListener('pagehide',function(){MPBridge.stopped()});"
      + "var q=function(s){var b=document.querySelector(s);if(b)b.click()};"
      + "window.__mp={act:function(a,p){"
      + "if(a==='play'){if(au.src)au.play().catch(function(){});else q('.js-pp')}"
      + "else if(a==='pause'){au.pause()}"
      + "else if(a==='toggle'){if(au.paused)window.__mp.act('play');else au.pause()}"
      + "else if(a==='next'){q('.js-next')}"
      + "else if(a==='prev'){q('.js-prev')}"
      + "else if(a==='seek'){au.currentTime=p/1000}}};"
      + "rep();"
      + "})();";

    private final Context app;

    PlayerBridge(Context app) { this.app = app.getApplicationContext(); }

    @JavascriptInterface
    public void update(boolean playing, String title, String artist, long posMs, long durMs) {
        PlaybackService.push(app, playing, title, artist, posMs, durMs);
        if (playing) MainActivity.onPlaybackStarted();
    }

    @JavascriptInterface
    public void stopped() {
        PlaybackService.shutdown();
    }
}
