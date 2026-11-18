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

    static String frase(Aviso a) {
        String cuerpo = a.texto.length() > 160 ? a.texto.substring(0, 160) + "…" : a.texto;
        StringBuilder s = new StringBuilder(a.app);
        if (!a.titulo.isEmpty()) s.append(", de ").append(a.titulo);
        if (!cuerpo.isEmpty()) s.append(": ").append(cuerpo);
        return s.toString();
    }

    /** Resumen en español de las notificaciones (todas o de una app). */
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
        int total = lista.size();
        StringBuilder s = new StringBuilder(total == 1 ? "Tienes una notificación" + de + ". "
                : "Tienes " + total + " notificaciones" + de + ". ");
        int leidas = 0;
        for (Aviso a : lista) {
            if (leidas == 6) { s.append("Y ").append(total - 6).append(" más."); break; }
            s.append(frase(a)).append(". ");
            leidas++;
        }
        return s.toString().trim();
    }
}
