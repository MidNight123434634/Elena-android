package cl.jarvis.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.view.KeyEvent;

import org.json.JSONObject;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Lo que Jarvis puede hacer en el propio teléfono. Lo usan la pantalla y la escucha con «Jarvis». */
final class Acciones {

    private Acciones() { }

    /** Hace la acción que mandó Jarvis desde el PC y devuelve lo que hay que decir. */
    static String ejecutar(Context c, JSONObject accion) {
        String tipo = accion.optString("accion");
        try {
            switch (tipo) {
                case "abrir": return abrirApp(c, accion.optString("app"));
                case "notificaciones": return Avisos.resumen(accion.optString("app", ""));
                case "leer_aviso": return Avisos.leerUno(accion.optString("de", ""));
                case "llamar": return llamar(c, accion.optString("contacto"));
                case "contestar": case "colgar": case "bloquear": case "llamadas": return llamada(c, tipo);
                case "linterna": return linterna(c, accion.optBoolean("encender", true));
                case "bateria": return bateria(c);
                case "volumen": return volumen(c, accion.optString("cambio", "subir"), accion.optInt("nivel", -1));
                case "musica": return musica(c, accion.optString("orden", "pausa"));
                case "alarma": return alarma(c, accion.optInt("hora", -1), accion.optInt("minuto", 0), accion.optString("texto", ""));
                case "temporizador": return temporizador(c, accion.optInt("segundos", 0));
                case "whatsapp": return whatsapp(c, accion.optString("contacto"), accion.optString("mensaje", ""));
                case "navegar": return navegar(c, accion.optString("destino"));
                case "ajustes": return ajustes(c, accion.optString("cual"));
                default: return "Esa orden para el teléfono todavía no la sé hacer. Actualiza la app.";
            }
        } catch (Exception e) {
            return "No pude hacerlo en este teléfono.";
        }
    }

    /** Texto sin tildes, en minúsculas y sin signos (para comparar nombres). */
    static String simple(String texto) {
        String t = Normalizer.normalize(texto == null ? "" : texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return t.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    /** ¿Hay una llamada en curso (normal, WhatsApp, Meet...)? Mientras tanto Jarvis no habla ni escucha. */
    static boolean enLlamada(Context c) {
        AudioManager audio = c.getSystemService(AudioManager.class);
        if (audio == null) return false;
        int modo = audio.getMode();
        return modo == AudioManager.MODE_IN_CALL || modo == AudioManager.MODE_IN_COMMUNICATION;
    }

    private static void abrir(Context c, Intent intento) {
        c.startActivity(intento.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private static final String SIN_SEGUNDO_PLANO = " Si no se abrió, activa «Abrir apps con la pantalla en otra app» en mis ajustes.";

    // ------------------------------------------------------------------ apps
    static List<String[]> apps(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent inicio = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<String[]> lista = new ArrayList<>();
        for (ResolveInfo r : pm.queryIntentActivities(inicio, 0)) {
            if (r.activityInfo.packageName.equals(c.getPackageName())) continue;
            lista.add(new String[]{r.loadLabel(pm).toString(), r.activityInfo.packageName});
        }
        return lista;
    }

    static String abrirApp(Context c, String nombre) {
        String buscado = simple(nombre).replace("guasap", "whatsapp").replace("wasap", "whatsapp")
                .replace("wsp", "whatsapp").replace("yutub", "youtube");
        if (buscado.isEmpty()) return "¿Qué app quieres que abra?";
        String[] elegida = null;
        int mejor = 0;
        for (String[] app : apps(c)) {
            String etiqueta = simple(app[0]);
            int puntos = etiqueta.equals(buscado) ? 3 : etiqueta.startsWith(buscado) || buscado.startsWith(etiqueta) ? 2
                    : (buscado.length() >= 4 && etiqueta.contains(buscado)) ? 1 : 0;
            if (puntos > mejor || (puntos == mejor && puntos > 0 && elegida != null && app[0].length() < elegida[0].length())) {
                mejor = puntos;
                elegida = app;
            }
        }
        if (elegida == null) return "No encontré una app llamada «" + nombre + "» en el teléfono.";
        Intent abrir = c.getPackageManager().getLaunchIntentForPackage(elegida[1]);
        if (abrir == null) return "No puedo abrir " + elegida[0] + ".";
        try {
            abrir(c, abrir);
        } catch (Exception e) {
            return "Android no me dejó abrir " + elegida[0] + " desde segundo plano." + SIN_SEGUNDO_PLANO;
        }
        return "Abriendo " + elegida[0] + ".";
    }

    // ------------------------------------------------------------------ linterna, batería, volumen, música
    static String linterna(Context c, boolean encender) throws Exception {
        CameraManager camaras = c.getSystemService(CameraManager.class);
        for (String id : camaras.getCameraIdList()) {
            Boolean flash = camaras.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (flash != null && flash) {
                camaras.setTorchMode(id, encender);
                return encender ? "Linterna encendida." : "Linterna apagada.";
            }
        }
        return "Este teléfono no tiene linterna.";
    }

    static String bateria(Context c) {
        BatteryManager bm = c.getSystemService(BatteryManager.class);
        int nivel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        Intent estado = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int carga = estado == null ? -1 : estado.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean cargando = carga == BatteryManager.BATTERY_STATUS_CHARGING || carga == BatteryManager.BATTERY_STATUS_FULL;
        String texto = "Te queda " + nivel + " por ciento de batería";
        return texto + (cargando ? ", y se está cargando." : nivel <= 20 ? ". Conviene cargarlo pronto." : ".");
    }

    static String volumen(Context c, String cambio, int nivel) {
        AudioManager audio = c.getSystemService(AudioManager.class);
        int flujo = AudioManager.STREAM_MUSIC, maximo = audio.getStreamMaxVolume(flujo);
        switch (cambio) {
            case "silencio":
                audio.adjustStreamVolume(flujo, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI);
                return "Silencié el sonido multimedia.";
            case "maximo":
                audio.setStreamVolume(flujo, maximo, AudioManager.FLAG_SHOW_UI);
                return "Volumen al máximo.";
            case "nivel":
                int destino = Math.round(Math.max(0, Math.min(100, nivel)) * maximo / 100f);
                audio.setStreamVolume(flujo, destino, AudioManager.FLAG_SHOW_UI);
                return "Volumen al " + Math.max(0, Math.min(100, nivel)) + " por ciento.";
            case "bajar":
                audio.adjustStreamVolume(flujo, AudioManager.ADJUST_LOWER, 0);
                audio.adjustStreamVolume(flujo, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                return "Bajé el volumen.";
            default:
                audio.adjustStreamVolume(flujo, AudioManager.ADJUST_UNMUTE, 0);
                audio.adjustStreamVolume(flujo, AudioManager.ADJUST_RAISE, 0);
                audio.adjustStreamVolume(flujo, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                return "Subí el volumen.";
        }
    }

    static String musica(Context c, String orden) {
        int tecla;
        String texto;
        switch (orden) {
            case "siguiente": tecla = KeyEvent.KEYCODE_MEDIA_NEXT; texto = "Siguiente canción."; break;
            case "anterior": tecla = KeyEvent.KEYCODE_MEDIA_PREVIOUS; texto = "Canción anterior."; break;
            case "reproducir": tecla = KeyEvent.KEYCODE_MEDIA_PLAY; texto = "Reproduciendo."; break;
            default: tecla = KeyEvent.KEYCODE_MEDIA_PAUSE; texto = "Pausado."; break;
        }
        AudioManager audio = c.getSystemService(AudioManager.class);
        long ahora = SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(ahora, ahora, KeyEvent.ACTION_DOWN, tecla, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(ahora, ahora, KeyEvent.ACTION_UP, tecla, 0));
        return texto;
    }

    // ------------------------------------------------------------------ alarma, temporizador
    static String alarma(Context c, int hora, int minuto, String texto) {
        if (hora < 0 || hora > 23 || minuto < 0 || minuto > 59) return "¿A qué hora pongo la alarma?";
        Intent i = new Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hora).putExtra(AlarmClock.EXTRA_MINUTES, minuto)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        if (!texto.isEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, texto);
        try {
            abrir(c, i);
        } catch (Exception e) {
            return "No pude poner la alarma." + SIN_SEGUNDO_PLANO;
        }
        return String.format(Locale.ROOT, "Alarma puesta a las %d:%02d.", hora, minuto);
    }

    static String temporizador(Context c, int segundos) {
        if (segundos <= 0) return "¿De cuánto tiempo pongo el temporizador?";
        Intent i = new Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, segundos).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis");
        try {
            abrir(c, i);
        } catch (Exception e) {
            return "No pude poner el temporizador." + SIN_SEGUNDO_PLANO;
        }
        int min = segundos / 60, seg = segundos % 60;
        String duracion = min > 0 ? min + (min == 1 ? " minuto" : " minutos") + (seg > 0 ? " y " + seg + " segundos" : "")
                : seg + " segundos";
        return "Temporizador de " + duracion + " en marcha.";
    }

    // ------------------------------------------------------------------ WhatsApp, mapas, ajustes
    static String whatsapp(Context c, String nombre, String mensaje) {
        String[] contacto = buscarContacto(c, nombre);
        if (contacto[0] == null) return contacto[1];
        String numero = Llamadas.limpiar(contacto[1]).replace("+", "");
        if (numero.length() == 9 && numero.startsWith("9")) numero = "56" + numero;   // celular chileno sin +56
        Uri uri = Uri.parse("https://wa.me/" + numero + (mensaje.isEmpty() ? "" : "?text=" + Uri.encode(mensaje)));
        Intent i = new Intent(Intent.ACTION_VIEW, uri);
        if (c.getPackageManager().getLaunchIntentForPackage("com.whatsapp") != null) i.setPackage("com.whatsapp");
        try {
            abrir(c, i);
        } catch (Exception e) {
            return "No pude abrir WhatsApp." + SIN_SEGUNDO_PLANO;
        }
        return mensaje.isEmpty() ? "Abrí el chat de " + contacto[0] + " en WhatsApp."
                : "Dejé el mensaje para " + contacto[0] + " listo en WhatsApp; toca enviar.";
    }

    static String navegar(Context c, String destino) {
        if (destino == null || destino.trim().isEmpty()) return "¿A dónde quieres ir?";
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(destino)));
        if (i.resolveActivity(c.getPackageManager()) == null) {
            i = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(destino)));
        }
        try {
            abrir(c, i);
        } catch (Exception e) {
            return "No pude abrir el mapa." + SIN_SEGUNDO_PLANO;
        }
        return "Te llevo a " + destino + ".";
    }

    static String ajustes(Context c, String cual) {
        String accion, nombre;
        switch (cual) {
            case "wifi":
                accion = Build.VERSION.SDK_INT >= 29 ? Settings.Panel.ACTION_WIFI : Settings.ACTION_WIFI_SETTINGS;
                nombre = "el WiFi"; break;
            case "bluetooth": accion = Settings.ACTION_BLUETOOTH_SETTINGS; nombre = "el Bluetooth"; break;
            case "datos":
                accion = Build.VERSION.SDK_INT >= 29 ? Settings.Panel.ACTION_INTERNET_CONNECTIVITY : Settings.ACTION_DATA_ROAMING_SETTINGS;
                nombre = "los datos móviles"; break;
            case "no molestar": accion = "android.settings.ZEN_MODE_SETTINGS"; nombre = "No molestar"; break;
            case "pantalla": accion = Settings.ACTION_DISPLAY_SETTINGS; nombre = "la pantalla"; break;
            default: accion = Settings.ACTION_SETTINGS; nombre = "los ajustes"; break;
        }
        try {
            abrir(c, new Intent(accion));
        } catch (Exception e) {
            return "No pude abrir los ajustes." + SIN_SEGUNDO_PLANO;
        }
        return "Te abrí " + nombre + ". Android no deja que una app lo cambie sola: tócalo tú.";
    }

    // ------------------------------------------------------------------ llamadas
    static String llamada(Context c, String accion) {
        TelecomManager telefono = c.getSystemService(TelecomManager.class);
        try {
            switch (accion) {
                case "contestar":
                    if (!permiso(c, Manifest.permission.ANSWER_PHONE_CALLS)) return faltaPermisoLlamadas();
                    telefono.acceptRingingCall();
                    return "Contestando.";
                case "colgar":
                    if (!permiso(c, Manifest.permission.ANSWER_PHONE_CALLS)) return faltaPermisoLlamadas();
                    return telefono.endCall() ? "Colgué la llamada." : "No hay ninguna llamada que colgar.";
                case "bloquear":
                    return Llamadas.bloquearUltima(c);
                default:
                    return Llamadas.resumen(c);
            }
        } catch (SecurityException e) {
            return "Android no me dejó hacerlo: falta un permiso de llamadas.";
        } catch (Exception e) {
            return "No pude hacerlo en este teléfono.";
        }
    }

    private static String faltaPermisoLlamadas() {
        return "Para eso necesito el permiso de llamadas: actívalo en mis ajustes, en «Llamadas».";
    }

    static boolean permiso(Context c, String permiso) {
        return c.checkSelfPermission(permiso) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Busca un contacto por nombre. Devuelve {nombre, número} si encontró uno solo,
     * o {null, lo que hay que decir} si no lo encontró o hay varios.
     */
    static String[] buscarContacto(Context c, String nombre) {
        String buscado = simple(nombre).replaceAll("^(a la |a |al |el |la |mi |mis |don |dona |senor |senora )+", "");
        if (buscado.isEmpty()) return new String[]{null, "¿A quién?"};
        if (!permiso(c, Manifest.permission.READ_CONTACTS)) {
            return new String[]{null, "Para usar tus contactos necesito permiso para verlos: actívalo en mis ajustes, en «Llamadas»."};
        }
        Map<String, String> candidatos = new LinkedHashMap<>();  // nombre -> número
        int mejor = 0;
        String[] palabras = buscado.split(" ");
        try (Cursor cursor = c.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY}, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                String visible = cursor.getString(0), numero = cursor.getString(1);
                if (visible == null || numero == null) continue;
                String etiqueta = simple(visible);
                int puntos = etiqueta.equals(buscado) ? 4 : etiqueta.startsWith(buscado + " ") ? 3 : 0;
                if (puntos == 0) {
                    boolean todas = true;
                    for (String p : palabras) if (!(" " + etiqueta + " ").contains(" " + p)) { todas = false; break; }
                    if (todas) puntos = 2;
                    else if (buscado.length() >= 4 && etiqueta.contains(buscado)) puntos = 1;
                }
                if (puntos == 0 || puntos < mejor) continue;
                if (puntos > mejor) { candidatos.clear(); mejor = puntos; }
                if (!candidatos.containsKey(visible) || cursor.getInt(2) == 1) candidatos.put(visible, numero);
            }
        } catch (Exception e) {
            return new String[]{null, "No pude revisar tus contactos."};
        }
        if (candidatos.isEmpty()) return new String[]{null, "No encontré a «" + nombre + "» en tus contactos."};
        if (candidatos.size() > 1) {
            List<String> nombres = new ArrayList<>(candidatos.keySet());
            return new String[]{null, "Tengo varios: " + String.join(", ", nombres.subList(0, Math.min(4, nombres.size())))
                    + ". Dime el nombre completo."};
        }
        Map.Entry<String, String> elegido = candidatos.entrySet().iterator().next();
        return new String[]{elegido.getKey(), elegido.getValue()};
    }

    /** «Llama a Juan»: busca el contacto y llama (o deja el número listo si no hay permiso para llamar). */
    static String llamar(Context c, String nombre) {
        String[] contacto = buscarContacto(c, nombre);
        if (contacto[0] == null) return contacto[1];
        Uri tel = Uri.fromParts("tel", contacto[1], null);
        if (permiso(c, Manifest.permission.CALL_PHONE)) {
            try {
                c.getSystemService(TelecomManager.class).placeCall(tel, new Bundle());
                return "Llamando a " + contacto[0] + ".";
            } catch (Exception ignorado) { }
        }
        try {
            abrir(c, new Intent(Intent.ACTION_DIAL, tel));
            return "Te dejé el número de " + contacto[0] + " listo; toca llamar.";
        } catch (Exception e) {
            return "No pude llamar a " + contacto[0] + ".";
        }
    }
}
