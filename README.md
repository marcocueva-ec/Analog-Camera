# AnalogPro Camera v2.0 (Cine HUD + Interactive Solar VFX + Multi-Lens + 4K Video + Persistent Darkroom)

Aplicación de cámara profesional y analógica para Android basada en `Camera2 API`, con interfaz inspirada en monitores de cine (Horizontal y Vertical) sin deformación de imagen (*Zero Squeeze*), previsualización en tiempo real de películas/LUTs y efectos ópticos que reaccionan dinámicamente al sol y a las altas luces de la escena.

## Novedades de la Versión 2.0
1. **Corrección Total de Rotación y Relación de Aspecto (Zero Squeeze)**:
   - Matriz de transformación geométrica (`configureTextureTransform`) que adapta el visor tanto en vertical como en horizontal sin aplastar ni deformar la imagen, soportando **`16:9`**, **`4:3`** y **`OPEN GATE`** (sensor completo).
2. **Interfaz Cine HUD (Vertical y Horizontal)**:
   - Esquinas de encuadre, retícula central interactiva `[ + ]`, indicador de batería en tiempo real, selector rápido `AUTO/PRO`, relación de aspecto, telemetría (`4K`, `30 fps`, `ISO`) y dock translúcido con **`FILMS`**, **`VFX`**, **`PRO`** y **`LENS`**.
3. **Menú `FILMS` con Imagen Representativa por Película**:
   - Cada película (**Fujifilm Classic Chrome, Velvia 50, Neopan Acros, Kodak Portra 400, Gold 200, Ektar 100, Fuji QuickSnap, Kodak FunSaver**) genera su propia miniatura visual representativa y aplica la colorimetría en tiempo real sobre el visor.
4. **Menú `VFX` con Light Leaks y Flares Anamórficos Interactivos**:
   - Analizador lumínico en tiempo real que detecta la posición del sol y los puntos de altas luces en la cámara:
     - **Light Leaks Solares**: Se desplazan e interactúan según el ángulo de la fuente de luz principal.
     - **Light Leaks / Flares Anamórficos**: Se enganchan directamente sobre los puntos de altas luces (farolas, reflejos, sol) tanto en el visor en vivo como en la foto capturada.
     - **Desenfoque Cercano (Bokeh)**: Desenfoque progresivo alrededor del objeto enfocado, además de Grano y Marco Polaroid.
5. **Menú `PRO` y `LENS`**:
   - Control de ISO, velocidad de obturación, compensación EV, enfoque manual macro, formato (`JPG`, `PNG`, `RAW .DNG`), **control de intensidad del Flash**, detección automática de baja luz y selector **`LENS`** para cambiar entre todas las cámaras físicas del teléfono (Ultra Wide, Principal, Teleobjetivo, Frontal).
6. **Grabación de Video 4K y Gestos en Pantalla**:
   - Botón de grabación de video junto al disparador principal en la máxima calidad del sensor (`4K` / `FHD` a 45 Mbps), zoom con dos dedos (*Pinch-to-Zoom*) y enfoque táctil (*Tap-to-Focus*) en cualquier zona de la pantalla.
7. **Galería Persistente y Modo Editor (Darkroom)**:
   - Todas las fotos tomadas en `DCIM/AnalogPro` se consultan desde `MediaStore` al abrir la app para que **nunca desaparezcan al cerrar la aplicación**, mostrando siempre la miniatura en la esquina e incluyendo zoom táctil e importación de cualquier foto antigua de la galería para editarla con todos los Films y VFX.
