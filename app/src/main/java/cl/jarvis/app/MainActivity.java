package cl.jarvis.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.app.role.RoleManager;
import android.telecom.TelecomManager;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
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
 * Elena para el teléfono.
 *
 * La pantalla es la misma app web de Elena (en assets/index.html), servida desde el propio teléfono
 * en https://appassets.androidplatform.net (así el micrófono funciona). Las preguntas a Elena las hace
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
    private Actualizador actualizador;

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
                // solo se muestra la pantalla de Elena: ningún enlace lleva a otras páginas dentro de la app
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
            if (vozLista) { voz.setLanguage(new Locale("es", "ES")); voz.setSpeechRate(1.1f); }
        });

        actualizador = new Actualizador(this, texto -> principal.post(() -> web.evaluateJavascript(
                "window.avisoNativo&&window.avisoNativo(" + JSONObject.quote(texto) + ")", null)));
        if (getIntent() != null && Actualizador.ACCION_RESULTADO.equals(getIntent().getAction())) {
            actualizador.resultado(getIntent());
        }

        Escucha.oyente = (quien, texto) -> principal.post(() -> web.evaluateJavascript(
                "window.eventoEscucha&&window.eventoEscucha(" + JSONObject.quote(quien) + "," + JSONObject.quote(texto) + ")", null));
        if (Llamadas.preferencias(this).getBoolean(Escucha.ESCUCHAR_SIEMPRE, false)
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            Escucha.iniciar(this);
        }

        web.addJavascriptInterface(new Puente(), "ElenaNativo");
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
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (Actualizador.ACCION_RESULTADO.equals(intent.getAction())) actualizador.resultado(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (actualizador != null) actualizador.continuarSiEspera();
        if (hilos != null) hilos.execute(() -> Memoria.entregar(this));
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);  // el botón atrás minimiza Elena en vez de cerrarlo
    }

    @Override
    protected void onDestroy() {
        Escucha.oyente = null;
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

        // ------------------------------------------------------------------ llamadas
        @JavascriptInterface
        public boolean filtroLlamadasActivo() {
            if (Build.VERSION.SDK_INT < 29) return false;
            RoleManager roles = getSystemService(RoleManager.class);
            return roles != null && roles.isRoleHeld(RoleManager.ROLE_CALL_SCREENING);
        }

        @JavascriptInterface
        public String activarFiltroLlamadas() {
            if (Build.VERSION.SDK_INT < 29) return "El filtro de llamadas necesita Android 10 o más nuevo.";
            principal.post(() -> {
                RoleManager roles = getSystemService(RoleManager.class);
                if (roles != null) startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), 21);
                java.util.List<String> faltan = new ArrayList<>();
                if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
                    faltan.add(Manifest.permission.READ_CONTACTS);
                if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED)
                    faltan.add(Manifest.permission.ANSWER_PHONE_CALLS);
                if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
                    faltan.add(Manifest.permission.CALL_PHONE);
                if (!faltan.isEmpty()) requestPermissions(faltan.toArray(new String[0]), 22);
            });
            return "";
        }

        @JavascriptInterface
        public boolean ajusteLlamadas(String nombre) {
            boolean porDefecto = nombre.equals(Llamadas.BLOQUEAR_809) || nombre.equals(Llamadas.ANUNCIAR);
            return Llamadas.preferencias(MainActivity.this).getBoolean(nombre, porDefecto);
        }

        @JavascriptInterface
        public void cambiarAjusteLlamadas(String nombre, boolean valor) {
            if (!nombre.startsWith("llamadas_")) return;
            Llamadas.preferencias(MainActivity.this).edit().putBoolean(nombre, valor).apply();
        }

        @JavascriptInterface
        public String llamarContacto(String nombre) {
            return Acciones.llamar(MainActivity.this, nombre);
        }

        // ------------------------------------------------------------------ escuchar siempre («Elena»)
        @JavascriptInterface
        public void guardarConexion(String servidor, String clave) {
            Llamadas.preferencias(MainActivity.this).edit()
                    .putString(Escucha.SERVIDOR, servidor).putString(Escucha.CLAVE, clave).apply();
        }

        @JavascriptInterface
        public boolean escuchando() {
            return Escucha.activa();
        }

        @JavascriptInterface
        public String escucharSiempre(boolean si) {
            Llamadas.preferencias(MainActivity.this).edit().putBoolean(Escucha.ESCUCHAR_SIEMPRE, si).apply();
            if (!si) { Escucha.detener(MainActivity.this); return ""; }
            java.util.List<String> faltan = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                faltan.add(Manifest.permission.RECORD_AUDIO);
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                faltan.add(Manifest.permission.POST_NOTIFICATIONS);
            if (!faltan.isEmpty()) {
                principal.post(() -> requestPermissions(faltan.toArray(new String[0]), 23));
                return "Dame permiso para usar el micrófono (y mostrar la notificación) y vuelve a activar la opción.";
            }
            principal.post(() -> Escucha.iniciar(MainActivity.this));
            return "";
        }

        @JavascriptInterface
        public int sensibilidad() {
            return Llamadas.preferencias(MainActivity.this).getInt(Escucha.SENSIBILIDAD, 2);
        }

        @JavascriptInterface
        public void cambiarSensibilidad(int valor) {
            Llamadas.preferencias(MainActivity.this).edit().putInt(Escucha.SENSIBILIDAD, Math.max(1, Math.min(3, valor))).apply();
        }

        @JavascriptInterface
        public void pausarEscucha(boolean si) {
            Escucha.pausar(si);
        }

        @JavascriptInterface
        public boolean puedeAbrirDesdeSegundoPlano() {
            return Settings.canDrawOverlays(MainActivity.this);
        }

        @JavascriptInterface
        public void pedirAbrirDesdeSegundoPlano() {
            principal.post(() -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        }

        @JavascriptInterface
        public String llamada(String accion) {
            TelecomManager telefono = getSystemService(TelecomManager.class);
            try {
                switch (accion) {
                    case "contestar":
                        if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED)
                            return "Para contestar llamadas necesito el permiso de llamadas: actívalo en ⚙ › Filtro de llamadas.";
                        telefono.acceptRingingCall();
                        return "Contestando.";
                    case "colgar":
                        if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED)
                            return "Para colgar llamadas necesito el permiso de llamadas: actívalo en ⚙ › Filtro de llamadas.";
                        return telefono.endCall() ? "Colgué la llamada." : "No hay ninguna llamada que colgar.";
                    case "bloquear":
                        return Llamadas.bloquearUltima(MainActivity.this);
                    default:
                        return Llamadas.resumen(MainActivity.this);
                }
            } catch (SecurityException e) {
                return "Android no me dejó hacerlo: falta un permiso de llamadas.";
            } catch (Exception e) {
                return "No pude hacerlo en este teléfono.";
            }
        }

        @JavascriptInterface
        public void revisarActualizacion(final String servidor, final String clave, final boolean avisarSiNoHay) {
            hilos.execute(() -> actualizador.revisar(servidor, clave, avisarSiNoHay));
        }

        @JavascriptInterface
        public String version() {
            return String.valueOf(Actualizador.versionInstalada(MainActivity.this));
        }

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
            if (Acciones.enLlamada(MainActivity.this)) return;  // en llamada no habla
            if (vozLista && texto != null) voz.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "elena");
        }

        @JavascriptInterface
        public boolean enLlamada() {
            return Acciones.enLlamada(MainActivity.this);
        }

        /** Cualquier orden para el teléfono que mandó Elena desde el PC (linterna, alarma, WhatsApp...). */
        @JavascriptInterface
        public String accionTelefono(String json) {
            try {
                return Acciones.ejecutar(MainActivity.this, new JSONObject(json));
            } catch (Exception e) {
                return "No entendí la orden para el teléfono.";
            }
        }

        // ------------------------------------------------ memoria del teléfono (cuando Elena no está conectada)
        @JavascriptInterface
        public String guardarEnMemoria(String texto, String tipo) {
            return Memoria.agregarTexto(MainActivity.this, texto, tipo == null || tipo.isEmpty() ? "auto" : tipo);
        }

        @JavascriptInterface
        public String guardarVozEnMemoria(String wavBase64) {
            return Memoria.agregarAudio(MainActivity.this, android.util.Base64.decode(wavBase64, android.util.Base64.DEFAULT));
        }

        @JavascriptInterface
        public String memoria() {
            return Memoria.paraPantalla(MainActivity.this);
        }

        @JavascriptInterface
        public int cantidadMemoria() {
            return Memoria.cantidad(MainActivity.this);
        }

        @JavascriptInterface
        public void borrarDeMemoria(String id) {
            try { Memoria.borrar(MainActivity.this, Long.parseLong(id)); } catch (NumberFormatException ignorado) { }
        }

        @JavascriptInterface
        public void entregarMemoria() {
            hilos.execute(() -> Memoria.entregar(MainActivity.this));
        }

        @JavascriptInterface
        public boolean vozRapida() {
            return Llamadas.preferencias(MainActivity.this).getBoolean(Escucha.VOZ_RAPIDA, true);
        }

        @JavascriptInterface
        public void cambiarVozRapida(boolean si) {
            Llamadas.preferencias(MainActivity.this).edit().putBoolean(Escucha.VOZ_RAPIDA, si).apply();
        }

        @JavascriptInterface
        public void pedir(final String id, final String servidor, final String ruta, final String tipo,
                          final String cuerpoBase64, final String clave, final boolean voz) {
            hilos.execute(() -> {
                String json;
                try {
                    json = enviar(servidor, ruta, tipo, cuerpoBase64, clave, voz);
                } catch (Exception e) {
                    json = "{\"error\":\"No pude conectar con Elena. ¿Está abierto en el PC y NetBird conectado?\","
                            + "\"sin_conexion\":true}";
                }
                final String respuesta = JSONObject.quote(json);
                principal.post(() -> web.evaluateJavascript(
                        "window.respuestaNativa(" + JSONObject.quote(id) + "," + respuesta + ")", null));
            });
        }
    }

    /** Hace la petición a Elena (por HTTPS, confiando en el certificado de tu PC) y devuelve el JSON. */
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
        conexion.setReadTimeout(180000);  // Elena puede tardar en pensar
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
