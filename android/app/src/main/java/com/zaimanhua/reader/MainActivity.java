package com.zaimanhua.reader;

import android.app.Activity;
import android.graphics.Color;
import android.net.http.SslError;
import android.os.Bundle;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final String TAG = "ZaiManhua";
    private TextView logView;
    private AtomicInteger logCount = new AtomicInteger(0);
    private final ConcurrentHashMap<String, String> dnsCache = new ConcurrentHashMap<>();

    private void appLog(String msg) {
        Log.i(TAG, msg);
        runOnUiThread(() -> {
            int n = logCount.incrementAndGet();
            if (n > 10) {
                String t = logView.getText().toString();
                int idx = t.indexOf('\n');
                if (idx > 0) logView.setText(t.substring(idx + 1));
            }
            logView.append(msg + "\n");
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        // Trust all certs for image proxy (needed for IP-based HTTPS connections)
        setupTrustAllCerts();

        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);

        logView = new TextView(this);
        logView.setTextSize(9);
        logView.setTextColor(Color.GREEN);
        logView.setBackgroundColor(Color.BLACK);
        logView.setMaxLines(6);
        logView.setPadding(8, 8, 8, 8);
        layout.addView(logView, new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 100));

        WebView webView = new WebView(this);
        layout.addView(webView, new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(layout);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.setWebViewClient(new ProxyWebViewClient());
        webView.setWebChromeClient(new WebChromeClient());

        // Pre-resolve DNS with cd=1 (skip DNSSEC validation)
        new Thread(() -> {
            String ip = resolveViaDoH("images.zaimanhua.com");
            if (ip != null) {
                dnsCache.put("images.zaimanhua.com", ip);
                appLog("DNS: images → " + ip);
            } else {
                appLog("DNS FAIL");
            }
        }).start();

        appLog("Loading...");
        webView.loadDataWithBaseURL(
            "http://127.0.0.1/",
            getHtmlFromAssets(),
            "text/html", "UTF-8", null
        );
    }

    /**
     * DNS-over-HTTPS with cd=1 to bypass DNSSEC validation.
     * The domain images.zaimanhua.com has broken DNSSEC,
     * so normal DNS and standard DoH fail with SERVFAIL.
     */
    private String resolveViaDoH(String hostname) {
        try {
            // cd=1 means "checking disabled" — skip DNSSEC validation
            String apiUrl = "https://dns.google/resolve?name=" + hostname + "&type=A&cd=1";
            String response = fetchUrl(apiUrl);
            if (response != null) {
                JSONObject json = new JSONObject(response);
                int status = json.optInt("Status", -1);
                if (status != 0) {
                    appLog("DoH status: " + status);
                }
                JSONArray answers = json.optJSONArray("Answer");
                if (answers != null) {
                    for (int i = 0; i < answers.length(); i++) {
                        JSONObject ans = answers.getJSONObject(i);
                        if (ans.optInt("type") == 1) { // A record
                            return ans.getString("data");
                        }
                    }
                }
            }
        } catch (Exception e) {
            appLog("DoH err: " + e.getMessage());
        }
        return null;
    }

    private String fetchUrl(String targetUrl) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(targetUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "ZaiManhuaReader/1.0");
            int code = conn.getResponseCode();
            if (code >= 200 && code < 400) {
                InputStream is = conn.getInputStream();
                String result = new String(readAll(is), "UTF-8");
                is.close();
                return result;
            }
        } catch (Exception e) {
            Log.e(TAG, "fetchUrl error", e);
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private String getHtmlFromAssets() {
        try {
            InputStream is = getAssets().open("index.html");
            String html = new String(readAll(is), "UTF-8");
            is.close();
            return html;
        } catch (Exception e) {
            appLog("HTML err");
            return "<html><body><h1>Error</h1></body></html>";
        }
    }

    /**
     * Setup SSL trust-all so we can connect to IPs directly
     * without certificate hostname verification failures.
     */
    private void setupTrustAllCerts() {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAll, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
                public boolean verify(String hostname, SSLSession session) { return true; }
            });
        } catch (Exception e) {
            Log.e(TAG, "SSL setup error", e);
        }
    }

    private class ProxyWebViewClient extends WebViewClient {

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();

            // Intercept image requests from zaimanhua CDN
            if (url.contains("images.zaimanhua.com")) {
                try {
                    byte[] data = fetchImageViaIP(url);
                    if (data != null && data.length > 0) {
                        String mime = guessMime(url);
                        appLog("IMG: " + (data.length / 1024) + "KB");
                        return new WebResourceResponse(mime, "UTF-8",
                            new ByteArrayInputStream(data));
                    }
                    appLog("IMG: 0b");
                } catch (Exception e) {
                    appLog("IMG err: " + e.getMessage());
                }
                return new WebResourceResponse("image/png", "UTF-8",
                    new ByteArrayInputStream(new byte[0]));
            }

            return null;
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.proceed();
        }
    }

    /**
     * Fetch image using resolved IP directly, bypassing phone's broken DNS.
     * Uses Host header for virtual hosting.
     */
    private byte[] fetchImageViaIP(String imageUrl) {
        try {
            URL url = new URL(imageUrl);
            String hostname = url.getHost();
            int port = url.getPort() > 0 ? url.getPort() : 443;
            String path = url.getFile(); // includes query string

            // Get IP from DoH cache
            String ip = dnsCache.get(hostname);

            // Fallback: try system DNS
            if (ip == null) {
                try {
                    ip = java.net.InetAddress.getByName(hostname).getHostAddress();
                } catch (Exception ignored) {}
            }

            // Fallback: try DoH on the spot
            if (ip == null) {
                ip = resolveViaDoH(hostname);
                if (ip != null) dnsCache.put(hostname, ip);
            }

            if (ip == null) {
                appLog("No IP for " + hostname);
                return null;
            }

            // Build URL with IP address directly
            // e.g. https://162.128.37.195/img/webpic/15/xxx.jpg
            String directUrl = "https://" + ip + path;

            HttpsURLConnection conn = (HttpsURLConnection) new URL(directUrl).openConnection();
            conn.setHostnameVerifier((hn, sn) -> true); // Accept any hostname
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Host", hostname); // Virtual hosting
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36");
            conn.setRequestProperty("Accept", "image/webp,image/apng,image/*,*/*;q=0.8");
            conn.setRequestProperty("Referer", "https://www.zaimanhua.com/");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setInstanceFollowRedirects(true);

            int code = conn.getResponseCode();
            if (code >= 200 && code < 400) {
                InputStream is = conn.getInputStream();
                byte[] data = readAll(is);
                is.close();
                conn.disconnect();
                return data;
            } else {
                appLog("HTTP " + code);
                conn.disconnect();
            }
        } catch (Exception e) {
            appLog("IMG err: " + e.getMessage());
        }
        return null;
    }

    private String guessMime(String url) {
        if (url.contains(".jpg") || url.contains(".jpeg")) return "image/jpeg";
        if (url.contains(".png")) return "image/png";
        if (url.contains(".gif")) return "image/gif";
        if (url.contains(".webp")) return "image/webp";
        return "image/jpeg";
    }

    private byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int n;
        while ((n = is.read(tmp)) != -1) buf.write(tmp, 0, n);
        return buf.toByteArray();
    }

    @Override
    public void onBackPressed() { super.onBackPressed(); }
}
