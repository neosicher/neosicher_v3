# NEOSICHER — App Android nativa

Aplicación Android nativa de monitoreo, desarrollada en **Kotlin + Jetpack Compose**.
Este repositorio contiene la interfaz NEOSICHER (fijada a **orientación horizontal**)
y la integración con la cámara térmica **GOYOJO GW192A** por USB OTG, incluyendo un
**streaming experimental** basado en evidencia real de descriptors USB.

> **Regla central del proyecto:** no se inventan datos térmicos ni signos vitales, y
> **no se inventa el protocolo del GW192A**. Toda interpretación térmica se basa en
> evidencia real (descriptors del dispositivo) y se marca explícitamente como
> **EXPERIMENTAL / sin calibrar**. Ver `docs/GW192A_INVESTIGACION.md`.

---

## Qué incluye

| Requisito | Estado |
|---|---|
| El proyecto compila y corre en hardware real (POCO F7) | ✅ Validado |
| Interfaz horizontal, independiente del bloqueo de pantalla del sistema | ✅ `screenOrientation="sensorLandscape"` |
| Cámara Android: permiso + preview REAL (CameraX) | ✅ `CameraManager` + `CameraPreviewPanel` |
| Selector Cámara Android / Cámara termográfica | ✅ `NeosicherModeSelector` |
| Detección del GW192A por VID/PID | ✅ Validado con hardware real (VID `0x37B4`/PID `0x0102`) |
| Permiso USB (independiente del de cámara) | ✅ Validado |
| Diagnóstico USB: interfaces y endpoints reales | ✅ Validado (ver evidencia en `docs/GW192A_INVESTIGACION.md` §12) |
| Lectura de descriptors UVC crudos (formatos/frames declarados) | ✅ `UvcDescriptorParser` |
| Streaming UVC real (negociación Probe/Commit + lectura por bulk) | ✅ `UvcStreamingSession` — **REQUIERE PRUEBA EN POCO F7 + GW192A** para confirmar que el hardware acepta la negociación |
| Interpretación experimental del frame (paleta de calor relativa) | ✅ `ThermalFrameInterpreter` — solo si los descriptors confirman la hipótesis de doble altura |
| Temperatura calibrada en °C, heart rate, respiratory rate | ❌ Deliberadamente NO implementado: no existe evidencia ni calibración |

---

## ⚠️ Alcance real de la interpretación térmica (léase antes de probar)

La cámara térmica GW192A **no tiene protocolo público documentado ni datasheet oficial**.
Ante tu pedido explícito de investigar y avanzar, se implementó lo siguiente **con
evidencia y límites claros** (nunca copiando la app propietaria THG Start ni ningún
código cerrado — ver §10 y §13 de `docs/GW192A_INVESTIGACION.md`):

1. **Lectura de descriptors UVC crudos** (`UvcDescriptorParser`): usa la especificación
   **pública** USB-IF "Video Class" para leer qué formatos/resoluciones declara el
   propio dispositivo. Es solo lectura, no asume nada de antemano.
2. **Hipótesis de "doble altura"**: es un patrón **documentado públicamente en
   proyectos open-source de terceros** (no en THG Start) sobre módulos térmicos UVC de
   gama similar (InfiRay P2 Pro y clones): el frame declara el doble de alto que de
   ancho; la mitad superior es video visible (YUYV) y la mitad inferior transporta
   datos crudos del sensor. **Esta hipótesis se aplica únicamente si los descriptors
   reales del GW192A la confirman** (frame con `height == width * 2`). Si no la
   confirman, la app se detiene en `READY` sin inventar nada.
3. **Negociación UVC estándar** (`UvcStreamingSession`): Probe/Commit y lectura por
   **bulk transfer** (confirmado como el tipo real de endpoint del GW192A). Si el
   dispositivo rechaza la negociación, o el endpoint real fuera isócrono (no soportado
   en este MVP), la app falla de forma honesta y visible — nunca simula un stream.
4. **Visualización, no medición**: cuando hay stream real, se muestra una **paleta de
   calor relativa** (normalizada min–max dentro del propio frame, sin fórmula de
   conversión a grados) con la etiqueta permanente **"Experimental · sin calibrar"** y
   los valores crudos (`raw[min–max]`). Esto **no es una temperatura**, es solo una
   ayuda visual de contraste relativo del propio sensor.

**Este enfoque es más conservador que un simulador de THG Start a propósito**: prioriza
la integridad de la investigación (evidencia real, clasificada como
CONFIRMADO/INFERIDO/HIPÓTESIS/NO DETERMINADO) sobre una demo visual bonita.

---

## Arquitectura

```
app/src/main/java/com/neosicher/app/
├── MainActivity.kt            # Entry point + permiso CAMERA (accompanist)
├── camera/                    # Cámara del teléfono (CameraX)
│   ├── CameraManager.kt        · Preview + CameraSelector + ProcessCameraProvider
│   └── CameraState.kt          · estado + permiso + lente
├── usb/                       # Android USB Host + UVC (protocolo público, no THG Start)
│   ├── UsbDeviceManager.kt         · detección VID/PID, permiso, enumeración, descriptors crudos
│   ├── UsbDeviceInfo.kt            · modelo de descriptors (config/interface/endpoint)
│   ├── UvcModels.kt                · modelos de formato/frame UVC (spec pública USB-IF)
│   ├── UvcDescriptorParser.kt      · parsea descriptors crudos en busca de VC_HEADER/VS_FORMAT/VS_FRAME
│   ├── UvcControlRequests.kt       · struct Probe/Commit y bRequest estándar UVC
│   ├── UvcStreamingSession.kt      · negociación real + lectura de frames por bulk transfer
│   └── YuyvDecoder.kt              · conversión YUY2→RGB (estándar público ITU-R BT.601)
├── thermal/                   # Cámara térmica GW192A
│   ├── ThermalCameraDataSource.kt        · interfaz
│   ├── Gw192aThermalCameraDataSource.kt  · orquesta detección→permiso→READY→intento de streaming
│   ├── ThermalCameraRepository.kt        · orquestador
│   ├── ThermalCameraState.kt             · estados de conexión (hasta STREAMING si hay evidencia)
│   └── ThermalFrameInterpreter.kt        · interpretación EXPERIMENTAL (paleta relativa, no °C)
├── monitoring/                # Modelos de métricas (sin fuente real todavía)
│   ├── VitalSigns.kt · ThermalReading.kt · SleepStatus.kt
└── ui/
    ├── NeosicherScreen.kt      · layout horizontal 70/30 fijo (con red de seguridad angosta)
    ├── NeosicherViewModel.kt   · estado de pantalla (AndroidViewModel)
    ├── NeosicherUiState.kt
    ├── components/             · TopBar, ModeSelector, CameraPreviewPanel,
    │                             MonitoringCard, CameraSwitchButton, etc.
    └── theme/                  · Color, Type, Shape, Theme (paleta NEOSICHER)
```

**Flujo de estados de la cámara térmica:**

```
NOT_CONNECTED → DETECTING → CONNECTED → PERMISSION_REQUIRED → READY
                                                                 │
                                              (solo si los descriptors UVC
                                               confirman formato válido y la
                                               negociación Probe/Commit es
                                               aceptada por el hardware real)
                                                                 ▼
                                                            STREAMING
```

Si la negociación falla en cualquier punto, el estado vuelve a `READY` con
`errorDetail` explicando por qué, nunca se fuerza `STREAMING`.

---

## Orientación horizontal

La app está fijada a horizontal (`android:screenOrientation="sensorLandscape"` en
`MainActivity`), **independientemente de si la pantalla del sistema está bloqueada en
vertical**. El layout tipo dashboard (70% cámara / 30% panel) es el predeterminado; se
conserva un modo apilado solo como red de seguridad para anchos extremadamente angostos
(p. ej. pantalla de cobertura de un plegable), sin fijar tamaños absolutos.

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

Requiere **Android Studio** con el Android SDK 35 instalado.

1. Clonar el repositorio y cambiar a la rama `develop`.
2. Abrir el proyecto en Android Studio y dejar que Gradle sincronice.
3. Ejecutar sobre un **dispositivo físico** (validado en **POCO F7**). El emulador sirve
   para la cámara Android, pero **no** para USB OTG.

### Permisos en tiempo de ejecución

- **Cámara Android:** se solicita `android.permission.CAMERA` al pulsar "Conceder permiso".
- **USB (GW192A):** al conectar el dispositivo o al entrar en modo termográfico se
  solicita el permiso USB, independiente del permiso de cámara.

---

## Probar la cámara térmica GW192A

1. Conecta el GW192A por **USB OTG** al teléfono.
2. En la app, selecciona **"Cámara termográfica"**.
3. La app detecta el dispositivo (VID `0x37B4` / PID `0x0102`), pide el permiso USB, abre
   la conexión y enumera interfaces/endpoints reales (estado `READY`).
4. Automáticamente intenta leer los descriptors UVC y, **si confirman** un frame de
   doble altura, negocia el streaming real. Si tiene éxito, verás la paleta de calor
   experimental con la etiqueta **"Experimental · sin calibrar"**. Si no, el mensaje
   indicará honestamente por qué no hay stream (sin datos inventados).
5. Abre **"Mostrar diagnóstico USB"** para ver toda la información REAL del dispositivo.

> **REQUIERE PRUEBA EN POCO F7 + GW192A** para confirmar en qué punto exacto llega la
> negociación con hardware real (los descriptors ya se validaron; ver
> `docs/GW192A_INVESTIGACION.md` §12). Si el dispositivo no acepta el Probe/Commit
> estándar, la app lo reportará como error honesto en vez de forzar un stream falso.

---

## Próximos pasos posibles

- Registrar en `docs/GW192A_INVESTIGACION.md` §13.4 el resultado real de
  `readUvcDescriptors()` contra el GW192A (qué formatos/frames declara de verdad).
- Si la negociación Probe/Commit falla con el hardware real, capturar el `errorDetail`
  exacto para decidir el siguiente paso de investigación.
- Si en el futuro se dispusiera de evidencia de calibración (emissivity, tabla de
  referencia confirmada por el fabricante), evaluar una conversión real a °C — nunca
  antes de tener esa evidencia.
