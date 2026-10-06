# NEOSICHER v2.0 — Detección de postura del bebé (cámara Android)

Rama: `feature/neosicher-v2` (la versión anterior sigue intacta en `develop`).

## Qué detecta

| Postura | Cómo se decide (heurística) |
|---|---|
| **Boca arriba** | Cara detectada de frente (giro de cabeza < 35°) y hombros anchos |
| **Volteado (de lado)** | Hombros "colapsados" (razón hombros/torso < 0.45) o cabeza girada ≥ 35° |
| **Boca abajo** | Sin cara, torso de frente, y el cuerpo pasó por posición lateral tras perder la cara (se volteó). Si nunca se vio la cara: confianza más baja |
| **Cara tapada** | La cara estaba visible y desaparece de golpe mientras el torso sigue de frente, sin pasar por posición lateral |

Modelos usados (ambos on-device, sin internet): ML Kit Pose Detection y ML Kit Face Detection.

## Mejoras de precisión (segunda iteración)

1. **Búsqueda de rotación.** Los modelos de ML Kit esperan a la persona derecha. Un bebé tumbado visto desde arriba aparece en cualquier orientación. Si no se detecta cuerpo ni cara durante 4 frames, el analizador gira la imagen 90° y vuelve a probar; mantiene el giro mientras el bebé siga apareciendo. Los puntos se devuelven a las coordenadas del visor (`PoseGeometry.rotatedToDisplay`).
2. **Pista pecho/espalda** (`PoseGeometry.chestFacing`): con el vector hombro derecho→izquierdo y el eje caderas→cabeza, el signo del producto cruzado dice si se ve el pecho o la espalda. No cambia al girar la imagen. Resuelve la ambigüedad "cabeza girada": con espalda visible es **boca abajo**; con pecho visible es solo **volteado**.
3. Sin cara detectada pero con pecho visible se asume **boca arriba (confianza baja)** si la cara nunca se vio; **cara tapada** solo si la cara sí se veía antes y desapareció.

**Verificado (simulación, kotlinc real):** el giro de imagen se deshace correctamente en 0/90/180/270°, el signo pecho/espalda no cambia al girar la imagen, y el clasificador responde como se describe en 10 escenarios.

**HIPÓTESIS sin validar con bebés reales:** que ML Kit mantenga bien la izquierda/derecha anatómica con un cuerpo tumbado visto desde arriba. La línea de diagnóstico del visor muestra `se ve=pecho|espalda|--` para comprobarlo. Si en fotos de bebés boca arriba sale "espalda" de forma sistemática, el signo está invertido y se corrige con un solo cambio.

## Estabilidad y alerta

- La etiqueta sale de una votación sobre ~2 s de frames: un frame con ruido no cambia el resultado.
- "Boca abajo" y "Cara tapada" son posturas de atención. La alerta ("ATENCIÓN: revisar al bebé") solo se muestra si se sostienen ≥ 3 s.
- La confianza nunca pasa de 70 %.

## Clasificación de evidencia (regla del proyecto)

- **CONFIRMADO (probado con lógica simulada):** la máquina de estados del clasificador (`SleepPositionClassifier`) responde como se describe arriba en secuencias simuladas de frames.
- **HIPÓTESIS (NO probado con un bebé real):** que ML Kit detecte bien hombros y cara de un bebé acostado, envuelto o con poca luz. Los modelos están entrenados mayoritariamente con adultos.
- **LIMITACIONES CONOCIDAS:**
  - Con una sola cámara 2D, un bebé boca abajo con la cabeza girada se parece a uno boca arriba con la cabeza girada.
  - "Boca abajo" y "Cara tapada" pueden confundirse entre sí; por eso ambas se tratan como atención.
  - Los umbrales (`SIDE_RATIO_THRESHOLD`, `SIDE_YAW_THRESHOLD_DEG`) son empíricos y hay que ajustarlos con pruebas reales.
- **NO es un dispositivo de seguridad ni diagnóstico médico.** No sustituye la supervisión de un adulto.

## Archivos

- `vision/SleepPositionClassifier.kt` — lógica de decisión (Kotlin puro, sin Android).
- `vision/BabyPoseAnalyzer.kt` — combina pose + rostro de ML Kit en cada frame.
- `vision/PoseOverlayView.kt` — esqueleto, recuadro de cara, etiqueta y alerta sobre el preview.
- `vision/BabySleepPosition.kt` — modelos de datos.

El dashboard (`NeosicherScreen`), `CameraPreviewPanel` y las tarjetas no se modificaron.
