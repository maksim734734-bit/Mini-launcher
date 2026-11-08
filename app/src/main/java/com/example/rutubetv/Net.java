package com.example.rutubetv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

// Сеть для Android 4.4: включаем TLS 1.2 (на 4.4 он по умолчанию выключен).
// Если сертификат сервера старое устройство не принимает (нет новых корневых
// сертификатов), один раз переключаемся в режим без проверки сертификата.
// ExoPlayer берёт те же глобальные настройки HttpsURLConnection.
final class Net {
    static final String UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    static volatile boolean lax = false;

    static void init() { apply(false); }

    static synchronized void apply(boolean trustAll) {
        try {
            TrustManager[] tms = null;
            if (trustAll) {
                tms = new TrustManager[]{ new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] c, String a) { }
                    public void checkServerTrusted(X509Certificate[] c, String a) { }
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                } };
            }
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tms, null);
            HttpsURLConnection.setDefaultSSLSocketFactory(new Tls12Factory(ctx.getSocketFactory()));
            if (trustAll) {
                HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
                    public boolean verify(String h, SSLSession s) { return true; }
                });
            }
            lax = trustAll;
        } catch (Exception ignored) { }
    }

    static String get(String u) throws IOException {
        return new String(fetch(u), "UTF-8");
    }

    static byte[] fetch(String u) throws IOException {
        try {
            return fetch0(u);
        } catch (SSLException e) {
            if (lax) throw e;
            apply(true);
            return fetch0(u);
        }
    }

    private static byte[] fetch0(String u) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "*/*");
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            in.close();
            return bo.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    static class Tls12Factory extends SSLSocketFactory {
        private final SSLSocketFactory d;
        Tls12Factory(SSLSocketFactory d) { this.d = d; }

        private Socket patch(Socket s) {
            if (s instanceof SSLSocket) {
                SSLSocket ss = (SSLSocket) s;
                List<String> sup = Arrays.asList(ss.getSupportedProtocols());
                List<String> want = new ArrayList<String>();
                for (String p : new String[]{"TLSv1.2", "TLSv1.1", "TLSv1"}) if (sup.contains(p)) want.add(p);
                if (!want.isEmpty()) ss.setEnabledProtocols(want.toArray(new String[0]));
            }
            return s;
        }

        public String[] getDefaultCipherSuites() { return d.getDefaultCipherSuites(); }
        public String[] getSupportedCipherSuites() { return d.getSupportedCipherSuites(); }
        public Socket createSocket() throws IOException { return patch(d.createSocket()); }
        public Socket createSocket(Socket s, String h, int p, boolean a) throws IOException { return patch(d.createSocket(s, h, p, a)); }
        public Socket createSocket(String h, int p) throws IOException { return patch(d.createSocket(h, p)); }
        public Socket createSocket(String h, int p, InetAddress la, int lp) throws IOException { return patch(d.createSocket(h, p, la, lp)); }
        public Socket createSocket(InetAddress h, int p) throws IOException { return patch(d.createSocket(h, p)); }
        public Socket createSocket(InetAddress h, int p, InetAddress la, int lp) throws IOException { return patch(d.createSocket(h, p, la, lp)); }
    }
}
