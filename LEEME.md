# Jarvis para Android

App para hablar con Jarvis desde el teléfono, por NetBird.

- La pantalla es la misma de la página del teléfono (esfera, micrófono, chat), guardada dentro de la app.
- Se conecta a Jarvis por HTTPS y confía solo en el certificado de tu PC (`app/src/main/res/raw/jarvis_ca.crt`,
  la parte pública de la autoridad de la ferretería). No hay que instalar certificados en el teléfono.
- La dirección (por defecto `https://100.124.180.1:8766`, la de NetBird) y la clave se configuran en ⚙ dentro de la app.

## Cómo se arma
GitHub la arma sola con cada cambio (`.github/workflows/apk.yml`). El resultado queda en la pestaña
**Actions**, como archivo `Jarvis-apk`.

## Instalar en el teléfono
1. Descarga `app-debug.apk` en el teléfono.
2. Ábrela y, si Android lo pide, permite «Instalar apps desconocidas» para el navegador o el administrador de archivos.
3. Abre Jarvis, toca ⚙ y escribe la clave (está en `clave_movil.txt` en el PC).
