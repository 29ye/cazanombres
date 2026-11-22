package com.cazanombres.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Muestra la interfaz (assets/index.html) en un WebView y le ofrece un puente "Native"
 * para comprobar nombres en Roblox, Discord y TikTok desde el propio celular.
 */
public class MainActivity extends Activity {

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0 Mobile Safari/537.36";
    private static final Pattern NOMBRE = Pattern.compile("^[a-z0-9]{3,12}$");

    private WebView web;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private volatile int generacion = 0;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);

        web.addJavascriptInterface(new Puente(), "Native");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String url = r.getUrl().toString();
                if (url.startsWith("file:///android_asset/")) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception e) {
                    // sin aplicación para abrir el enlace
                }
                return true;
            }
        });
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }

    /** Métodos que la página puede llamar como Native.check(...), Native.cancel(), Native.copy(...). */
    private class Puente {
        @JavascriptInterface
        public void check(final String plataforma, final String nombre) {
            final int g = generacion;
            if (nombre == null || plataforma == null || !NOMBRE.matcher(nombre).matches()) {
                responder(String.valueOf(plataforma), String.valueOf(nombre), "no válido", g);
                return;
            }
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    if (g != generacion) {
                        return;
                    }
                    String resultado;
                    try {
                        resultado = comprobar(plataforma, nombre, g);
                    } catch (Exception e) {
                        resultado = "error";
                    }
                    responder(plataforma, nombre, resultado, g);
                }
            });
        }

        @JavascriptInterface
        public void cancel() {
            generacion++;
        }

        @JavascriptInterface
        public void copy(final String texto) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("nombre", texto));
                    }
                }
            });
        }
    }

    private void responder(String p, String n, String r, int g) {
        if (g != generacion) {
            return;
        }
        final String js = "window.__res(" + JSONObject.quote(p) + "," + JSONObject.quote(n) + ","
                + JSONObject.quote(r) + ")";
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript(js, null);
            }
        });
    }

    private String comprobar(String p, String n, int g) throws Exception {
        for (int intento = 0; intento < 4; intento++) {
            if (g != generacion) {
                return "cancelado";
            }
            String[] r;
            if (p.equals("roblox")) {
                r = http("GET", "https://auth.roblox.com/v1/usernames/validate?Username=" + n
                        + "&Birthday=2000-01-01&Context=Signup", null, 200000);
            } else if (p.equals("discord")) {
                r = http("POST", "https://discord.com/api/v9/unique-username/username-attempt-unauthed",
                        "{\"username\":\"" + n + "\"}", 200000);
            } else if (p.equals("tiktok")) {
                r = http("GET", "https://www.tiktok.com/@" + n, null, 1500000);
            } else {
                return "error";
            }
            int estado = Integer.parseInt(r[0]);
            if (estado == 429) {
                Thread.sleep(esperaMs(r[1]));
                continue;
            }
            return interpretar(p, n, estado, r[1]);
        }
        return "error";
    }

    private long esperaMs(String cuerpo) {
        try {
            double s = new JSONObject(cuerpo).optDouble("retry_after", 3);
            return (long) (Math.min(Math.max(s, 1), 15) * 1000) + 300;
        } catch (Exception e) {
            return 3000;
        }
    }

    private String interpretar(String p, String n, int estado, String cuerpo) {
        try {
            if (p.equals("roblox")) {
                int code = new JSONObject(cuerpo).optInt("code", -1);
                if (code == 0) return "disponible";
                if (code == 1) return "tomado";
                if (code == -1) return "error";
                return "no válido";
            }
            if (p.equals("discord")) {
                JSONObject o = new JSONObject(cuerpo);
                if (estado == 200 && o.has("taken")) {
                    return o.getBoolean("taken") ? "tomado" : "disponible";
                }
                return "error";
            }
            if (cuerpo.contains("\"statusCode\":10221")) {
                return "disponible";
            }
            if (cuerpo.toLowerCase().contains(("\"uniqueId\":\"" + n + "\"").toLowerCase())) {
                return "tomado";
            }
            return "desconocido";
        } catch (Exception e) {
            return "error";
        }
    }

    private String[] http(String metodo, String url, String cuerpo, int max) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(metodo);
        c.setConnectTimeout(12000);
        c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Language", "es,en;q=0.8");
        if (cuerpo != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream o = c.getOutputStream();
            o.write(cuerpo.getBytes("UTF-8"));
            o.close();
        }
        int codigo = c.getResponseCode();
        InputStream in = codigo >= 400 ? c.getErrorStream() : c.getInputStream();
        String texto = "";
        if (in != null) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int leidos;
            int total = 0;
            while (total < max && (leidos = in.read(buf)) > 0) {
                bo.write(buf, 0, leidos);
                total += leidos;
            }
            in.close();
            texto = bo.toString("UTF-8");
        }
        c.disconnect();
        return new String[]{String.valueOf(codigo), texto};
    }
}
