package cl.jarvis.app;

import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.HashSet;
import java.util.Set;

/** Ruta compartida por el botón y la escucha permanente, liberada al dejar de grabar. */
final class Microfono {
    private static Microfono instancia;
    static synchronized Microfono obtener(Context c) {
        if (instancia == null) instancia = new Microfono(c.getApplicationContext());
        return instancia;
    }
    private final AudioManager audio;
    private final Context contexto;
    private final Set<String> usuarios = new HashSet<>();
    private volatile int revision;
    private int elegido = -1;
    private boolean sco;
    private volatile boolean scoConectado;
    private final BroadcastReceiver estadoSco = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            scoConectado = i.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) == AudioManager.SCO_AUDIO_STATE_CONNECTED;
        }
    };
    private String firma = "";
    private final AudioDeviceCallback cambios = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] ds) { cambio(); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] ds) { cambio(); }
    };
    private Microfono(Context c) { contexto = c; audio = c.getSystemService(AudioManager.class); }
    int revision() { return revision; }
    private String firmaDispositivos() {
        StringBuilder s = new StringBuilder();
        for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_ALL))
            s.append(d.getId()).append(':').append(d.getType()).append(';');
        return s.toString();
    }
    private synchronized void cambio() {
        String actual = firmaDispositivos();
        if (!usuarios.isEmpty() && !actual.equals(firma)) { firma = actual; revision++; ajustar(); }
    }

    synchronized void adquirir(String usuario) {
        if (!usuarios.add(usuario)) return;
        if (usuarios.size() == 1) {
            firma = firmaDispositivos();
            if (Build.VERSION.SDK_INT < 31)
                contexto.registerReceiver(estadoSco, new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED));
            audio.registerAudioDeviceCallback(cambios, new Handler(Looper.getMainLooper()));
            ajustar();
        }
    }
    private static boolean bluetooth(AudioDeviceInfo d) {
        return d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || (Build.VERSION.SDK_INT >= 31 && d.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET);
    }
    private static int prioridad(AudioDeviceInfo d) {
        if (d.getType() == AudioDeviceInfo.TYPE_WIRED_HEADSET) return 4;
        if (d.getType() == AudioDeviceInfo.TYPE_USB_HEADSET || d.getType() == AudioDeviceInfo.TYPE_USB_DEVICE) return 3;
        if (bluetooth(d)) return 2;
        return d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC ? 1 : 0;
    }
    private void ajustar() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                AudioDeviceInfo mejor = null;
                for (AudioDeviceInfo d : audio.getAvailableCommunicationDevices()) {
                    if (prioridad(d) >= 2 && (mejor == null || prioridad(d) > prioridad(mejor))) mejor = d;
                }
                if (mejor != null && mejor.getId() != elegido) {
                    if (audio.setCommunicationDevice(mejor)) elegido = mejor.getId();
                } else if (mejor == null && elegido != -1) {
                    audio.clearCommunicationDevice(); elegido = -1;
                }
            } else {
                boolean hayBluetooth = false, cable = false;
                for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_ALL)) {
                    hayBluetooth |= bluetooth(d);
                    cable |= prioridad(d) >= 3;
                }
                if (hayBluetooth && !cable && !sco) {
                    audio.startBluetoothSco(); audio.setBluetoothScoOn(true); sco = true;
                } else if ((!hayBluetooth || cable) && sco) {
                    audio.stopBluetoothSco(); audio.setBluetoothScoOn(false); sco = false;
                }
            }
        } catch (SecurityException ignorado) { /* Sin permiso Bluetooth se conserva el micrófono del teléfono. */ }
    }
    void esperarRuta() {
        // La apertura del enlace de voz Bluetooth es asíncrona; no bloquear el hilo de interfaz.
        int destino;
        boolean antiguo;
        synchronized (this) { destino = elegido; antiguo = sco; }
        if (destino == -1 && !antiguo) return;
        long hasta = SystemClock.elapsedRealtime() + 3000;
        while (SystemClock.elapsedRealtime() < hasta) {
            if (Build.VERSION.SDK_INT >= 31) {
                AudioDeviceInfo actual = audio.getCommunicationDevice();
                if (actual != null && actual.getId() == destino) return;
            } else if (scoConectado) return;
            SystemClock.sleep(50);
        }
        // Si el perfil de voz no llega a conectarse, permite seguir con el micrófono del teléfono.
        synchronized (this) {
            if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice();
            else { audio.stopBluetoothSco(); audio.setBluetoothScoOn(false); }
            elegido = -1; sco = false;
        }
    }
    void preferir(AudioRecord grabador) {
        AudioDeviceInfo mejor = null;
        for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (bluetooth(d) && elegido == -1 && !sco) continue;
            if (prioridad(d) > 0 && (mejor == null || prioridad(d) > prioridad(mejor))) mejor = d;
        }
        if (mejor != null) grabador.setPreferredDevice(mejor);
    }
    synchronized void soltar(String usuario) {
        if (!usuarios.remove(usuario) || !usuarios.isEmpty()) return;
        audio.unregisterAudioDeviceCallback(cambios);
        if (Build.VERSION.SDK_INT < 31) contexto.unregisterReceiver(estadoSco);
        try {
            if (Build.VERSION.SDK_INT >= 31 && elegido != -1) audio.clearCommunicationDevice();
            if (Build.VERSION.SDK_INT < 31 && sco) { audio.stopBluetoothSco(); audio.setBluetoothScoOn(false); }
        } catch (SecurityException ignorado) { }
        elegido = -1; sco = false; scoConectado = false;
    }
}
