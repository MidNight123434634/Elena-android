package cl.jarvis.app;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Actualiza la app sola: le pregunta a Elena (en el PC) qué versión tiene, y si hay una más nueva la
 * descarga e instala. Android siempre muestra un aviso para confirmar la instalación (eso no se puede saltar).
 */
class Actualizador {

    static final String ACCION_RESULTADO = "cl.jarvis.app.RESULTADO_ACTUALIZACION";

    interface Aviso { void decir(String texto); }

    private final Activity actividad;
    private final Aviso aviso;
    private volatile boolean trabajando = false;
    private String servidorPendiente, clavePendiente;

    Actualizador(Activity actividad, Aviso aviso) {
        this.actividad = actividad;
        this.aviso = aviso;
    }

    static long versionInstalada(Activity actividad) {
        try {
            PackageInfo info = actividad.getPackageManager().getPackageInfo(actividad.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Se llama en segundo plano. Si hay versión nueva, la baja y abre el aviso de instalación. */
    void revisar(String servidor, String clave, boolean avisarSiNoHay) {
        if (trabajando) return;
        trabajando = true;
        try {
            String base = servidor.trim().replaceAll("/+$", "");
            JSONObject respuesta = new JSONObject(new String(pedir(base + "/app/version", clave, 1 << 16), "UTF-8"));
            long nueva = respuesta.optLong("version", 0);
            long actual = versionInstalada(actividad);
            if (nueva <= actual) {
                if (avisarSiNoHay) aviso.decir("Ya tienes la última versión de la app (" + actual + ").");
                return;
            }
            if (Build.VERSION.SDK_INT >= 26 && !actividad.getPackageManager().canRequestPackageInstalls()) {
                servidorPendiente = servidor;
                clavePendiente = clave;
                aviso.decir("Hay una versión nueva de la app. Para que se actualice sola, permite que Elena instale "
                        + "apps (activa «Permitir de esta fuente») y vuelve a la app.");
                actividad.runOnUiThread(() -> actividad.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + actividad.getPackageName()))));
                return;
            }
            aviso.decir("Descargando la versión nueva de la app (" + nueva + ")…");
            byte[] apk = pedir(base + "/app/jarvis.apk", clave, 80 << 20);
            instalar(apk);
        } catch (Exception e) {
            if (avisarSiNoHay) aviso.decir("No pude revisar si hay una versión nueva: " + e.getMessage());
        } finally {
            trabajando = false;
        }
    }

    /** Al volver de los Ajustes de Android: si quedó una actualización esperando el permiso, sigue. */
    void continuarSiEspera() {
        if (servidorPendiente == null) return;
        if (Build.VERSION.SDK_INT >= 26 && !actividad.getPackageManager().canRequestPackageInstalls()) return;
        final String servidor = servidorPendiente, clave = clavePendiente;
        servidorPendiente = null;
        new Thread(() -> revisar(servidor, clave, false)).start();
    }

    private void instalar(byte[] apk) throws Exception {
        PackageInstaller instalador = actividad.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams parametros =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int id = instalador.createSession(parametros);
        try (PackageInstaller.Session sesion = instalador.openSession(id)) {
            try (OutputStream salida = sesion.openWrite("jarvis.apk", 0, apk.length)) {
                salida.write(apk);
                sesion.fsync(salida);
            }
            Intent resultado = new Intent(actividad, MainActivity.class).setAction(ACCION_RESULTADO)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int banderas = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pendiente = PendingIntent.getActivity(actividad, 7, resultado, banderas);
            sesion.commit(pendiente.getIntentSender());
        }
    }

    /** Lo que responde Android después de pedir la instalación. */
    void resultado(Intent intent) {
        int estado = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (estado == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmar = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmar != null) actividad.startActivity(confirmar.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else if (estado != PackageInstaller.STATUS_SUCCESS) {
            String motivo = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            aviso.decir("No se pudo actualizar la app" + (motivo != null ? " (" + motivo + ")" : "") + ".");
        }
    }

    private static byte[] pedir(String url, String clave, int maximo) throws Exception {
        if (!url.startsWith("https://")) throw new IllegalArgumentException("la dirección debe empezar con https://");
        HttpURLConnection conexion = (HttpURLConnection) new URL(url).openConnection();
        conexion.setConnectTimeout(8000);
        conexion.setReadTimeout(120000);
        conexion.setRequestProperty("X-Jarvis", "1");
        conexion.setRequestProperty("X-Jarvis-Clave", clave == null ? "" : clave);
        int codigo = conexion.getResponseCode();
        if (codigo != 200) throw new IllegalStateException("Elena respondió " + codigo);
        try (InputStream entrada = conexion.getInputStream()) {
            ByteArrayOutputStream datos = new ByteArrayOutputStream();
            byte[] trozo = new byte[65536];
            int n;
            while ((n = entrada.read(trozo)) > 0) {
                datos.write(trozo, 0, n);
                if (datos.size() > maximo) throw new IllegalStateException("archivo demasiado grande");
            }
            return datos.toByteArray();
        } finally {
            conexion.disconnect();
        }
    }
}
