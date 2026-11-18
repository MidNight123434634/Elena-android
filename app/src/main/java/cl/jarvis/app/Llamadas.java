package cl.jarvis.app;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.ContactsContract;
import android.speech.tts.TextToSpeech;
import android.telecom.Call;
import android.telecom.CallScreeningService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Filtro de llamadas: Android le pregunta a Jarvis por cada llamada entrante y Jarvis decide si la deja pasar.
 * Todo pasa en el teléfono (no se manda nada al PC). Solo funciona si el usuario elige a Jarvis como
 * «app de identificación de llamadas y spam» (se pide desde ⚙ en la app).
 *
 * Reglas (se cambian en ⚙):
 *  - Prefijo 809: en Chile es obligatorio para el telemarketing no solicitado (spam). Activada.
 *  - Números que el usuario bloqueó («bloquea esa llamada»). Siempre.
 *  - Números ocultos. Desactivada.
 *  - Todos los que no están en tus contactos. Desactivada (muy estricta).
 */
public class Llamadas extends CallScreeningService {

    static final String BLOQUEAR_809 = "llamadas_bloquear_809";
    static final String BLOQUEAR_OCULTOS = "llamadas_bloquear_ocultos";
    static final String BLOQUEAR_DESCONOCIDOS = "llamadas_bloquear_desconocidos";
    static final String ANUNCIAR = "llamadas_anunciar";
    static final String BLOQUEADOS = "llamadas_numeros_bloqueados";
    static final String REGISTRO = "llamadas_registro";
    static final String ULTIMA = "llamadas_ultima";

    @Override
    public void onScreenCall(Call.Details detalles) {
        if (Build.VERSION.SDK_INT >= 29 && detalles.getCallDirection() != Call.Details.DIRECTION_INCOMING) {
            respondToCall(detalles, new CallResponse.Builder().build());
            return;
        }
        SharedPreferences pref = preferencias(this);
        Uri dato = detalles.getHandle();
        String numero = dato == null ? "" : dato.getSchemeSpecificPart();
        String limpio = limpiar(numero);
        String nombre = limpio.isEmpty() ? null : contacto(this, numero);

        String motivo = null;
        if (!limpio.isEmpty() && pref.getStringSet(BLOQUEADOS, new HashSet<>()).contains(limpio)) {
            motivo = "número que bloqueaste";
        } else if (nombre == null && pref.getBoolean(BLOQUEAR_809, true) && esPrefijo809(limpio)) {
            motivo = "prefijo 809 (telemarketing)";
        } else if (limpio.isEmpty() && pref.getBoolean(BLOQUEAR_OCULTOS, false)) {
            motivo = "número oculto";
        } else if (nombre == null && pref.getBoolean(BLOQUEAR_DESCONOCIDOS, false)) {
            motivo = "no está en tus contactos";
        } else if (Build.VERSION.SDK_INT >= 30 && nombre == null
                && detalles.getCallerNumberVerificationStatus() == android.telecom.Connection.VERIFICATION_STATUS_FAILED) {
            motivo = "número falsificado";
        }

        pref.edit().putString(ULTIMA, limpio).apply();
        if (motivo != null) {
            respondToCall(detalles, new CallResponse.Builder()
                    .setDisallowCall(true).setRejectCall(true).setSkipCallLog(false).setSkipNotification(true).build());
            anotar(this, numero, motivo);
        } else {
            respondToCall(detalles, new CallResponse.Builder().build());
            if (pref.getBoolean(ANUNCIAR, true)) {
                Voz.decir(this, nombre != null ? "Te llama " + nombre
                        : limpio.isEmpty() ? "Te llama un número oculto"
                        : "Te llama un número desconocido terminado en " + deletreo(limpio));
            }
        }
    }

    // ------------------------------------------------------------------ ayudas
    static SharedPreferences preferencias(Context c) {
        return c.getSharedPreferences(Avisos.PREFERENCIAS, Context.MODE_PRIVATE);
    }

    /** Solo los dígitos (y el + inicial). */
    static String limpiar(String numero) {
        if (numero == null) return "";
        String s = numero.replaceAll("[^0-9+]", "");
        return s.replaceAll("(?<!^)\\+", "");
    }

    static boolean esPrefijo809(String limpio) {
        String sinPais = limpio.startsWith("+56") ? limpio.substring(3) : limpio.startsWith("56") && limpio.length() > 10
                ? limpio.substring(2) : limpio;
        return sinPais.startsWith("809");
    }

    private static String deletreo(String limpio) {
        String fin = limpio.length() > 4 ? limpio.substring(limpio.length() - 4) : limpio;
        return String.join(" ", fin.split(""));
    }

    static String contacto(Context c, String numero) {
        if (c.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null;
        Uri busqueda = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(numero));
        try (Cursor cursor = c.getContentResolver().query(busqueda,
                new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignorado) { }
        return null;
    }

    private static synchronized void anotar(Context c, String numero, String motivo) {
        SharedPreferences pref = preferencias(c);
        try {
            JSONArray lista = new JSONArray(pref.getString(REGISTRO, "[]"));
            JSONArray nueva = new JSONArray();
            nueva.put(new JSONObject().put("numero", numero == null || numero.isEmpty() ? "oculto" : numero)
                    .put("motivo", motivo).put("hora", new SimpleDateFormat("dd/MM HH:mm", Locale.ROOT).format(new Date())));
            for (int i = 0; i < Math.min(lista.length(), 29); i++) nueva.put(lista.get(i));
            pref.edit().putString(REGISTRO, nueva.toString()).apply();
        } catch (Exception ignorado) { }
    }

    /** «Bloquea esa llamada»: bloquea el último número que llamó. */
    static String bloquearUltima(Context c) {
        SharedPreferences pref = preferencias(c);
        String ultima = pref.getString(ULTIMA, "");
        if (ultima.isEmpty()) return "No tengo registrada ninguna llamada reciente con número para bloquear.";
        Set<String> bloqueados = new HashSet<>(pref.getStringSet(BLOQUEADOS, new HashSet<>()));
        bloqueados.add(ultima);
        pref.edit().putStringSet(BLOQUEADOS, bloqueados).apply();
        return "Listo, bloqueé el número " + ultima + ". Ya no te va a sonar.";
    }

    /** Resumen de las llamadas bloqueadas. */
    static String resumen(Context c) {
        try {
            JSONArray lista = new JSONArray(preferencias(c).getString(REGISTRO, "[]"));
            if (lista.length() == 0) return "No he bloqueado ninguna llamada.";
            StringBuilder s = new StringBuilder(lista.length() == 1 ? "Bloqueé una llamada: "
                    : "Bloqueé " + lista.length() + " llamadas. Las últimas: ");
            for (int i = 0; i < Math.min(lista.length(), 5); i++) {
                JSONObject l = lista.getJSONObject(i);
                s.append(l.getString("numero")).append(" el ").append(l.getString("hora"))
                        .append(" (").append(l.getString("motivo")).append("). ");
            }
            return s.toString().trim();
        } catch (Exception e) {
            return "No pude leer el registro de llamadas bloqueadas.";
        }
    }

    /** Una voz que sigue sonando aunque el filtro termine (el filtro dura muy poco). */
    static class Voz {
        private static TextToSpeech voz;
        private static boolean lista = false;

        static synchronized void decir(Context c, final String texto) {
            if (voz == null) {
                voz = new TextToSpeech(c.getApplicationContext(), estado -> {
                    lista = estado == TextToSpeech.SUCCESS;
                    if (lista) {
                        voz.setLanguage(new Locale("es", "ES"));
                        voz.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "llamada");
                    }
                });
            } else if (lista) {
                voz.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "llamada");
            }
        }
    }
}
