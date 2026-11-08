package com.example.rutubetv;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Главный экран: поиск по RuTube + быстрые категории + сетка видео.
public class MainActivity extends Activity {

    static class Vid {
        String id, title, thumb, author;
        int dur;
    }

    private static final String[] CATS = {
            "Музыка", "Фильмы", "Мультики", "Новости", "Игры", "Футбол", "Юмор", "Наука"};

    private float u;
    private EditText input;
    private TextView status;
    private GridView grid;
    private BaseAdapter adapter;
    private SharedPreferences prefs;

    private final List<Vid> vids = new ArrayList<Vid>();
    private String query = "";
    private int page = 0;
    private boolean hasNext = false, loading = false;
    private int token = 0;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(60);

    private int px(float k) { return (int) (k * u); }

    private TextView pill(String text, final Runnable r) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(1.9f));
        t.setPadding(px(1.8f), px(1f), px(1.8f), px(1f));
        t.setGravity(Gravity.CENTER);
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackgroundDrawable(focusBg(0x22FFFFFF, 0x55FFFFFF));
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { r.run(); }
        });
        return t;
    }

    private StateListDrawable focusBg(int normal, int hot) {
        StateListDrawable sl = new StateListDrawable();
        GradientDrawable h = new GradientDrawable();
        h.setColor(hot);
        h.setCornerRadius(px(1.2f));
        GradientDrawable n = new GradientDrawable();
        n.setColor(normal);
        n.setCornerRadius(px(1.2f));
        sl.addState(new int[]{android.R.attr.state_focused}, h);
        sl.addState(new int[]{android.R.attr.state_pressed}, h);
        sl.addState(new int[]{}, n);
        return sl;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Net.init();
        prefs = getSharedPreferences("rutube", MODE_PRIVATE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        u = dm.widthPixels / 100f;

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0xFF000000);
        col.setPadding(px(2), px(2), px(2), 0);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Поиск на RuTube");
        input.setHintTextColor(0x88FFFFFF);
        input.setTextColor(0xFFFFFFFF);
        input.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(2.2f));
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setPadding(px(1.5f), px(1f), px(1.5f), px(1f));
        input.setBackgroundDrawable(focusBg(0x22FFFFFF, 0x44FFFFFF));
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int a, KeyEvent e) {
                doSearch();
                return true;
            }
        });
        top.addView(input, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView go = pill("Найти", new Runnable() {
            public void run() { doSearch(); }
        });
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-2, -2);
        gp.leftMargin = px(1);
        top.addView(go, gp);
        col.addView(top, new LinearLayout.LayoutParams(-1, -2));

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout cats = new LinearLayout(this);
        cats.setOrientation(LinearLayout.HORIZONTAL);
        for (final String c : CATS) {
            TextView t = pill(c, new Runnable() {
                public void run() { input.setText(c); search(c); }
            });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
            cp.rightMargin = px(0.8f);
            cats.addView(t, cp);
        }
        hs.addView(cats);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = px(1.2f);
        hp.bottomMargin = px(1f);
        col.addView(hs, hp);

        status = new TextView(this);
        status.setTextColor(0xCCFFFFFF);
        status.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(1.8f));
        status.setPadding(px(0.5f), 0, 0, px(0.5f));
        status.setVisibility(View.GONE);
        col.addView(status);

        grid = new GridView(this);
        grid.setNumColumns(4);
        grid.setColumnWidth(px(23f));
        grid.setHorizontalSpacing(px(1f));
        grid.setVerticalSpacing(px(1f));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setSelector(focusBg(0x00000000, 0x44FFFFFF));
        grid.setCacheColorHint(Color.TRANSPARENT);
        grid.setSmoothScrollbarEnabled(false);
        adapter = new BaseAdapter() {
            public int getCount() { return vids.size(); }
            public Object getItem(int i) { return vids.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View v, ViewGroup p) {
                LinearLayout box;
                FrameLayout fr;
                ImageView img;
                TextView dur, title, sub;
                if (v == null) {
                    box = new LinearLayout(MainActivity.this);
                    box.setOrientation(LinearLayout.VERTICAL);
                    box.setPadding(px(0.5f), px(0.5f), px(0.5f), px(0.8f));
                    fr = new FrameLayout(MainActivity.this);
                    img = new ImageView(MainActivity.this);
                    img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    img.setBackgroundColor(0xFF15171C);
                    fr.addView(img, new FrameLayout.LayoutParams(-1, -1));
                    dur = new TextView(MainActivity.this);
                    dur.setTextColor(0xFFFFFFFF);
                    dur.setBackgroundColor(0xCC000000);
                    dur.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(1.4f));
                    dur.setPadding(px(0.5f), 0, px(0.5f), 0);
                    FrameLayout.LayoutParams dp = new FrameLayout.LayoutParams(-2, -2,
                            Gravity.BOTTOM | Gravity.RIGHT);
                    dp.rightMargin = px(0.5f);
                    dp.bottomMargin = px(0.5f);
                    fr.addView(dur, dp);
                    box.addView(fr, new LinearLayout.LayoutParams(-1, px(12.2f)));
                    title = new TextView(MainActivity.this);
                    title.setTextColor(0xFFFFFFFF);
                    title.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(1.6f));
                    title.setLines(2);
                    title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    title.setPadding(0, px(0.6f), 0, 0);
                    box.addView(title);
                    sub = new TextView(MainActivity.this);
                    sub.setTextColor(0x99FFFFFF);
                    sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(1.4f));
                    sub.setSingleLine(true);
                    sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    box.addView(sub);
                } else {
                    box = (LinearLayout) v;
                    fr = (FrameLayout) box.getChildAt(0);
                    img = (ImageView) fr.getChildAt(0);
                    dur = (TextView) fr.getChildAt(1);
                    title = (TextView) box.getChildAt(1);
                    sub = (TextView) box.getChildAt(2);
                }
                Vid it = vids.get(i);
                title.setText(it.title);
                sub.setText(it.author);
                dur.setText(fmt(it.dur));
                dur.setVisibility(it.dur > 0 ? View.VISIBLE : View.GONE);
                loadThumb(img, it.thumb);
                return box;
            }
        };
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Vid it = vids.get(pos);
                Intent in = new Intent(MainActivity.this, PlayerActivity.class);
                in.putExtra("id", it.id);
                in.putExtra("title", it.title);
                startActivity(in);
            }
        });
        grid.setOnScrollListener(new AbsListView.OnScrollListener() {
            public void onScrollStateChanged(AbsListView v, int s) { }
            public void onScroll(AbsListView v, int first, int vis, int total) {
                if (total > 0 && first + vis >= total - 4) loadMore();
            }
        });
        col.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(col);

        search(prefs.getString("last", CATS[0]));
    }

    private static String fmt(int s) {
        if (s <= 0) return "";
        int h = s / 3600, m = (s % 3600) / 60, c = s % 60;
        if (h > 0) return h + ":" + (m < 10 ? "0" : "") + m + ":" + (c < 10 ? "0" : "") + c;
        return m + ":" + (c < 10 ? "0" : "") + c;
    }

    private void doSearch() {
        String q = input.getText().toString().trim();
        if (q.length() > 0) search(q);
    }

    private void search(String q) {
        token++;
        loading = false;
        query = q;
        page = 0;
        hasNext = true;
        vids.clear();
        adapter.notifyDataSetChanged();
        prefs.edit().putString("last", q).commit();
        loadMore();
    }

    private void loadMore() {
        if (loading || !hasNext) return;
        loading = true;
        final int tk = token;
        final int pg = page + 1;
        final String q = query;
        if (vids.isEmpty()) {
            status.setText("Загрузка…");
            status.setVisibility(View.VISIBLE);
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    String url = "https://rutube.ru/api/search/video/?query="
                            + URLEncoder.encode(q, "UTF-8") + "&page=" + pg + "&format=json";
                    JSONObject o = new JSONObject(Net.get(url));
                    JSONArray r = o.optJSONArray("results");
                    final List<Vid> got = new ArrayList<Vid>();
                    if (r != null) for (int i = 0; i < r.length(); i++) {
                        JSONObject j = r.optJSONObject(i);
                        if (j == null) continue;
                        Vid v = new Vid();
                        v.id = j.optString("id");
                        if (v.id.length() == 0) continue;
                        v.title = j.optString("title");
                        v.thumb = j.optString("thumbnail_url");
                        v.dur = j.optInt("duration");
                        JSONObject a = j.optJSONObject("author");
                        v.author = a == null ? "" : a.optString("name");
                        got.add(v);
                    }
                    final boolean more = o.optBoolean("has_next", false) && !got.isEmpty();
                    ui.post(new Runnable() {
                        public void run() {
                            if (tk != token) return;
                            vids.addAll(got);
                            page = pg;
                            hasNext = more;
                            loading = false;
                            if (vids.isEmpty()) {
                                status.setText("Ничего не найдено");
                                status.setVisibility(View.VISIBLE);
                            } else status.setVisibility(View.GONE);
                            adapter.notifyDataSetChanged();
                        }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        public void run() {
                            if (tk != token) return;
                            loading = false;
                            status.setText("Ошибка: " + t);
                            status.setVisibility(View.VISIBLE);
                        }
                    });
                }
            }
        }).start();
    }

    private void loadThumb(final ImageView iv, final String url) {
        iv.setTag(url);
        iv.setImageDrawable(null);
        if (url == null || url.length() == 0) return;
        Bitmap c = cache.get(url);
        if (c != null) { iv.setImageBitmap(c); return; }
        pool.execute(new Runnable() {
            public void run() {
                try {
                    byte[] b = Net.fetch(url);
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(b, 0, b.length, o);
                    int s = 1;
                    while (o.outWidth / (s * 2) >= px(24f)) s *= 2;
                    o = new BitmapFactory.Options();
                    o.inSampleSize = s;
                    o.inPreferredConfig = Bitmap.Config.RGB_565;
                    final Bitmap bm = BitmapFactory.decodeByteArray(b, 0, b.length, o);
                    if (bm == null) return;
                    cache.put(url, bm);
                    ui.post(new Runnable() {
                        public void run() {
                            if (url.equals(iv.getTag())) iv.setImageBitmap(bm);
                        }
                    });
                } catch (Throwable ignored) { }
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
    }
}
