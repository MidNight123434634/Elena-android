package cl.jarvis.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * La «segunda memoria» del teléfono: si Jarvis (el PC) no está disponible, lo que le dices o escribes queda
 * guardado aquí, en el teléfono, y se le entrega solo cuando vuelve la conexión.
 *  - Ideas («idea: ...», «anota ...») -> Jarvis las guarda en el bloc «Ideas desde el teléfono».
 *  - Órdenes («crea una planilla de ventas») -> Jarvis las hace al reconectar.
 */
final class Memoria {

    private Memoria() { }

    private static final String ARCHIVO = "memoria.json";
    private static final String CANAL = "memoria";
    private static final int MAXIMO = 200;
    private static volatile boolean entregando = false;

    // ------------------------------------------------------------------ guardar
    private static File carpeta(Context c) {
        File d = new File(c.getFilesDir(), "memoria");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static String ahora() {
        return new SimpleDateFormat("dd/MM HH:mm", Locale.ROOT).format(new Date());
    }

    static synchronized JSONArray lista(Context c) {
        try {
            File f = new File(carpeta(c), ARCHIVO);
            if (!f.exists()) return new JSONArray();
            return new JSONArray(new String(leer(f), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static synchronized void guardarLista(Context c, JSONArray lista) {
        try (FileOutputStream f = new FileOutputStream(new File(carpeta(c), ARCHIVO))) {
            f.write(lista.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignorado) { }
    }

    static int cantidad(Context c) { return lista(c).length(); }

    /** Guarda algo escrito. tipo: "idea", "accion" o "auto" (Jarvis decide por las palabras). */
    static synchronized String agregarTexto(Context c, String texto, String tipo) {
        texto = texto == null ? "" : texto.trim();
        if (texto.isEmpty()) return "";
        try {
            JSONArray l = lista(c);
            if (l.length() >= MAXIMO) return "La memoria del teléfono está llena (" + MAXIMO + " cosas). Conéctame a Jarvis para vaciarla.";
            l.put(new JSONObject().put("id", System.currentTimeMillis()).put("tipo", tipo).put("texto", texto).put("hora", ahora()));
            guardarLista(c, l);
            return confirmacion(c, tipo);
        } catch (Exception e) {
            return "No pude guardarlo en el teléfono.";
        }
    }

    /** Guarda algo dicho con la voz (el PC lo transcribe cuando vuelva la conexión). */
    static synchronized String agregarAudio(Context c, byte[] wav) {
        try {
            JSONArray l = lista(c);
            if (l.length() >= MAXIMO) return "La memoria del teléfono está llena. Conéctame a Jarvis para vaciarla.";
            long id = System.currentTimeMillis();
            String nombre = "voz_" + id + ".wav";
            try (FileOutputStream f = new FileOutputStream(new File(carpeta(c), nombre))) { f.write(wav); }
            l.put(new JSONObject().put("id", id).put("tipo", "auto").put("audio", nombre).put("hora", ahora()));
            guardarLista(c, l);
            return confirmacion(c, "voz");
        } catch (Exception e) {
            return "No pude guardar la grabación en el teléfono.";
        }
    }

    private static String confirmacion(Context c, String tipo) {
        int n = cantidad(c);
        String que = tipo.equals("idea") ? "tu idea" : tipo.equals("accion") ? "la orden" : "lo que dijiste";
        return "Jarvis no está conectado: guardé " + que + " en el teléfono y se la entrego cuando vuelva la conexión"
                + (n > 1 ? " (tengo " + n + " cosas esperando)." : ".");
    }

    static synchronized boolean borrar(Context c, long id) {
        JSONArray l = lista(c), nueva = new JSONArray();
        boolean borrado = false;
        for (int i = 0; i < l.length(); i++) {
            JSONObject o = l.optJSONObject(i);
            if (o == null) continue;
            if (o.optLong("id") == id) {
                borrado = true;
                if (o.has("audio")) new File(carpeta(c), o.optString("audio")).delete();
            } else {
                nueva.put(o);
            }
        }
        guardarLista(c, nueva);
        return borrado;
    }

    /** La lista para mostrar en la pantalla (sin el audio). */
    static String paraPantalla(Context c) {
        JSONArray l = lista(c), salida = new JSONArray();
        for (int i = 0; i < l.length(); i++) {
            JSONObject o = l.optJSONObject(i);
            if (o == null) continue;
            try {
                salida.put(new JSONObject().put("id", o.optLong("id")).put("tipo", o.optString("tipo"))
                        .put("hora", o.optString("hora"))
                        .put("texto", o.has("audio") ? "🎤 Grabación de voz" : o.optString("texto")));
            } catch (Exception ignorado) { }
        }
        return salida.toString();
    }

    // ------------------------------------------------------------------ entregar a Jarvis
    /** Intenta entregar todo lo guardado. Se llama en segundo plano. Devuelve cuántas cosas entregó. */
    static int entregar(Context c) {
        if (entregando || cantidad(c) == 0) return 0;
        entregando = true;
        List<String> resultados = new ArrayList<>();
        int entregadas = 0;
        try {
            SharedPreferences pref = Llamadas.preferencias(c);
            String base = pref.getString(Escucha.SERVIDOR, "https://100.124.180.1:8766").trim().replaceAll("/+$", "");
            String clave = pref.getString(Escucha.CLAVE, "");
            if (!base.startsWith("https://") || clave.isEmpty()) return 0;
            JSONArray l = lista(c);
            for (int i = 0; i < l.length(); i++) {
                JSONObject o = l.optJSONObject(i);
                if (o == null) continue;
                String resultado;
                try {
                    resultado = entregarUno(c, base, clave, o);
                } catch (java.io.IOException | IllegalStateException e) {
                    break;  // sin conexión, Jarvis cerrado o clave mala: se vuelve a intentar más tarde
                } catch (Exception e) {
                    resultado = "No pude entregar algo que guardaste (" + o.optString("hora") + ").";
                }
                borrar(c, o.optLong("id"));
                entregadas++;
                if (resultado != null && !resultado.isEmpty()) resultados.add(resultado);
            }
        } finally {
            entregando = false;
        }
        if (entregadas > 0) avisarEntrega(c, entregadas, resultados);
        return entregadas;
    }

    private static String entregarUno(Context c, String base, String clave, JSONObject o) throws Exception {
        JSONObject pedido = new JSONObject().put("tipo", o.optString("tipo", "auto")).put("hora", o.optString("hora"));
        byte[] audio = null;
        if (o.has("audio")) {
            File f = new File(carpeta(c), o.optString("audio"));
            if (!f.exists()) return "";  // la grabación ya no está: se descarta
            audio = leer(f);
            pedido.put("audio", Base64.encodeToString(audio, Base64.NO_WRAP));
        } else {
            pedido.put("texto", o.optString("texto"));
        }
        Respuesta r = enviar(base + "/telefono/memoria", "application/json",
                pedido.toString().getBytes(StandardCharsets.UTF_8), clave);
        JSONObject d;
        if (r.codigo == 404) {
            // Jarvis en el PC todavía no conoce la memoria: se le manda como si se lo dijeras ahora
            if (audio != null) {
                r = enviar(base + "/oir", "audio/wav", audio, clave);
            } else {
                String texto = o.optString("texto");
                if (o.optString("tipo").equals("idea")) texto = "Anota esta idea en el bloc Ideas desde el teléfono: " + texto;
                r = enviar(base + "/preguntar", "application/json",
                        new JSONObject().put("texto", texto).toString().getBytes(StandardCharsets.UTF_8), clave);
            }
        }
        if (r.codigo == 401) throw new IllegalStateException("clave incorrecta");
        if (r.codigo >= 500) return "No pude atender algo que guardaste (" + o.optString("hora") + "): Jarvis tuvo un problema.";
        d = new JSONObject(r.cuerpo);
        String dicho = d.optString("texto", d.optString("oido", o.optString("texto")));
        if (d.has("telefono")) {
            return "«" + dicho + "» era para el teléfono; dímelo de nuevo cuando quieras que lo haga.";
        }
        String respuesta = d.optString("respuesta", d.optString("error", ""));
        if (dicho.isEmpty()) return respuesta;
        return "«" + dicho + "» (" + o.optString("hora") + "): " + respuesta;
    }

    private static void avisarEntrega(Context c, int n, List<String> resultados) {
        String titulo = n == 1 ? "Jarvis recibió 1 cosa que dejaste guardada" : "Jarvis recibió " + n + " cosas que dejaste guardadas";
        Escucha.Oyente o = Escucha.oyente;
        if (o != null) {
            o.evento("aviso", titulo + ".");
            for (String r : resultados) o.evento("jarvis", r);
        }
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CANAL) == null) {
                nm.createNotificationChannel(new NotificationChannel(CANAL, "Memoria de Jarvis", NotificationManager.IMPORTANCE_DEFAULT));
            }
            PendingIntent abrir = PendingIntent.getActivity(c, 11, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            android.app.Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new android.app.Notification.Builder(c, CANAL) : new android.app.Notification.Builder(c);
            String detalle = String.join("\n", resultados);
            b.setSmallIcon(android.R.drawable.ic_menu_agenda).setContentTitle(titulo)
                    .setContentText(resultados.isEmpty() ? "Listo." : resultados.get(0))
                    .setStyle(new android.app.Notification.BigTextStyle().bigText(detalle.isEmpty() ? "Listo." : detalle))
                    .setContentIntent(abrir).setAutoCancel(true);
            nm.notify(5, b.build());
        } catch (Exception ignorado) { }
    }

    // ------------------------------------------------------------------ red y archivos
    static final class Respuesta {
        final int codigo;
        final String cuerpo;
        Respuesta(int codigo, String cuerpo) { this.codigo = codigo; this.cuerpo = cuerpo; }
    }

    private static Respuesta enviar(String url, String tipo, byte[] cuerpo, String clave) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(240000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", tipo);
        c.setRequestProperty("X-Jarvis", "1");
        c.setRequestProperty("X-Jarvis-Clave", clave);
        c.setRequestProperty("X-Jarvis-Voz", "0");
        c.setRequestProperty("X-Jarvis-Telefono", "1");
        c.setFixedLengthStreamingMode(cuerpo.length);
        try (OutputStream salida = c.getOutputStream()) { salida.write(cuerpo); }
        int codigo = c.getResponseCode();
        InputStream entrada = codigo >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream datos = new ByteArrayOutputStream();
        byte[] t = new byte[16384];
        int n;
        while (entrada != null && (n = entrada.read(t)) > 0) datos.write(t, 0, n);
        c.disconnect();
        return new Respuesta(codigo, datos.toString("UTF-8"));
    }

    private static byte[] leer(File f) throws java.io.IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] t = new byte[16384];
            int n;
            while ((n = in.read(t)) > 0) b.write(t, 0, n);
            return b.toByteArray();
        }
    }
}
