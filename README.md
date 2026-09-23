# Imagenes-EscoriasSMP

Fork mejorado de **Custom Images** que renderiza imágenes en los mapas de Minecraft.
Coloca cualquier imagen (archivo local o URL) sobre una pared y se muestra como píxeles
flotantes con marcos de ítem invisibles. Soporta Minecraft **1.8.8 → 26.2**.

Este proyecto se distribuye bajo la licencia **MIT**.

---

## Qué ofrece

- **Renderizado en el juego**: las imágenes se proyectan sobre los mapas de Minecraft
  usando marcos de ítem invisibles (1.16+).
- **Creación sencilla**: `/image create` desde un archivo del servidor o una URL, con
  escala opcional. Clic derecho coloca la imagen y clic izquierdo cancela.
- **Renderizado en vivo por jugador**: las secciones se muestran dentro del
  `show-distance` (64 bloques por defecto) y se ocultan al superar el `hide-distance`
  (128), con una conversión nativa a la paleta de color de los mapas.
- **Persistencia**: las imágenes se guardan en **SQLite, MySQL o archivo** con compresión
  lossless zstd, y pueden migrarse entre almacenes con `/image transfer`.
- **Formatos ampliados**: **PNG, WebP, JPEG, JPEG XL y WebM**, decodificados por Java
  ImageIO o por un binario estático de ffmpeg descargado y cacheado automáticamente.
- **Optimización de almacenamiento**: recompresión lossless del origen (JPEG XL > WebP >
  PNG) con caché por contenido y aceleración por hardware cuando el dispositivo lo permite.
- **Seguridad y control**: protector SSRF contra URLs locales y restricción opcional
  de creador (`creator-restricted`).

---

## Versiones compatibles

1.8.8 · 1.9.4 · 1.10.2 · 1.11.2 · 1.12.2 · 1.13.2 · 1.14.4 · 1.15.2
· 1.16.3 · 1.16.5 · 1.17.1 · 1.18.1 · 1.18.2 · 1.19.2 · 1.19.3 · 1.19.4
· 1.20.1 · 1.20.2 · 1.20.4 · 1.20.6 · 1.21.1 · 1.21.3 · 1.21.4 · 1.21.5
· 1.21.8 · 1.21.10 · 1.21.11 · 26.2

En cada release se publica un JAR por versión de Minecraft, por ejemplo
`images-escoriassmp-1.21.8-X.X.X.jar` para 1.21.8.

---

## Cambios respecto al plugin original

Fork de **Custom Images** de **Andavin**. Mejoras principales:

- **Decodificación ampliada**: soporte de **WebP, JPEG XL y WebM** mediante un binario
  estático de ffmpeg (BtbN) descargado automáticamente y cacheado por plataforma, con
  fallback para cualquier formato que Java ImageIO no pueda leer.
- **Almacenamiento optimizado**: compresión lossless **zstd** de las imágenes guardadas,
  con total compatibilidad con los datos de versiones anteriores.
- **Migración automática de configuración** entre versiones, conservando los valores
  modificados por el administrador.
- **Creación más rápida**: recompresión lossless del origen con caché por hash de
  contenido, de forma que reutilizar un archivo o URL no vuelve a costar nada.
- **Rendimiento y estabilidad**: conversión de píxeles a la paleta del mapa mediante
  tabla precomputada de cuantización 5-bit (~100× más rápida, sin asignaciones por
  píxel), corrección de fugas de memoria y una auditoría de seguridad que incluye
  esperas acotadas en los hilos de red, carga de datos tolerante a fallos y límite
  anti-bomba de descompresión.
- **Límite de tamaño** configurable por imagen (`max-sections`).
- **CI**: build automático con GitHub Actions que genera **un JAR por versión de
  Minecraft** (1.8.8 → 26.2) adjuntado a cada release.

---

## Contribuir

¡Las contribuciones son bienvenidas!

1. Haz un *fork* del repositorio y crea una rama con tu cambio:
   `git checkout -b mi-cambio`.
2. Implementa el cambio y comprueba que el proyecto sigue compilando
   (ver [Compilación](#compilación)).
3. Abre un *pull request* describiendo el problema que resuelve.

Para reportar errores o proponer mejoras, abre un *issue* en GitHub.

---

## Compilación

Requisitos: **Maven 3.9+** y **JDK 25**.

```bash
mvn clean package
```

Compila todos los módulos y genera el plugin principal en `Images-Core/target/`
además de los JARs intermedios de cada versión de Minecraft.

---

## Créditos y licencia

Este proyecto es una **bifurcación de [Custom Images](https://www.spigotmc.org/resources/custom-images.53036/)**
creado por **Andavin** (código original bajo licencia MIT, Copyright (c) 2020 Mark).

Gracias a:
- **Andavin** por el desarrollo original del plugin.
- **BtbN / BtbN FFmpeg-Builds** por los binarios estáticos de ffmpeg con soporte JPEG XL.

Licencia: **MIT** — ver [LICENSE](LICENSE) para más detalles.