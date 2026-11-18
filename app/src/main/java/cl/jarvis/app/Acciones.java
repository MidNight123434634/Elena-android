package cl.jarvis.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.telecom.TelecomManager;

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
        switch (tipo) {
            case "abrir": return abrirApp(c, accion.optString("app"));
            case "notificaciones": return Avisos.resumen(accion.optString("app", ""));
            case "llamar": return llamar(c, accion.optString("contacto"));
            case "contestar": case "colgar": case "bloquear": case "llamadas": return llamada(c, tipo);
            default: return "";
        }
    }

    /** Texto sin tildes, en minúsculas y sin signos (para comparar nombres). */
    static String simple(String texto) {
        String t = Normalizer.normalize(texto == null ? "" : texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return t.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

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
            c.startActivity(abrir.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            return "Android no me dejó abrir " + elegida[0] + " desde segundo plano. Activa «Abrir apps con la pantalla "
                    + "en otra app» en mis ajustes.";
        }
        return "Abriendo " + elegida[0] + ".";
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

    /** «Llama a Juan»: busca el contacto y llama (o deja el número listo si no hay permiso para llamar). */
    static String llamar(Context c, String nombre) {
        String buscado = simple(nombre).replaceAll("^(a la |a |al |el |la |mi |mis |don |dona |senor |senora )+", "");
        if (buscado.isEmpty()) return "¿A quién llamo?";
        if (!permiso(c, Manifest.permission.READ_CONTACTS)) {
            return "Para llamar a tus contactos necesito permiso para verlos: actívalo en mis ajustes, en «Llamadas».";
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
            return "No pude revisar tus contactos.";
        }
        if (candidatos.isEmpty()) return "No encontré a «" + nombre + "» en tus contactos.";
        if (candidatos.size() > 1) {
            List<String> nombres = new ArrayList<>(candidatos.keySet());
            return "Tengo varios: " + String.join(", ", nombres.subList(0, Math.min(4, nombres.size())))
                    + ". Dime el nombre completo de a quién llamo.";
        }
        Map.Entry<String, String> elegido = candidatos.entrySet().iterator().next();
        Uri tel = Uri.fromParts("tel", elegido.getValue(), null);
        if (permiso(c, Manifest.permission.CALL_PHONE)) {
            try {
                c.getSystemService(TelecomManager.class).placeCall(tel, new Bundle());
                return "Llamando a " + elegido.getKey() + ".";
            } catch (Exception ignorado) { }
        }
        try {
            c.startActivity(new Intent(Intent.ACTION_DIAL, tel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return "Te dejé el número de " + elegido.getKey() + " listo; toca llamar.";
        } catch (Exception e) {
            return "No pude llamar a " + elegido.getKey() + ".";
        }
    }
}
