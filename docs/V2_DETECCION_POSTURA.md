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
