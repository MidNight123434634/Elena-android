# Elena para Android

App para hablar con Elena desde el teléfono, por NetBird.

- La pantalla es la misma de la página del teléfono (esfera, micrófono, chat), guardada dentro de la app.
- Se conecta a Elena por HTTPS y confía solo en el certificado de tu PC (`app/src/main/res/raw/jarvis_ca.crt`,
  la parte pública de la autoridad de la ferretería). No hay que instalar certificados en el teléfono.
- La dirección (por defecto `https://100.124.180.1:8766`, la de NetBird) y la clave se configuran en ⚙ dentro de la app.

## Cómo se arma
El micrófono se adapta al conectar o desconectar audífonos con micrófono (Bluetooth, cable o USB).
En Android 12 o posterior, permite **Dispositivos cercanos** para usar Bluetooth. Si se deniega,
puedes volver a tocar el botón para usar la entrada disponible sin ese permiso.
La escucha permanente libera la entrada mientras grabas con el botón. Si desconectas los audífonos
durante una grabación manual, se descarta esa frase y se pide repetirla para no enviar audio cortado.

Prueba en un teléfono: iniciar la escucha sin audífonos, conectarlos, decir «Elena», grabar con el botón,
desconectarlos durante una frase y repetir; comprobar también permisos denegados y una llamada entrante.
Prueba automática de la interfaz: `node tests/microfono-web.test.cjs`.

GitHub la arma sola con cada cambio (`.github/workflows/apk.yml`). El resultado queda en la pestaña
**Actions**, como archivo `Elena-apk`.

## Instalar en el teléfono
La app usa por defecto la voz local del PC para las respuestas de conversación. En Ajustes,
activa «Usar la voz rápida del teléfono» para volver al sintetizador de Android.
La voz del PC requiere que Elena esté abierta y accesible por NetBird; no utiliza una API de voz de pago.
Las acciones locales y las notificaciones conservan la voz del teléfono.

1. Descarga `Jarvis.apk` en el teléfono. El nombre del archivo se conserva para que la actualización automática siga siendo compatible.
2. Ábrela y, si Android lo pide, permite «Instalar apps desconocidas» para el navegador o el administrador de archivos.
3. Abre Elena, toca ⚙ y escribe la clave (está en `clave_movil.txt` en el PC).

El identificador Android `cl.jarvis.app`, las claves locales `jarvis_*` y las rutas/cabeceras `X-Jarvis` se conservan para que las instalaciones existentes mantengan sus datos y sigan comunicándose con el servidor del PC.
# Saludo al llamarla

Al decir solo «Elena», responde «Aquí estoy» con un audio local incluido en la app y luego escucha tu orden. No necesita conexión al PC para el saludo. El micrófono deja de grabar durante el saludo para no enviar la voz de Elena como si fuera tu orden. Si dices «Elena, abre WhatsApp» de corrido, atiende la orden directamente.

