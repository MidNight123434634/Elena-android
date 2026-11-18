package cl.jarvis.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.IBinder;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.StorageService;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Escucha siempre (con la pantalla apagada también) y se despierta al oír «Jarvis».
 *
 * La palabra «Jarvis» se reconoce DENTRO del teléfono, sin internet (Vosk). Solo después de oírla se graba la
 * orden y se manda a Jarvis en el PC (por NetBird), igual que con el botón del micrófono.
 * Android exige mostrar una notificación fija mientras el micrófono está activo.
 */
public class Escucha extends Service {

    static final String ESCUCHAR_SIEMPRE = "escuchar_siempre";
    static final String SENSIBILIDAD = "escucha_sensibilidad";   // 1 (estricta) a 3 (sensible)
    static final String SERVIDOR = "conexion_servidor";
    static final String CLAVE = "conexion_clave";
    private static final String CANAL = "escucha";
    private static final int TASA = 16000;

    /** La pantalla de la app se entera de lo que pasa (para mostrarlo en el chat). */
    interface Oyente { void evento(String quien, String texto); }
    static volatile Oyente oyente;
    private static volatile boolean pausada = false;
    private static volatile boolean activa = false;

    private Thread hilo;
    private volatile boolean seguir = true;
    private TextToSpeech voz;
    private volatile boolean vozLista = false;
    private Model modelo;

    static boolean activa() { return activa; }

    /** Mientras se usa el botón del micrófono de la app, la escucha se pausa (el micrófono es uno solo). */
    static void pausar(boolean si) { pausada = si; }

    static void iniciar(Context c) {
        Intent i = new Intent(c, Escucha.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    static void detener(Context c) { c.stopService(new Intent(c, Escucha.class)); }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "detener".equals(intent.getAction())) {
            Llamadas.preferencias(this).edit().putBoolean(ESCUCHAR_SIEMPRE, false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        mostrarNotificacion("Di «Jarvis» para hablarme");
        if (hilo == null) {
            activa = true;
            voz = new TextToSpeech(this, estado -> {
                vozLista = estado == TextToSpeech.SUCCESS;
                if (vozLista) voz.setLanguage(new Locale("es", "ES"));
            });
            avisar("aviso", "Preparando la escucha…");
            StorageService.unpack(this, "model-en-us", "modelo_jarvis", m -> {
                modelo = m;
                hilo = new Thread(this::bucle, "escucha-jarvis");
                hilo.start();
                avisar("aviso", "Te escucho: di «Jarvis» cuando me necesites.");
            }, e -> {
                avisar("aviso", "No pude preparar la escucha: " + e.getMessage());
                stopSelf();
            });
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        seguir = false;
        activa = false;
        if (hilo != null) hilo.interrupt();
        if (voz != null) voz.shutdown();
        if (modelo != null) modelo.close();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ el bucle de escucha
    private void bucle() {
        int minimo = AudioRecord.getMinBufferSize(TASA, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord mic;
        try {
            mic = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, TASA, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(minimo, TASA));
            mic.startRecording();
        } catch (SecurityException e) {
            avisar("aviso", "Falta el permiso del micrófono.");
            stopSelf();
            return;
        }
        short[] trozo = new short[1600];  // 0,1 segundos
        try (Recognizer despertador = new Recognizer(modelo, TASA, "[\"jarvis\", \"[unk]\"]")) {
            despertador.setWords(true);
            while (seguir) {
                int n = mic.read(trozo, 0, trozo.length);
                if (n <= 0) continue;
                if (pausada) { despertador.reset(); continue; }
                if (despertador.acceptWaveForm(trozo, n) && oyoJarvis(despertador.getResult())) {
                    despertador.reset();
                    atenderOrden(mic, trozo);
                    despertador.reset();
                }
            }
        } catch (Exception e) {
            avisar("aviso", "La escucha se detuvo: " + e.getMessage());
        } finally {
            mic.stop();
            mic.release();
        }
    }

    /** ¿El resultado trae «jarvis» con suficiente seguridad? */
    private boolean oyoJarvis(String json) {
        try {
            JSONArray palabras = new JSONObject(json).optJSONArray("result");
            if (palabras == null) return false;
            int sensibilidad = Llamadas.preferencias(this).getInt(SENSIBILIDAD, 2);
            double minimo = sensibilidad == 1 ? 0.95 : sensibilidad == 3 ? 0.7 : 0.85;
            for (int i = 0; i < palabras.length(); i++) {
                JSONObject p = palabras.getJSONObject(i);
                if ("jarvis".equals(p.optString("word")) && p.optDouble("conf", 0) >= minimo) return true;
            }
        } catch (Exception ignorado) { }
        return false;
    }

    private void atenderOrden(AudioRecord mic, short[] trozo) {
        pitido();
        mostrarNotificacion("Te escucho…");
        avisar("estado", "escuchando");
        byte[] orden = grabarOrden(mic, trozo, 1.6, 8);
        if (orden == null) {  // solo dijo «Jarvis»
            decirYEsperar("Aquí estoy, señor.");
            orden = grabarOrden(mic, trozo, 5, 8);
        }
        if (orden == null) {
            mostrarNotificacion("Di «Jarvis» para hablarme");
            avisar("estado", "esperando");
            return;
        }
        mostrarNotificacion("Pensando…");
        avisar("estado", "pensando");
        try {
            JSONObject r = preguntar(wav(orden));
            if (r.optString("oido").length() > 0) avisar("tu", "🎤 " + r.optString("oido"));
            JSONObject accion = r.optJSONObject("telefono");
            String texto;
            if (accion != null) {
                texto = Acciones.ejecutar(this, accion);
                avisar("jarvis", texto);
                decirYEsperar(texto);
            } else {
                texto = r.optString("respuesta", r.optString("error", "No te entendí."));
                avisar("jarvis", texto);
                if (!reproducir(r.optJSONArray("audios"))) decirYEsperar(texto);
            }
        } catch (Exception e) {
            String texto = "No pude hablar con Jarvis en el PC. ¿Está abierto y NetBird conectado?";
            avisar("jarvis", texto);
            decirYEsperar(texto);
        }
        mostrarNotificacion("Di «Jarvis» para hablarme");
        avisar("estado", "esperando");
    }

    /** Graba hasta que te callas. Devuelve null si no hablaste en «espera» segundos. */
    private byte[] grabarOrden(AudioRecord mic, short[] trozo, double espera, double maximo) {
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        ByteBuffer bytes = ByteBuffer.allocate(trozo.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        double tiempo = 0, silencio = 0, ruido = 300;
        boolean hablo = false;
        while (seguir && tiempo < maximo) {
            int n = mic.read(trozo, 0, trozo.length);
            if (n <= 0) continue;
            double suma = 0;
            for (int i = 0; i < n; i++) suma += (double) trozo[i] * trozo[i];
            double rms = Math.sqrt(suma / n);
            if (tiempo < 0.3) ruido = Math.max(ruido, rms * 1.5);  // el ruido de fondo de los primeros instantes
            bytes.clear();
            for (int i = 0; i < n; i++) bytes.putShort(trozo[i]);
            pcm.write(bytes.array(), 0, n * 2);
            tiempo += (double) n / TASA;
            if (rms > ruido * 2 && rms > 600) { hablo = true; silencio = 0; }
            else if (hablo) { silencio += (double) n / TASA; if (silencio > 1.2) break; }
            if (!hablo && tiempo > espera) return null;
        }
        return hablo ? pcm.toByteArray() : null;
    }

    private static byte[] wav(byte[] pcm) {
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes()).put("fmt ".getBytes())
                .putInt(16).putShort((short) 1).putShort((short) 1).putInt(TASA).putInt(TASA * 2)
                .putShort((short) 2).putShort((short) 16).put("data".getBytes()).putInt(pcm.length).put(pcm);
        return b.array();
    }

    // ------------------------------------------------------------------ hablar con el PC
    private JSONObject preguntar(byte[] audio) throws Exception {
        SharedPreferences pref = Llamadas.preferencias(this);
        String base = pref.getString(SERVIDOR, "https://100.124.180.1:8766").trim().replaceAll("/+$", "");
        if (!base.startsWith("https://")) throw new IllegalArgumentException("dirección no válida");
        HttpURLConnection c = (HttpURLConnection) new URL(base + "/oir").openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(180000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "audio/wav");
        c.setRequestProperty("X-Jarvis", "1");
        c.setRequestProperty("X-Jarvis-Clave", pref.getString(CLAVE, ""));
        c.setRequestProperty("X-Jarvis-Voz", "1");
        c.setRequestProperty("X-Jarvis-Telefono", "1");
        c.setFixedLengthStreamingMode(audio.length);
        try (OutputStream salida = c.getOutputStream()) { salida.write(audio); }
        int codigo = c.getResponseCode();
        InputStream entrada = codigo >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream datos = new ByteArrayOutputStream();
        byte[] t = new byte[16384];
        int n;
        while (entrada != null && (n = entrada.read(t)) > 0) datos.write(t, 0, n);
        c.disconnect();
        if (codigo == 401) return new JSONObject().put("respuesta", "La clave no es correcta; revísala en mis ajustes.");
        return new JSONObject(datos.toString("UTF-8"));
    }

    /** Reproduce la voz de Jarvis que mandó el PC. Devuelve false si no había audio. */
    private boolean reproducir(JSONArray audios) {
        if (audios == null || audios.length() == 0) return false;
        for (int i = 0; i < audios.length(); i++) {
            JSONObject a = audios.optJSONObject(i);
            if (a == null) continue;
            try {
                String tipo = a.optString("tipo", "audio/wav");
                File archivo = new File(getCacheDir(), "voz_" + i + (tipo.contains("mpeg") ? ".mp3" : ".wav"));
                try (FileOutputStream f = new FileOutputStream(archivo)) {
                    f.write(Base64.decode(a.optString("datos"), Base64.DEFAULT));
                }
                CountDownLatch fin = new CountDownLatch(1);
                MediaPlayer reproductor = new MediaPlayer();
                reproductor.setDataSource(archivo.getPath());
                reproductor.setOnCompletionListener(m -> fin.countDown());
                reproductor.setOnErrorListener((m, x, y) -> { fin.countDown(); return true; });
                reproductor.prepare();
                reproductor.start();
                fin.await(60, TimeUnit.SECONDS);
                reproductor.release();
            } catch (Exception ignorado) { }
        }
        return true;
    }

    private void decirYEsperar(String texto) {
        if (!vozLista || texto == null || texto.isEmpty()) return;
        CountDownLatch fin = new CountDownLatch(1);
        voz.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { fin.countDown(); }
            @Override public void onError(String id) { fin.countDown(); }
        });
        voz.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "escucha");
        try { fin.await(30, TimeUnit.SECONDS); } catch (InterruptedException ignorado) { }
    }

    private void pitido() {
        try {
            ToneGenerator tono = new ToneGenerator(AudioManager.STREAM_MUSIC, 60);
            tono.startTone(ToneGenerator.TONE_PROP_BEEP, 120);
            Thread.sleep(150);
            tono.release();
        } catch (Exception ignorado) { }
    }

    private void avisar(String quien, String texto) {
        Oyente o = oyente;
        if (o != null) o.evento(quien, texto);
    }

    // ------------------------------------------------------------------ notificación fija (la exige Android)
    private void mostrarNotificacion(String texto) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CANAL) == null) {
            NotificationChannel canal = new NotificationChannel(CANAL, "Escucha de Jarvis", NotificationManager.IMPORTANCE_LOW);
            canal.setShowBadge(false);
            nm.createNotificationChannel(canal);
        }
        int inmutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent abrir = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class), inmutable);
        PendingIntent detener = PendingIntent.getService(this, 2,
                new Intent(this, Escucha.class).setAction("detener"), inmutable);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CANAL) : new Notification.Builder(this);
        Notification n = b.setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Jarvis")
                .setContentText(texto)
                .setOngoing(true)
                .setContentIntent(abrir)
                .addAction(new Notification.Action.Builder(null, "Dejar de escuchar", detener).build())
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(5, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(5, n);
        }
    }
}
