# NEOSICHER — App Android nativa (MVP inicial)

Aplicación Android nativa de monitoreo, desarrollada en **Kotlin + Jetpack Compose**.
Este repositorio contiene el **primer MVP funcional** de la interfaz NEOSICHER y la
**base técnica** para integrar más adelante la cámara térmica **GOYOJO GW192A** por USB OTG.

> **Regla central del proyecto:** no se inventan datos térmicos ni signos vitales, y
> **no se inventa el protocolo del GW192A**. Ver `docs/GW192A_INVESTIGACION.md`.

---

## Qué incluye este MVP

| Requisito | Estado |
|---|---|
| El proyecto compila (Android Studio) | ✅ Estructura Gradle completa |
| La app abre en una pantalla dashboard cercana a la referencia | ✅ `NeosicherScreen` |
| Cámara Android: permiso + preview REAL (CameraX) | ✅ `CameraManager` + `CameraPreviewPanel` |
| Selector Cámara Android / Cámara termográfica | ✅ `NeosicherModeSelector` |
| Detección del GW192A por VID/PID | ✅ `UsbDeviceManager.findGw192a()` |
| Solicitud de permiso USB (independiente del de cámara) | ✅ `UsbDeviceManager.requestPermission()` |
| Información USB real del dispositivo | ✅ `UsbDeviceInfo` + `UsbDiagnosticsPanel` |
| Diagnóstico USB: interfaces y endpoints reales | ✅ `openAndEnumerate()` + panel de diagnóstico |
| Sin datos térmicos falsos | ✅ métricas en `--` / "Sin lectura" / "No disponible" |

**No incluido a propósito** (según el alcance de esta etapa): interpretación térmica,
streaming del GW192A, protocolo, cálculo de temperatura, heart rate o respiratory rate.
Todo eso queda marcado en el código como `REQUIERE PRUEBA EN POCO F7 + GW192A`.

---

## Arquitectura

```
app/src/main/java/com/neosicher/app/
├── MainActivity.kt            # Entry point + permiso CAMERA (accompanist)
├── camera/                    # Cámara del teléfono (CameraX)
│   ├── CameraManager.kt        · Preview + CameraSelector + ProcessCameraProvider
│   └── CameraState.kt          · estado + permiso + lente
├── usb/                       # Android USB Host (sin protocolo)
│   ├── UsbDeviceManager.kt     · detección VID/PID, permiso, enumeración real
│   └── UsbDeviceInfo.kt        · modelo de descriptors (config/interface/endpoint)
├── thermal/                   # Cámara térmica GW192A (capa de abstracción)
│   ├── ThermalCameraDataSource.kt        · interfaz
│   ├── Gw192aThermalCameraDataSource.kt  · implementación (máx. estado READY)
│   ├── ThermalCameraRepository.kt        · orquestador
│   └── ThermalCameraState.kt             · 8 estados de conexión
├── monitoring/                # Modelos de métricas (sin fuente real todavía)
│   ├── VitalSigns.kt · ThermalReading.kt · SleepStatus.kt
└── ui/
    ├── NeosicherScreen.kt      · layout adaptativo 70/30 (ancho) / apilado (retrato)
    ├── NeosicherViewModel.kt   · estado de pantalla (AndroidViewModel)
    ├── NeosicherUiState.kt
    ├── components/             · TopBar, ModeSelector, CameraPreviewPanel,
    │                             MonitoringCard, CameraSwitchButton, etc.
    └── theme/                  · Color, Type, Shape, Theme (paleta NEOSICHER)
```

**Flujo de estados de la cámara térmica** (implementado):

```
NOT_CONNECTED → DETECTING → CONNECTED → PERMISSION_REQUIRED → READY
                                                              └── (STREAMING no se alcanza en este MVP)
```

En `READY` la conexión USB está abierta y los endpoints enumerados. **Ahí se detiene**:
no se inicia stream ni se interpretan frames porque el protocolo aún no está confirmado.

---

## Versiones / toolchain

- Kotlin **2.0.21** (plugin Compose de Kotlin)
- Android Gradle Plugin **8.7.3**
- Gradle **8.11.1** (wrapper incluido)
- Compose BOM **2024.12.01**, Material 3
- CameraX **1.4.1**
- `minSdk` **26** · `targetSdk` **35** · `compileSdk` **35** · Java **17**

Dependencias declaradas con **version catalog** en `gradle/libs.versions.toml`.

---

## Cómo compilar y ejecutar

Requiere **Android Studio** (Ladybug o posterior) con el Android SDK 35 instalado.

1. Abrir el proyecto en Android Studio (carpeta raíz de este repositorio).
2. Dejar que Gradle sincronice (descargará AGP, Compose, CameraX, etc.).
3. Ejecutar sobre un **dispositivo físico** (recomendado: **POCO F7** para las pruebas
   del GW192A). El emulador sirve para la cámara Android, pero **no** para USB OTG.

### Permisos en tiempo de ejecución

- **Cámara Android:** se solicita `android.permission.CAMERA` al pulsar "Conceder permiso".
- **USB (GW192A):** al conectar el dispositivo o al entrar en modo termográfico se
  solicita el permiso USB, que es **independiente** del permiso de cámara.

---

## Probar la cámara térmica GW192A

1. Conecta el GW192A por **USB OTG** al teléfono.
2. En la app, selecciona **"Cámara termográfica"**.
3. La app detecta el dispositivo comparando **VID `0x37B4` / PID `0x0102`**.
4. Concede el **permiso USB** cuando el sistema lo pida.
5. Abre **"Mostrar diagnóstico USB"** para ver la información REAL:
   VID, PID, manufacturer, product, serial, interfaces, alternate settings y endpoints
   (dirección, tipo de transferencia, `maxPacketSize`, `interval`).

Esa información es la base para continuar la investigación del protocolo. **No** se
muestra ninguna temperatura ni imagen térmica hasta que el protocolo esté confirmado.

> **REQUIERE PRUEBA EN POCO F7 + GW192A:** la detección, el permiso y la enumeración
> deben validarse con el hardware físico. El código está preparado, pero la evidencia
> de bajo nivel (descriptors reales) sólo se obtiene con el dispositivo conectado.

---

## Nota sobre el entorno de generación

Este código se generó en un sandbox **sin Android SDK** y con **red restringida**, por lo
que **no fue posible ejecutar `./gradlew assembleDebug` aquí**. La compilación real debe
hacerse en Android Studio (o en CI con el SDK de Android). Se verificó manualmente la
coherencia de la estructura, imports y balance sintáctico de los 26 archivos Kotlin.

---

## Próximos pasos (fuera del alcance de esta etapa)

- Validar detección + permiso + enumeración con POCO F7 + GW192A reales.
- A partir de los descriptors reales, decidir (con evidencia) si el dispositivo es UVC
  y cómo se estructura el stream — siguiendo `docs/GW192A_INVESTIGACION.md`.
- Sólo entonces: implementar el pipeline térmico y conectar `ThermalReading` real
  (etiquetado como **experimental** hasta calibración).
- Añadir `ImageAnalysis` (punto de extensión ya preparado en `CameraManager`) para la
  detección/ubicación del bebé y métricas por visión.
```
