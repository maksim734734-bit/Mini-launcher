package com.example.rutubetv;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;

import org.json.JSONObject;

// Плеер: берём ссылку на HLS из play/options и играем через ExoPlayer.
// Кнопка MENU на пульте переключает максимальное качество 480 / 720 / 1080.
public class PlayerActivity extends Activity {

    private static final int[] QH = {480, 720, 1080};
    private static final int[] QW = {854, 1280, 1920};

    private ExoPlayer player;
    private DefaultTrackSelector ts;
    private PlayerView pv;
    private TextView status;
    private SharedPreferences prefs;
    private int q = 1;
    private boolean failed = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("rutube", MODE_PRIVATE);
        q = Math.max(0, Math.min(QH.length - 1, prefs.getInt("q", 1)));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        pv = new PlayerView(this);
        pv.setControllerShowTimeoutMs(3000);
        root.addView(pv, new FrameLayout.LayoutParams(-1, -1));
        status = new TextView(this);
        status.setTextColor(0xFFFFFFFF);
        status.setGravity(Gravity.CENTER);
        status.setPadding(40, 40, 40, 40);
        status.setTextSize(18);
        status.setText("Загрузка…");
        root.addView(status, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        setContentView(root);

        final String id = getIntent().getStringExtra("id");
        new Thread(new Runnable() {
            public void run() { resolve(id); }
        }).start();
    }

    private void fail(final String msg) {
        runOnUiThread(new Runnable() {
            public void run() {
                failed = true;
                status.setVisibility(android.view.View.VISIBLE);
                status.setText(msg);
            }
        });
    }

    private void resolve(String id) {
        try {
            String s = Net.get("https://rutube.ru/api/play/options/" + id
                    + "/?no_404=true&referer&pver=v2");
            JSONObject o = new JSONObject(s);
            JSONObject det = o.optJSONObject("detail");
            if (det != null) {
                if ("blocking_rule".equals(det.optString("type")))
                    fail("Видео недоступно в этом регионе");
                else
                    fail("Видео недоступно");
                return;
            }
            JSONObject vb = o.optJSONObject("video_balancer");
            String url = vb == null ? "" : vb.optString("m3u8", vb.optString("default", ""));
            if (url.length() == 0) { fail("Не найдена ссылка на поток"); return; }
            final String m3u8 = url;
            runOnUiThread(new Runnable() {
                public void run() { start(m3u8); }
            });
        } catch (Throwable t) {
            fail("Ошибка загрузки: " + t);
        }
    }

    private void applyQuality() {
        ts.setParameters(ts.buildUponParameters()
                .setMaxVideoSize(QW[q], QH[q])
                .setExceedVideoConstraintsIfNecessary(true));
    }

    private void start(String m3u8) {
        ts = new DefaultTrackSelector(this);
        applyQuality();
        player = new ExoPlayer.Builder(this).setTrackSelector(ts).build();
        pv.setPlayer(player);

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(Net.UA)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000);
        player.setMediaSource(new HlsMediaSource.Factory(http)
                .createMediaSource(MediaItem.fromUri(m3u8)));
        player.addListener(new Player.Listener() {
            public void onPlaybackStateChanged(int st) {
                if (st == Player.STATE_READY) status.setVisibility(android.view.View.GONE);
                else if (st == Player.STATE_BUFFERING && !failed) {
                    status.setText("Буферизация…");
                    status.setVisibility(android.view.View.VISIBLE);
                }
            }
            public void onPlayerError(PlaybackException e) {
                failed = true;
                String m = e.getErrorCodeName();
                if (e.getCause() != null) m += "\n" + e.getCause();
                status.setVisibility(android.view.View.VISIBLE);
                status.setText("Ошибка воспроизведения\n" + m
                        + "\n\nMENU: сменить качество (сейчас " + QH[q] + "p)");
            }
        });
        player.prepare();
        player.setPlayWhenReady(true);
        pv.requestFocus();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            if (e.getAction() == KeyEvent.ACTION_UP && ts != null) {
                q = (q + 1) % QH.length;
                prefs.edit().putInt("q", q).commit();
                applyQuality();
                Toast.makeText(this, "Макс. качество: " + QH[q] + "p", Toast.LENGTH_SHORT).show();
                if (failed && player != null) {
                    failed = false;
                    status.setText("Загрузка…");
                    player.prepare();
                    player.setPlayWhenReady(true);
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) player.setPlayWhenReady(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) { player.release(); player = null; }
    }
}
