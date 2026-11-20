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
 * Escucha siempre (con la pantalla apagada también) y se despierta al oír «Elena».
 *
 * La palabra «Elena» se reconoce DENTRO del teléfono, sin internet (Vosk). Solo después de oírla se graba la
 * orden y se manda a Elena en el PC (por NetBird), igual que con el botón del micrófono.
 * Android exige mostrar una notificación fija mientras el micrófono está activo.
 */
public class Escucha extends Service {

    static final String ESCUCHAR_SIEMPRE = "escuchar_siempre";
    static final String SENSIBILIDAD = "escucha_sensibilidad";   // 1 (estricta) a 3 (sensible)
    static final String SERVIDOR = "conexion_servidor";
    static final String CLAVE = "conexion_clave";
    static final String VOZ_RAPIDA = "voz_rapida";               // responder con la voz del teléfono (más rápido)
    private static final String CANAL = "elena_escucha";
    private static final int TASA = 16000;

    /** La pantalla de la app se entera de lo que pasa (para mostrarlo en el chat). */
    interface Oyente { void evento(String quien, String texto); }
    static volatile Oyente oyente;
    private static volatile boolean pausada = false;
    private static volatile boolean activa = false;
    private static volatile boolean microfonoLibre = true;
    private Microfono ruta;
    private int revisionRuta;

    private Thread hilo;
    private volatile boolean seguir = true;
    private TextToSpeech voz;
    private volatile boolean vozLista = false;
    private Model modelo;

    static boolean activa() { return activa; }

    /** Mientras se usa el botón del micrófono de la app, la escucha se pausa (el micrófono es uno solo). */
    static void pausar(boolean si) { pausada = si; }
    static boolean esperarMicrofonoLibre() {
        long hasta = android.os.SystemClock.elapsedRealtime() + 2500;
        while (activa && !microfonoLibre && android.os.SystemClock.elapsedRealtime() < hasta)
            android.os.SystemClock.sleep(25);
        return !activa || microfonoLibre;
    }

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
        mostrarNotificacion("Di «Elena» para hablarme");
        if (hilo == null) {
            activa = true;
            voz = new TextToSpeech(this, estado -> {
                vozLista = estado == TextToSpeech.SUCCESS;
                if (vozLista) { voz.setLanguage(new Locale("es", "ES")); voz.setSpeechRate(1.1f); }
            });
            avisar("aviso", "Preparando la escucha…");
            StorageService.unpack(this, "model-es", "modelo_elena", m -> {
                if (!seguir) { m.close(); return; }
                modelo = m;
                hilo = new Thread(this::bucle, "escucha-elena");
                hilo.start();
                avisar("aviso", "Te escucho: di «Elena» cuando me necesites.");
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
        super.onDestroy();
    }

    // ------------------------------------------------------------------ el bucle de escucha
    private static final int TROZO = 1600;            // 0,1 segundos
    private static final int MAXIMO_RECIENTE = TASA * 15;
    private volatile double ruido = 300;
    private long ultimaEntrega = 0;              // ruido de fondo, medido mientras espera «Elena»

    /** Lo último que se oyó (desde que empezó la frase actual), para no perder lo dicho junto a «Elena». */
    private final short[] reciente = new short[MAXIMO_RECIENTE];
    private int largoReciente = 0;
    private long inicioReciente = 0;                  // en muestras desde que se creó el reconocedor

    private AudioRecord abrirMicrofono() {
        if (!seguir || pausada) return null;
        ruta = Microfono.obtener(this);
        ruta.adquirir("servicio");
        ruta.esperarRuta();
        revisionRuta = ruta.revision();
        ruido = 80;
        int minimo = AudioRecord.getMinBufferSize(TASA, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord mic = null;
        try {
            mic = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, TASA, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(minimo, TASA * 4));  // 2 segundos de margen
            if (mic.getState() != AudioRecord.STATE_INITIALIZED) { cerrar(mic); return null; }
            ruta.preferir(mic);
            mic.startRecording();
            microfonoLibre = false;
            android.media.AudioDeviceInfo entrada = mic.getRoutedDevice();
            avisar("aviso", "Micrófono: " + (entrada == null ? "selección automática de Android" : entrada.getProductName()));
            return mic;
        } catch (SecurityException e) {
            cerrar(mic);
            avisar("aviso", "Falta el permiso del micrófono.");
            return null;
        } catch (Exception e) {
            cerrar(mic);
            return null;
        }
    }

    private void cerrar(AudioRecord mic) {
        if (mic != null) {
            try { mic.stop(); } catch (Exception ignorado) { }
            mic.release();
        }
        if (ruta != null) ruta.soltar("servicio");
        microfonoLibre = true;
    }

    private Recognizer nuevoDespertador() throws java.io.IOException {
        Recognizer r = new Recognizer(modelo, TASA, "[\"elena\", \"[unk]\"]");
        r.setWords(true);
        largoReciente = 0;
        inicioReciente = 0;
        return r;
    }

    private void bucle() {
        AudioRecord mic = abrirMicrofono();
        short[] trozo = new short[TROZO];
        Recognizer despertador = null;
        boolean enLlamada = false, reiniciar = false;
        long oidas = 0;  // muestras que ya recibió el reconocedor
        try {
            despertador = nuevoDespertador();
            while (seguir) {
                if (pausada) {
                    if (mic != null) { cerrar(mic); mic = null; reiniciar = true; }
                    dormir(50);
                    continue;
                }
                if (mic != null && ruta.revision() != revisionRuta) {
                    cerrar(mic); mic = null; reiniciar = true;
                    avisar("aviso", "Cambió el dispositivo de audio. Ajustando el micrófono…");
                }
                // en llamada: suelta el micrófono y no habla hasta que corte
                if (Acciones.enLlamada(this)) {
                    if (!enLlamada) { enLlamada = true; cerrar(mic); mic = null; mostrarNotificacion("En llamada: no escucho ni hablo"); }
                    dormir(1000);
                    continue;
                }
                if (enLlamada || mic == null) {
                    enLlamada = false;
                    mic = abrirMicrofono();
                    if (mic == null) { dormir(2000); continue; }
                    reiniciar = true;
                    mostrarNotificacion("Di «Elena» para hablarme");
                }
                int n = mic.read(trozo, 0, trozo.length);
                if (n <= 0) { cerrar(mic); mic = null; dormir(300); continue; }
                if (pausada) { reiniciar = true; continue; }   // la pantalla está usando el micrófono
                if (reiniciar) {
                    despertador.close();
                    despertador = nuevoDespertador();
                    oidas = 0;
                    reiniciar = false;
                }
                if (System.currentTimeMillis() - ultimaEntrega > 90_000) {   // cada minuto y medio: ¿volvió Elena?
                    ultimaEntrega = System.currentTimeMillis();
                    if (Memoria.cantidad(this) > 0) new Thread(() -> Memoria.entregar(this)).start();
                }
                medirRuido(rms(trozo, n));
                guardarReciente(trozo, n);
                oidas += n;
                if (despertador.acceptWaveForm(trozo, n)) {
                    double fin = finDeElena(despertador.getResult());
                    if (fin >= 0) {
                        // lo que dijiste después de «Elena» (un poquito antes, para no cortar la primera sílaba)
                        short[] resto = desdeReciente((long) ((fin - 0.15) * TASA));
                        atenderOrden(mic, trozo, resto);
                        // se descarta lo que se juntó mientras Elena pensaba y hablaba (si no, se oiría a sí mismo)
                        cerrar(mic);
                        mic = abrirMicrofono();
                        reiniciar = true;
                    } else {
                        inicioReciente = oidas;  // terminó una frase sin «Elena»: se olvida
                        largoReciente = 0;
                    }
                }
            }
        } catch (Exception e) {
            avisar("aviso", "La escucha se detuvo: " + e.getMessage());
        } finally {
            if (despertador != null) despertador.close();
            cerrar(mic);
            if (modelo != null) modelo.close();
            activa = false;
        }
    }

    private static void dormir(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignorado) { }
    }

    private static double rms(short[] datos, int n) {
        double suma = 0;
        for (int i = 0; i < n; i++) suma += (double) datos[i] * datos[i];
        return n == 0 ? 0 : Math.sqrt(suma / n);
    }

    private void medirRuido(double rms) {
        if (rms < ruido * 1.5) ruido = ruido * 0.95 + rms * 0.05;   // silencio o ruido de fondo
        else ruido = ruido * 1.003;                                  // sube despacio si hay ruido constante
        ruido = Math.max(15, Math.min(4000, ruido));
    }

    private double umbralVoz() { return Math.max(ruido * 2.5, 60); }

    private void guardarReciente(short[] datos, int n) {
        if (largoReciente + n > MAXIMO_RECIENTE) {   // frase demasiado larga: se descarta lo más viejo
            int sobra = largoReciente + n - MAXIMO_RECIENTE;
            System.arraycopy(reciente, sobra, reciente, 0, largoReciente - sobra);
            largoReciente -= sobra;
            inicioReciente += sobra;
        }
        System.arraycopy(datos, 0, reciente, largoReciente, n);
        largoReciente += n;
    }

    private short[] desdeReciente(long muestra) {
        int desde = (int) Math.max(0, Math.min(largoReciente, muestra - inicioReciente));
        short[] resto = new short[largoReciente - desde];
        System.arraycopy(reciente, desde, resto, 0, resto.length);
        return resto;
    }

    /** Si el resultado trae «elena» con suficiente seguridad, devuelve el segundo en que terminó; si no, -1. */
    private double finDeElena(String json) {
        try {
            JSONArray palabras = new JSONObject(json).optJSONArray("result");
            if (palabras == null) return -1;
            int sensibilidad = Llamadas.preferencias(this).getInt(SENSIBILIDAD, 2);
            double minimo = sensibilidad == 1 ? 0.95 : sensibilidad == 3 ? 0.7 : 0.85;
            double fin = -1;
            for (int i = 0; i < palabras.length(); i++) {
                JSONObject p = palabras.getJSONObject(i);
                if ("elena".equals(p.optString("word")) && p.optDouble("conf", 0) >= minimo) fin = p.optDouble("end", 0);
            }
            return fin;
        } catch (Exception ignorado) {
            return -1;
        }
    }

    /** Segundos con voz dentro de un trozo de audio. */
    private double segundosConVoz(short[] audio) {
        double umbral = umbralVoz(), segundos = 0;
        for (int i = 0; i + TROZO <= audio.length; i += TROZO) {
            double suma = 0;
            for (int j = i; j < i + TROZO; j++) suma += (double) audio[j] * audio[j];
            if (Math.sqrt(suma / TROZO) > umbral) segundos += (double) TROZO / TASA;
        }
        return segundos;
    }

    private void atenderOrden(AudioRecord mic, short[] trozo, short[] resto) {
        byte[] orden;
        if (segundosConVoz(resto) >= 0.3) {
            // «Elena, abre WhatsApp» dicho de corrido: la orden ya está grabada; se sigue un momento por si continúas
            avisar("estado", "escuchando");
            orden = grabarOrden(mic, trozo, aBytes(resto), 0.6, 12);
        } else {
            // solo dijo «Elena»: pitido y escucha al tiro (sin hablar encima de ti)
            pitido(ToneGenerator.TONE_PROP_BEEP);
            mostrarNotificacion("Te escucho…");
            avisar("estado", "escuchando");
            orden = grabarOrden(mic, trozo, null, 6, 12);
        }
        if (orden == null) {
            pitido(ToneGenerator.TONE_PROP_NACK);
            mostrarNotificacion("Di «Elena» para hablarme");
            avisar("estado", "esperando");
            return;
        }
        mostrarNotificacion("Pensando…");
        avisar("estado", "pensando");
        boolean rapida = Llamadas.preferencias(this).getBoolean(VOZ_RAPIDA, true);
        try {
            JSONObject r = preguntar(wav(orden), !rapida);
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
                if (rapida || !reproducir(r.optJSONArray("audios"))) decirYEsperar(texto);
            }
            new Thread(() -> Memoria.entregar(this)).start();   // hay conexión: entrega lo que quedó guardado
        } catch (java.io.IOException e) {
            if (e instanceof java.net.SocketTimeoutException && String.valueOf(e.getMessage()).toLowerCase().contains("read")) {
                // el PC sí lo recibió pero se demoró demasiado: no se guarda (si no, lo haría dos veces)
                String texto = "Elena se está demorando mucho en responder.";
                avisar("jarvis", texto);
                decirYEsperar(texto);
                mostrarNotificacion("Di «Elena» para hablarme");
                avisar("estado", "esperando");
                return;
            }
            // sin conexión con el PC: queda guardado en el teléfono y se entrega cuando vuelva
            String texto = Memoria.agregarAudio(this, wav(orden));
            avisar("aviso", texto);
            decirYEsperar("Elena no está conectada. Lo guardé y se lo paso cuando vuelva.");
        } catch (Exception e) {
            String texto = "No pude hablar con Elena en el PC. ¿Está abierto y NetBird conectado?";
            avisar("jarvis", texto);
            decirYEsperar(texto);
        }
        mostrarNotificacion("Di «Elena» para hablarme");
        avisar("estado", "esperando");
    }

    private static byte[] aBytes(short[] audio) {
        ByteBuffer b = ByteBuffer.allocate(audio.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short m : audio) b.putShort(m);
        return b.array();
    }

    /**
     * Graba hasta que te callas (1 segundo de silencio). Si 'antes' trae audio, ya hablaste: se sigue grabando
     * solo si retomas antes de 'espera' segundos. Si no, devuelve null si no hablaste en 'espera' segundos.
     */
    private byte[] grabarOrden(AudioRecord mic, short[] trozo, byte[] antes, double espera, double maximo) {
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        if (antes != null) pcm.write(antes, 0, antes.length);
        ByteBuffer bytes = ByteBuffer.allocate(trozo.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        double tiempo = 0, silencio = 0, umbral = umbralVoz();
        boolean hablo = antes != null;
        double finSilencio = antes != null ? espera : 1.0;
        while (seguir && tiempo < maximo && mic != null) {
            if (pausada || Acciones.enLlamada(this) || ruta.revision() != revisionRuta) return null;
            int n = mic.read(trozo, 0, trozo.length);
            if (n <= 0) return null;
            double nivel = rms(trozo, n);
            bytes.clear();
            for (int i = 0; i < n; i++) bytes.putShort(trozo[i]);
            pcm.write(bytes.array(), 0, n * 2);
            tiempo += (double) n / TASA;
            boolean voz = nivel > umbral && tiempo > 0.2;   // lo primero puede ser el pitido
            if (voz) { hablo = true; silencio = 0; finSilencio = 1.0; }
            else if (hablo) { silencio += (double) n / TASA; if (silencio > finSilencio) break; }
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
    private JSONObject preguntar(byte[] audio, boolean vozDelPc) throws Exception {
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
        c.setRequestProperty("X-Jarvis-Voz", vozDelPc ? "1" : "0");
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

    /** Reproduce la voz de Elena que mandó el PC. Devuelve false si no había audio. */
    private boolean reproducir(JSONArray audios) {
        if (audios == null || audios.length() == 0) return false;
        for (int i = 0; i < audios.length(); i++) {
            if (Acciones.enLlamada(this)) return true;  // empezó una llamada: se calla
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
        if (!vozLista || texto == null || texto.isEmpty() || Acciones.enLlamada(this)) return;
        CountDownLatch fin = new CountDownLatch(1);
        voz.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { fin.countDown(); }
            @Override public void onError(String id) { fin.countDown(); }
        });
        voz.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "escucha");
        try { fin.await(30, TimeUnit.SECONDS); } catch (InterruptedException ignorado) { }
    }

    private void pitido(int cual) {
        try {
            ToneGenerator tono = new ToneGenerator(AudioManager.STREAM_MUSIC, 60);
            tono.startTone(cual, 120);
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
            NotificationChannel canal = new NotificationChannel(CANAL, "Escucha de Elena", NotificationManager.IMPORTANCE_LOW);
            canal.setShowBadge(false);
            nm.createNotificationChannel(canal);
        }
        int inmutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent abrir = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class), inmutable);
        PendingIntent detener = PendingIntent.getService(this, 2,
                new Intent(this, Escucha.class).setAction("detener"), inmutable);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CANAL) : new Notification.Builder(this);
        Notification n = b.setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Elena")
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
