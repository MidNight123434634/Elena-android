# Elena para Android

App para hablar con Elena desde el teléfono, por NetBird.

- La pantalla es la misma de la página del teléfono (esfera, micrófono, chat), guardada dentro de la app.
- Se conecta a Elena por HTTPS y confía solo en el certificado de tu PC (`app/src/main/res/raw/jarvis_ca.crt`,
  la parte pública de la autoridad de la ferretería). No hay que instalar certificados en el teléfono.
- La dirección (por defecto `https://100.124.180.1:8766`, la de NetBird) y la clave se configuran en ⚙ dentro de la app.

## Cómo se arma
GitHub la arma sola con cada cambio (`.github/workflows/apk.yml`). El resultado queda en la pestaña
**Actions**, como archivo `Elena-apk`.

## Instalar en el teléfono
1. Descarga `Jarvis.apk` en el teléfono. El nombre del archivo se conserva para que la actualización automática siga siendo compatible.
2. Ábrela y, si Android lo pide, permite «Instalar apps desconocidas» para el navegador o el administrador de archivos.
3. Abre Elena, toca ⚙ y escribe la clave (está en `clave_movil.txt` en el PC).

El identificador Android `cl.jarvis.app`, las claves locales `jarvis_*` y las rutas/cabeceras `X-Jarvis` se conservan para que las instalaciones existentes mantengan sus datos y sigan comunicándose con el servidor del PC.
