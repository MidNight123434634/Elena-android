package cl.jarvis.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Jarvis para el teléfono.
 *
 * La pantalla es la misma app web de Jarvis (en assets/index.html), servida desde el propio teléfono
 * en https://appassets.androidplatform.net (así el micrófono funciona). Las preguntas a Jarvis las hace
 * esta parte nativa por HTTPS, confiando solo en el certificado de tu PC (res/raw/jarvis_ca.crt).
 */
public class MainActivity extends Activity {

    private static final String INICIO = "https://appassets.androidplatform.net/assets/index.html";
    private static final int PEDIR_MICROFONO = 7;

    private WebView web;
    private PermissionRequest pedidoPendiente;
    private final ExecutorService hilos = Executors.newFixedThreadPool(2);
    private final Handler principal = new Handler(Looper.getMainLooper());
    private TextToSpeech voz;
    private boolean vozLista = false;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle guardado) {
        super.onCreate(guardado);
        getWindow().setStatusBarColor(Color.parseColor("#0B0F1A"));
        getWindow().setNavigationBarColor(Color.parseColor("#0B0F1A"));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0B0F1A"));
        setContentView(web);

        WebSettings ajustes = web.getSettings();
        ajustes.setJavaScriptEnabled(true);
        ajustes.setDomStorageEnabled(true);           // guarda la clave y los ajustes en el teléfono
        ajustes.setMediaPlaybackRequiresUserGesture(false);
        ajustes.setAllowFileAccess(false);
        ajustes.setAllowContentAccess(false);

        final WebViewAssetLoader cargador = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView vista, WebResourceRequest pedido) {
                return cargador.shouldInterceptRequest(pedido.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView vista, WebResourceRequest pedido) {
                // solo se muestra la pantalla de Jarvis: ningún enlace lleva a otras páginas dentro de la app
                Uri url = pedido.getUrl();
                return !"appassets.androidplatform.net".equals(url.getHost());
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest pedido) {
                principal.post(() -> {
                    if (!"appassets.androidplatform.net".equals(pedido.getOrigin().getHost())) {
                        pedido.deny();
                        return;
                    }
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        pedido.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    } else {
                        pedidoPendiente = pedido;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PEDIR_MICROFONO);
                    }
                });
            }
        });

        voz = new TextToSpeech(this, estado -> {
            vozLista = estado == TextToSpeech.SUCCESS;
            if (vozLista) voz.setLanguage(new Locale("es", "ES"));
        });

        web.addJavascriptInterface(new Puente(), "JarvisNativo");
        web.loadUrl(INICIO);
    }

    @Override
    public void onRequestPermissionsResult(int codigo, String[] permisos, int[] resultados) {
        super.onRequestPermissionsResult(codigo, permisos, resultados);
        if (codigo != PEDIR_MICROFONO || pedidoPendiente == null) return;
        if (resultados.length > 0 && resultados[0] == PackageManager.PERMISSION_GRANTED) {
            pedidoPendiente.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
        } else {
            pedidoPendiente.deny();
        }
        pedidoPendiente = null;
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);  // el botón atrás minimiza Jarvis en vez de cerrarlo
    }

    @Override
    protected void onDestroy() {
        hilos.shutdownNow();
        if (voz != null) voz.shutdown();
        web.destroy();
        super.onDestroy();
    }

    /** Texto sin tildes, en minúsculas y sin signos (para comparar nombres de apps). */
    static String simple(String texto) {
        String t = Normalizer.normalize(texto == null ? "" : texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return t.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    /** Las apps que se pueden abrir: nombre visible y paquete. */
    private List<String[]> apps() {
        PackageManager pm = getPackageManager();
        Intent inicio = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<String[]> lista = new ArrayList<>();
        for (ResolveInfo r : pm.queryIntentActivities(inicio, 0)) {
            if (r.activityInfo.packageName.equals(getPackageName())) continue;
            lista.add(new String[]{r.loadLabel(pm).toString(), r.activityInfo.packageName});
        }
        return lista;
    }

    private String abrir(String nombre) {
        String buscado = simple(nombre).replace("guasap", "whatsapp").replace("wasap", "whatsapp")
                .replace("wsp", "whatsapp").replace("insta ", "instagram ").replace("yutub", "youtube");
        if (buscado.isEmpty()) return "¿Qué app quieres que abra?";
        String[] elegida = null;
        int mejor = 0;
        for (String[] app : apps()) {
            String etiqueta = simple(app[0]);
            int puntos = etiqueta.equals(buscado) ? 3 : etiqueta.startsWith(buscado) || buscado.startsWith(etiqueta) ? 2
                    : (buscado.length() >= 4 && etiqueta.contains(buscado)) ? 1 : 0;
            if (puntos > mejor || (puntos == mejor && puntos > 0 && elegida != null && app[0].length() < elegida[0].length())) {
                mejor = puntos;
                elegida = app;
            }
        }
        if (elegida == null) return "No encontré una app llamada «" + nombre + "» en el teléfono.";
        Intent abrir = getPackageManager().getLaunchIntentForPackage(elegida[1]);
        if (abrir == null) return "No puedo abrir " + elegida[0] + ".";
        abrir.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(abrir);
        return "Abriendo " + elegida[0] + ".";
    }

    private boolean permisoNotificaciones() {
        String activos = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return activos != null && activos.contains(new ComponentName(this, Avisos.class).flattenToString());
    }

    /** Lo que la pantalla puede pedirle a la parte nativa. */
    private class Puente {

        @JavascriptInterface
        public String appsInstaladas() {
            JSONArray lista = new JSONArray();
            for (String[] app : apps()) lista.put(app[0]);
            return lista.toString();
        }

        @JavascriptInterface
        public String abrirApp(final String nombre) {
            final String[] resultado = {""};
            final Object listo = new Object();
            principal.post(() -> {
                String r;
                try { r = abrir(nombre); } catch (Exception e) { r = "No pude abrir esa app."; }
                synchronized (listo) { resultado[0] = r; listo.notifyAll(); }
            });
            synchronized (listo) {
                try { if (resultado[0].isEmpty()) listo.wait(3000); } catch (InterruptedException ignorado) { }
            }
            return resultado[0].isEmpty() ? "Abriendo." : resultado[0];
        }

        @JavascriptInterface
        public String notificaciones(String filtroApp) {
            return Avisos.resumen(filtroApp);
        }

        @JavascriptInterface
        public boolean puedeLeerNotificaciones() {
            return permisoNotificaciones();
        }

        @JavascriptInterface
        public void pedirPermisoNotificaciones() {
            principal.post(() -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        }

        @JavascriptInterface
        public void leerNotificacionesSolo(boolean si) {
            getSharedPreferences(Avisos.PREFERENCIAS, Context.MODE_PRIVATE).edit().putBoolean(Avisos.LEER_SOLO, si).apply();
        }

        @JavascriptInterface
        public boolean leeNotificacionesSolo() {
            return getSharedPreferences(Avisos.PREFERENCIAS, Context.MODE_PRIVATE).getBoolean(Avisos.LEER_SOLO, false);
        }

        @JavascriptInterface
        public void hablar(String texto) {
            if (vozLista && texto != null) voz.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "jarvis");
        }

        @JavascriptInterface
        public void pedir(final String id, final String servidor, final String ruta, final String tipo,
                          final String cuerpoBase64, final String clave, final boolean voz) {
            hilos.execute(() -> {
                String json;
                try {
                    json = enviar(servidor, ruta, tipo, cuerpoBase64, clave, voz);
                } catch (Exception e) {
                    json = "{\"error\":\"No pude conectar con Jarvis. ¿Está abierto en el PC y NetBird conectado?\","
                            + "\"sin_conexion\":true}";
                }
                final String respuesta = JSONObject.quote(json);
                principal.post(() -> web.evaluateJavascript(
                        "window.respuestaNativa(" + JSONObject.quote(id) + "," + respuesta + ")", null));
            });
        }
    }

    /** Hace la petición a Jarvis (por HTTPS, confiando en el certificado de tu PC) y devuelve el JSON. */
    private static String enviar(String servidor, String ruta, String tipo, String cuerpoBase64,
                                 String clave, boolean voz) throws Exception {
        String base = servidor.trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (!base.startsWith("https://")) throw new IllegalArgumentException("La dirección debe empezar con https://");
        if (!ruta.equals("/preguntar") && !ruta.equals("/oir") && !ruta.equals("/estado")
                && !ruta.equals("/telefono/apps")) {
            throw new IllegalArgumentException("Ruta no permitida");
        }
        HttpURLConnection conexion = (HttpURLConnection) new URL(base + ruta).openConnection();
        conexion.setConnectTimeout(8000);
        conexion.setReadTimeout(180000);  // Jarvis puede tardar en pensar
        conexion.setRequestProperty("X-Jarvis", "1");
        conexion.setRequestProperty("X-Jarvis-Clave", clave == null ? "" : clave);
        conexion.setRequestProperty("X-Jarvis-Voz", voz ? "1" : "0");
        conexion.setRequestProperty("X-Jarvis-Telefono", "1");  // esta app puede abrir apps y leer notificaciones
        if (ruta.equals("/estado")) {
            conexion.setRequestMethod("GET");
        } else {
            byte[] cuerpo = Base64.decode(cuerpoBase64, Base64.DEFAULT);
            conexion.setRequestMethod("POST");
            conexion.setDoOutput(true);
            conexion.setRequestProperty("Content-Type", tipo);
            conexion.setFixedLengthStreamingMode(cuerpo.length);
            try (OutputStream salida = conexion.getOutputStream()) {
                salida.write(cuerpo);
            }
        }
        int codigo = conexion.getResponseCode();
        InputStream entrada = codigo >= 400 ? conexion.getErrorStream() : conexion.getInputStream();
        ByteArrayOutputStream datos = new ByteArrayOutputStream();
        if (entrada != null) {
            byte[] trozo = new byte[16384];
            int n;
            while ((n = entrada.read(trozo)) > 0) datos.write(trozo, 0, n);
            entrada.close();
        }
        conexion.disconnect();
        String texto = datos.toString(StandardCharsets.UTF_8.name());
        if (texto.isEmpty()) texto = "{}";
        JSONObject objeto = new JSONObject(texto);
        objeto.put("estado_http", codigo);
        return objeto.toString();
    }
}
