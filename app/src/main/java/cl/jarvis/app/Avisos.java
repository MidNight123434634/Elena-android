package cl.jarvis.app;

import android.app.Notification;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.speech.tts.TextToSpeech;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Lee las notificaciones del teléfono (solo si el usuario lo permite en Ajustes › Acceso a notificaciones).
 * Las guarda en memoria, en el teléfono: nunca se mandan al PC.
 */
public class Avisos extends NotificationListenerService {

    static final String PREFERENCIAS = "jarvis";
    static final String LEER_SOLO = "leer_notificaciones_solo";
    private static final int MAXIMO = 60;

    /** Una notificación guardada. */
    static class Aviso {
        final String paquete, app, titulo, texto;
        final long hora;
        Aviso(String paquete, String app, String titulo, String texto, long hora) {
            this.paquete = paquete; this.app = app; this.titulo = titulo; this.texto = texto; this.hora = hora;
        }
    }

    private static final ArrayDeque<Aviso> recientes = new ArrayDeque<>();
    private static volatile boolean conectado = false;
    private TextToSpeech voz;
    private boolean vozLista = false;

    static boolean conectado() { return conectado; }

    @Override
    public void onListenerConnected() {
        conectado = true;
        try {
            for (StatusBarNotification n : getActiveNotifications()) guardar(n, false);
        } catch (Exception ignorado) { }
    }

    @Override
    public void onListenerDisconnected() { conectado = false; }

    @Override
    public void onNotificationPosted(StatusBarNotification n) { guardar(n, true); }

    @Override
    public void onNotificationRemoved(StatusBarNotification n) {
        synchronized (recientes) {
            Iterator<Aviso> it = recientes.iterator();
            while (it.hasNext()) {
                Aviso a = it.next();
                if (a.paquete.equals(n.getPackageName()) && a.hora == n.getPostTime()) it.remove();
            }
        }
    }

    @Override
    public void onDestroy() {
        if (voz != null) voz.shutdown();
        super.onDestroy();
    }

    private void guardar(StatusBarNotification n, boolean nueva) {
        if (n.getPackageName().equals(getPackageName()) || n.isOngoing()) return;
        Notification noti = n.getNotification();
        if ((noti.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        Bundle extras = noti.extras;
        String titulo = texto(extras.getCharSequence(Notification.EXTRA_TITLE));
        String cuerpo = texto(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if (cuerpo.isEmpty()) cuerpo = texto(extras.getCharSequence(Notification.EXTRA_TEXT));
        if (titulo.isEmpty() && cuerpo.isEmpty()) return;
        Aviso aviso = new Aviso(n.getPackageName(), nombreApp(this, n.getPackageName()), titulo, cuerpo, n.getPostTime());
        synchronized (recientes) {
            recientes.removeIf(a -> a.paquete.equals(aviso.paquete) && a.titulo.equals(aviso.titulo) && a.texto.equals(aviso.texto));
            recientes.addFirst(aviso);
            while (recientes.size() > MAXIMO) recientes.removeLast();
        }
        SharedPreferences pref = getSharedPreferences(PREFERENCIAS, Context.MODE_PRIVATE);
        if (nueva && pref.getBoolean(LEER_SOLO, false)) decir(frase(aviso));
    }

    private void decir(final String frase) {
        if (Acciones.enLlamada(this)) return;  // en llamada Jarvis no habla
        if (voz == null) {
            voz = new TextToSpeech(this, estado -> {
                vozLista = estado == TextToSpeech.SUCCESS;
                if (vozLista) {
                    voz.setLanguage(new Locale("es", "ES"));
                    voz.speak(frase, TextToSpeech.QUEUE_ADD, null, "aviso");
                }
            });
        } else if (vozLista) {
            voz.speak(frase, TextToSpeech.QUEUE_ADD, null, "aviso");
        }
    }

    static String texto(CharSequence c) {
        return c == null ? "" : c.toString().replaceAll("\\s+", " ").trim();
    }

    static String nombreApp(Context contexto, String paquete) {
        try {
            PackageManager pm = contexto.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(paquete, 0);
            return pm.getApplicationLabel(info).toString();
        } catch (Exception e) {
            return paquete;
        }
    }

    /** Para leer una notificación nueva en voz alta: solo de qué app y de quién, sin el texto. */
    static String frase(Aviso a) {
        String quien = corto(a.titulo, 40);
        return quien.isEmpty() ? "Notificación de " + a.app : a.app + ", de " + quien;
    }

    private static String corto(String texto, int largo) {
        return texto.length() > largo ? texto.substring(0, largo).trim() + "…" : texto;
    }

    /** Resumen de las notificaciones (todas o de una app): cuántas, de qué apps y de quién. No lee el texto. */
    static String resumen(String filtroApp) {
        List<Aviso> lista = new ArrayList<>();
        String filtro = filtroApp == null ? "" : MainActivity.simple(filtroApp);
        synchronized (recientes) {
            for (Aviso a : recientes) {
                if (filtro.isEmpty() || MainActivity.simple(a.app).contains(filtro)) lista.add(a);
            }
        }
        if (!conectado && lista.isEmpty()) {
            return "Todavía no me diste permiso para leer las notificaciones. Tócame en el engranaje y elige «Permitir leer notificaciones».";
        }
        String de = filtro.isEmpty() ? "" : " de " + filtroApp;
        if (lista.isEmpty()) return "No tienes notificaciones" + de + ".";
        // agrupadas por app, con quién las mandó (sin repetir)
        java.util.LinkedHashMap<String, java.util.LinkedHashSet<String>> porApp = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, Integer> cuantas = new java.util.LinkedHashMap<>();
        for (Aviso a : lista) {
            porApp.computeIfAbsent(a.app, k -> new java.util.LinkedHashSet<>());
            if (!a.titulo.isEmpty()) porApp.get(a.app).add(corto(a.titulo, 30));
            cuantas.merge(a.app, 1, Integer::sum);
        }
        int total = lista.size();
        StringBuilder s = new StringBuilder(total == 1 ? "Tienes una notificación" + de : "Tienes " + total + " notificaciones" + de);
        List<String> partes = new ArrayList<>();
        int apps = 0;
        for (String app : porApp.keySet()) {
            if (apps++ == 5) { partes.add("y otras apps"); break; }
            List<String> quienes = new ArrayList<>(porApp.get(app));
            String gente = quienes.isEmpty() ? "" : String.join(", ", quienes.subList(0, Math.min(3, quienes.size())))
                    + (quienes.size() > 3 ? " y otros" : "");
            if (filtro.isEmpty()) {
                partes.add(cuantas.get(app) + " de " + app + (gente.isEmpty() ? "" : " (" + gente + ")"));
            } else if (!gente.isEmpty()) {
                partes.add("de " + gente);
            }
        }
        if (!partes.isEmpty()) s.append(": ").append(String.join(", ", partes));
        s.append(". Si quieres que te lea una, dime «léeme la de» y el nombre.");
        return s.toString();
    }

    /** «Léeme el mensaje de Juan»: lee esa notificación (la más reciente de esa persona o app), resumida. */
    static String leerUno(String de) {
        String buscado = MainActivity.simple(de);
        if (buscado.isEmpty()) return "¿De quién quieres que te lea el mensaje?";
        synchronized (recientes) {
            for (Aviso a : recientes) {
                if (MainActivity.simple(a.titulo).contains(buscado) || MainActivity.simple(a.app).equals(buscado)) {
                    String texto = corto(a.texto, 200);
                    return (a.titulo.isEmpty() ? a.app : a.titulo) + (texto.isEmpty() ? " no escribió texto." : " dice: " + texto);
                }
            }
        }
        return "No tengo notificaciones de «" + de + "».";
    }
}
