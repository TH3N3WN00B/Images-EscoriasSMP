# Imagenes-EscoriasSMP

Fork mejorado de **Custom Images** que renderiza imágenes en los mapas de Minecraft.
Coloca cualquier imagen (archivo local o URL) sobre una pared y se muestra como píxeles
flotantes con marcos de ítem invisibles. Soporta Minecraft **1.8.8 → 1.21.11** y la rama **26.2**.

Este proyecto se distribuye bajo la licencia **MIT**.

---

## Cómo funciona

1. **Crea** la imagen con `/image create` (desde un archivo del servidor o una URL, con
   opción de redimensionar por porcentaje) o con un clic derecho.
2. **Colócala** haciendo clic derecho sobre un bloque: la imagen se renderiza en mapas
   colocados en marcos de ítem proyectados sobre la pared. Clic izquierdo cancela.
3. **Renderizado en vivo**: cada jugador ve las imágenes dentro de su `show-distance`
   (por defecto 64 bloques) y se ocultan al superar el `hide-distance` (128). Los píxeles
   se convierten a la paleta de color de los mapas con una tabla precomputada de cuantización
   5-bit, idéntica al renderizado nativo del juego.
4. **Persistencia**: las imágenes se guardan en una base de datos SQLite, MySQL o en un
   archivo, con compresión lossless zstd. Se pueden migrar entre almacenes con
   `/image transfer`.

---

## Características

- Imágenes en marcos de ítem **invisibles** (solo 1.16+).
- Soporte de formatos **PNG, WebP, JPEG, JPEG XL, WebM** y cualquier formato que Java
  ImageIO o el binario estático de **ffmpeg** puedan decodificar.
- **Recompresión lossless** del origen (JPEG XL > WebP > PNG) con caché por contenido, y
  decodificación con aceleración por hardware cuando el dispositivo está montado.
- Descarga automática y caché del binario estático de ffmpeg (BtbN FFmpeg-Builds) por
  plataforma.
- Actualización automática mediante comprobación de lanzamientos de GitHub.
- Protección contra creación desde URL a direcciones locales (SSRF).
- Restricción opcional por creador (`creator-restricted`).
- Importación automática de datos de la versión legacy (1.0.x-SNAPSHOT).

---

## Instalación

1. Descarga el JAR de **tu versión de Minecraft** desde la página de releases:
   `images-escoriassmp-<version-minecraft>-2.7.1.jar`.
2. Colócalo en la carpeta `plugins` de tu servidor.
3. Reinicia el servidor. La primera vez generará la configuración en
   `plugins/Imagenes-EscoriasSMP/config.yml`.
4. (Opcional) Coloca imágenes en `plugins/Imagenes-EscoriasSMP/images/` para crearlas
   desde archivo y configura la base de datos deseada.

---

## Comandos

Todos los comandos cuelgan de `/image` (aliases: `/images`, `/img`, `/customimage`).

| Comando | Función |
|---|---|
| `/image` | Muestra la lista de subcomandos disponibles. |
| `/image create <nombre \| URL> [porcentaje]` | Comienza a crear una imagen desde un archivo de la carpeta `images/` o una URL, con escala opcional (más de 1%). Clic derecho coloca, clic izquierdo cancela. Aliases: `new`, `add`, `load`. |
| `/image delete` | Elimina una imagen existente haciendo clic sobre ella. Aliases: `del`, `remove`, `unload`. |
| `/image delete near <rango>` | Elimina todas las imágenes dentro del rango dado. Alias: `delete n`. |
| `/image list` | Muestra las opciones de imágenes disponibles. Alias: `options`. |
| `/image import` | Importa y destruye todas las imágenes del formato legacy y las recrea en el formato actual. Alias: `legacyImport`. |
| `/image transfer <MySQL \| SQLite \| File>` | Transfiere todos los datos al almacenamiento indicado en la configuración y reinicia el servidor. Alias: `datatransfer`. |

> `size` y `resize` están implementados pero **deshabilitados** en el comando raíz.

---

## Permisos

| Permiso | Comando |
|---|---|
| `images.command.manage` | Acceso a `/image` (padre). |
| `images.command.create` | `/image create` |
| `images.command.create.url` | Crear imágenes desde URL. |
| `images.command.delete` | `/image delete` |
| `images.command.delete.near` | `/image delete near` |
| `images.command.list` | `/image list` |
| `images.command.import` | `/image import` |
| `images.command.transfer` | `/image transfer` |
| `images.restricted.bypass` | Ignora la restricción de creador. |

---

## Configuración

| Opción | Por defecto | Descripción |
|---|---|---|
| `config-version` | `2` | Versión del fichero; se migra solo (con copia en `config.yml.bak`). |
| `invisible-frames` | `true` | Marcos de ítem invisibles tras la imagen (1.16+). |
| `show-distance` / `hide-distance` | `64` / `128` | Rango para mostrar/ocultar secciones de imagen. |
| `database.type` | `SQLITE` | `MYSQL`, `SQLITE` o `FILE`. |
| `permissions.creator-restricted` | `false` | Solo el creador puede modificar la imagen. |
| `update-check` / `update-interval-minutes` | `true` / `360` | Comprobación de actualizaciones. |
| `ffmpeg.*` | habilitado | Decodificación con ffmpeg, binario automático (BtbN), recompresión lossless y formato preferido (`AUTO`, `PNG`, `WEBP`, `JXL`). |
| `image-storage.compression` | `true` | Compresión zstd de imágenes almacenadas (solo datos nuevos). |

---

## Cambios respecto al plugin original

El plugin original es **Custom Images** de **Andavin**. Esta bifurcación añade:

- **Decodificación ampliada** (2.6.3): soporte de **WebP, JPEG XL y WebM** mediante un binario
  estático de ffmpeg descargado automáticamente y cachéado por plataforma, con fallback para
  cualquier formato que Java ImageIO no pueda leer.
- **Almacenamiento con zstd** (2.6.3): compresión lossless de las imágenes guardadas en SQLite,
  MySQL o archivo, manteniendo compatibilidad con datos antiguos.
- **Migración automática de configuración** (2.6.3): el fichero `config.yml` se migra solo entre
  versiones sin perder los valores modificados.
- **Recompresión lossless y caché por contenido** (2.7.0): al crear imágenes, el origen se
  re-comprime sin pérdida (JPEG XL > WebP > PNG) y se cachea por hash de contenido; reutilizar
  el mismo archivo o URL ya no cuesta nada. Se intenta aceleración por hardware solo cuando el
  dispositivo está montado.
- **Correcciones de estabilidad y rendimiento** (2.7.1): corrección de fugas de memoria
  asociadas a UUID de jugadores (listas de movimiento, tareas de creación y oyentes se limpian
  al salir y en `/reload`) y conversión de píxeles a la paleta del mapa mediante tabla
  precomputada de cuantización 5-bit (~100× más rápida por píxel, sin asignaciones por píxel).
- **CI y versiones modernas**: build con JDK 25 y GitHub Actions actualizado, generando un JAR
  por versión de Minecraft (1.8.8 → 26.2).

---

## Compilación

```bash
mvn clean package
```

Compila todos los módulos (cada uno con su JAR por versión de Minecraft) y genera el plugin
principal en `Images-Core/target/`. Requiere Maven 3.9+ y un JDK 25.

---

## Créditos y licencia

Este proyecto es una **bifurcación de [Custom Images](https://www.spigotmc.org/resources/custom-images.53036/)**
creado por **Andavin** (código original bajo licencia MIT, Copyright (c) 2020 Mark).

Gracias a:
- **Andavin** por el desarrollo original del plugin.
- **BtbN / BtbN FFmpeg-Builds** por los binarios estáticos de ffmpeg con soporte JPEG XL.

Licencia: **MIT** — ver [LICENSE](LICENSE) para más detalles.