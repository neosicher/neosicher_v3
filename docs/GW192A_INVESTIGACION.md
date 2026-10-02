# Investigación técnica — GOYOJO GW192A / Android USB OTG

## 1. Objetivo de la investigación

Determinar cómo acceder técnicamente al GOYOJO GW192A conectado por USB OTG a un teléfono Android y establecer, mediante evidencia experimental, cómo obtiene, transmite y estructura sus datos de imagen térmica.

La cadena que debe comprobarse es:

GW192A
→ USB OTG
→ Android USB Host
→ dispositivo/configuración
→ interfaces
→ endpoints
→ protocolo
→ transferencia
→ stream/frame
→ formato
→ datos térmicos
→ temperatura/calibración

No debe asumirse ninguna etapa sin evidencia.

---

## 2. Dispositivo identificado

El GW192A fue observado conectado por USB con:

- Nombre: Camera
- VID: 0x37B4
- PID: 0x0102
- USB Class: 239
- USB Subclass: 2
- USB Protocol: 1

Esta identificación debe considerarse evidencia directa del dispositivo observado.

El VID/PID no demuestra por sí solo qué protocolo utiliza.

---

## 3. Hipótesis USB/UVC

La investigación previa encontró indicios que hacen razonable investigar si el dispositivo utiliza USB Video Class (UVC).

Sin embargo, NO está confirmado que el GW192A sea UVC.

No asumir:

- UVC
- YUYV
- UYVY
- MJPEG
- RGB
- RAW8
- RAW16
- bulk transfer
- isochronous transfer
- controles UVC Extension Unit
- resolución específica
- estructura específica de frame
- datos térmicos radiométricos dentro del frame

La existencia de Class 239 / Subclass 2 / Protocol 1 debe investigarse mediante los descriptors reales del dispositivo.

---

## 4. Investigación Android

Para Android, las APIs relevantes son:

- UsbManager
- UsbDevice
- UsbConfiguration
- UsbInterface
- UsbEndpoint
- UsbDeviceConnection
- UsbRequest

Flujo técnico esperado:

1. Detectar el dispositivo.
2. Obtener VID/PID y propiedades.
3. Obtener configuraciones.
4. Enumerar interfaces.
5. Enumerar alternate settings.
6. Enumerar endpoints.
7. Determinar dirección y tipo de transferencia.
8. Solicitar permiso USB.
9. Abrir UsbDeviceConnection.
10. Investigar comunicación con el dispositivo.
11. Determinar si existe una interfaz de cámara estándar.
12. Determinar cómo obtener frames.
13. Determinar la estructura del frame.
14. Determinar si contiene información térmica.

El permiso de USB debe diferenciarse del permiso Android de la cámara del teléfono.

---

## 5. Investigación previa de THG Start

Se realizó análisis estático de THG Start 2.5.1.

Se encontraron referencias relacionadas con dispositivos térmicos USB y HIKMICRO.

Bibliotecas nativas observadas:

- libHCUSBSDK.so
- libusbCam_host.so

`libusbCam_host.so` contiene símbolos relacionados con:

- hal_usbcam_host_init
- wait_for_connect
- start_streaming
- stop_streaming
- set_stream_params
- get_streaming_buf
- pass_through_read
- pass_through_write
- get_product_info
- get_calib_temp
- set_calib_temp
- get_cavity_temperature
- set_cavity_temperature
- upgrade

También aparecen funciones relacionadas con UVC:

- uvc_find_device
- uvc_open
- uvc_claim_if
- uvc_stream_start
- uvc_stream_get_frame
- uvc_xu_get_cur
- uvc_xu_set_cur
- uvc_yuyv2rgb
- uvc_uyvy2rgb

La aplicación contiene referencias a estructuras de termometría y stream como:

- USB_THERMOMETRY_BASIC_PARAM
- USB_THERMOMETRY_MODE
- USB_THERMOMETRY_REGIONS
- USB_BODYTEMP_COMPENSATION
- USB_THERMOMETRY_CALIBRATION_FILE
- USB_THERMOMETRY_EXPERT_REGIONS
- USB_THERMOMETRY_EXPERT_CORRECTION_PARAM
- USB_THERMOMETRY_RISE_SETTINGS
- USB_THERMAL_STREAM_PARAM
- USB_THERMAL_STREAM_REALTIME
- USB_THERMAL_STREAM_TEMP_YUV
- USB_THERMAL_STREAM_TEMP_HOT
- USB_THERMAL_STREAM_TEMP_YUV_OFFLINE
- USB_ROI_MAX_TEMPERATURE_SEARCH

También se encontraron comandos relacionados con termometría, calibración y stream.

Esto demuestra que THG Start contiene infraestructura para cámaras térmicas USB, pero NO demuestra que el GW192A utilice exactamente el mismo protocolo.

THG Start debe utilizarse únicamente como fuente de hipótesis técnicas.

No copiar ni reutilizar código propietario, bibliotecas propietarias, firmware, credenciales ni implementaciones cerradas.

---

## 6. Referencias HIKMICRO encontradas

En THG Start aparecen endpoints y referencias como:

- /ISAPI/System/deviceInfo
- /ISAPI/System/time
- /ISAPI/Thermal/capabilities
- /ISAPI/Thermal/channels/
- /ISAPI/Image/channels/
- /ISAPI/Streaming/channels/
- /thermometry/basicParam
- /thermometry/realTimethermometry/rules
- /StreamTemperatureParams

También aparece:

- global-datacenter.hikmicrotech.com

Se encontró referencia a VID HIKMICRO 0x2BDF y públicamente se ha observado 0x2BDF:0x0102 asociado a dispositivos HIKMICRO.

Esto NO debe utilizarse para identificar al GW192A como HIKMICRO.

El GW192A observado tiene:

0x37B4:0x0102

Por tanto, no deben mezclarse ambos dispositivos.

---

## 7. Investigación WebUSB previa

También se investigó la posibilidad de acceder al dispositivo desde una aplicación web/PWA mediante:

- navigator.usb
- USBDevice
- requestDevice()
- getDevices()
- open()
- selectConfiguration()
- claimInterface()
- selectAlternateInterface()
- controlTransferIn()
- controlTransferOut()
- bulkTransferIn()
- bulkTransferOut()
- isochronousTransferIn()
- isochronousTransferOut()

La investigación concluyó que WebUSB puede servir como experimento para comprobar el acceso desde Chrome/Android, pero no debe considerarse la arquitectura principal.

El objetivo actual es desarrollar la aplicación en Android Studio utilizando Android USB Host.

---

## 8. Preguntas técnicas todavía sin resolver

Debe determinarse experimentalmente:

### USB

- Configuración USB utilizada.
- Número de interfaces.
- Alternate settings.
- Clase/subclase/protocolo de cada interfaz.
- Endpoints.
- Dirección de cada endpoint.
- Tipo de transferencia.
- Maximum packet size.
- Intervalo.
- Interfaz utilizada para vídeo.
- Interfaces adicionales.
- Controles disponibles.

### UVC

Si existe una interfaz UVC, determinar:

- VideoControl interface.
- VideoStreaming interface.
- descriptors UVC.
- formatos soportados.
- frame descriptors.
- resoluciones.
- frame intervals.
- payload.
- controles estándar.
- Extension Units, si existen.

### Stream

Determinar:

- cómo inicia el stream;
- qué comandos/control transfers son necesarios;
- qué endpoint transporta los datos;
- cómo se divide un frame;
- cómo detectar inicio/final de frame;
- si existen headers;
- si existen metadatos;
- si existe información térmica.

### Datos térmicos

Determinar:

- si el dispositivo transmite valores radiométricos;
- si transmite una imagen térmica codificada;
- si transmite YUV/RGB u otro formato;
- si los valores de temperatura están en metadata;
- si existe una tabla de calibración;
- si existe compensación de temperatura;
- si existe información de emissivity;
- si la temperatura puede calcularse directamente;
- qué precisión y repetibilidad pueden obtenerse.

---

## 9. Clasificación de evidencia

Toda conclusión debe clasificarse como:

CONFIRMADO
Evidencia obtenida directamente mediante el dispositivo, Android, descriptors, código ejecutado o documentación fiable.

DOCUMENTADO
Información encontrada en documentación técnica o fuente externa fiable, pero no comprobada directamente en el GW192A.

INFERIDO
Conclusión derivada razonablemente de evidencia existente.

HIPÓTESIS
Posibilidad que todavía debe comprobarse.

NO DETERMINADO
No existe información suficiente.

No convertir una hipótesis en un hecho.

---

## 10. Regla fundamental

NO INVENTAR EL PROTOCOLO DEL GW192A.

La identificación VID/PID, la clase USB o la existencia de funciones similares en THG Start no son suficientes para afirmar cómo funciona el dispositivo.

La investigación debe avanzar desde evidencia de bajo nivel:

VID/PID
→ descriptors
→ interfaces
→ endpoints
→ protocolo
→ transferencias
→ frames
→ formato
→ datos térmicos
→ calibración

Solo después de comprobar cada etapa se debe implementar la siguiente.

---

## 11. Estado actual

Confirmado:

- Existe un dispositivo USB identificado como Camera.
- VID = 0x37B4.
- PID = 0x0102.
- Class = 239.
- Subclass = 2.
- Protocol = 1.
- El objetivo es utilizar Android USB Host.
- THG Start contiene infraestructura relacionada con cámaras térmicas USB y UVC.

No confirmado:

- Protocolo exacto del GW192A.
- Si es UVC estándar (existe evidencia consistente con UVC, ver sección 12; no está comprobado mediante comunicación real).
- Formato de vídeo.
- Método de inicio de streaming.
- Estructura de frame.
- Ubicación de datos térmicos.
- Método de conversión a temperatura.
- Calibración.

La investigación debe continuar desde este estado y no reiniciar las hipótesis ya documentadas.

---

## 12. Evidencia de descriptors USB reales (Android USB Host, app NEOSICHER)

**Fecha:** primera enumeración real en dispositivo físico (POCO F7 + GW192A por USB OTG),
mediante `UsbDeviceManager.openAndEnumerate()` de la app NEOSICHER (ver
`app/src/main/java/com/neosicher/app/usb/UsbDeviceManager.kt`). Datos leídos directamente
de `UsbDevice` / `UsbConfiguration` / `UsbInterface` / `UsbEndpoint` de Android, sin
interpretación de protocolo.

### 12.1 Datos crudos (CONFIRMADO)

```
Nombre:          /dev/bus/usb/001/002
VID:             0x37B4
PID:             0x0102
Class/Sub/Proto: 239/2/1
Manufacturer:    Camera
Product:         Camera
Serial:          EA5965521

Config #1 · 200mA · selfPowered=false
  Interface #0 alt=0 class=14 sub=1 proto=0
    EP 0x83 IN INTERRUPT max=16  int=8
  Interface #1 alt=0 class=14 sub=2 proto=0
    EP 0x81 IN BULK      max=512 int=0
```

Este resultado confirma y amplía la evidencia de la sección 2 (mismo VID/PID/Class/Sub/Proto
a nivel de dispositivo) y añade, por primera vez, evidencia a nivel de **interfaz**.

### 12.2 Lectura de la evidencia (sin asumir protocolo de streaming)

- **Class de dispositivo 239 / Subclass 2 / Protocol 1**: es la combinación estándar
  USB-IF para "Miscellaneous — Interface Association Descriptor" (IAD), usada por
  dispositivos compuestos que agrupan varias interfaces relacionadas bajo una sola función.
  **CONFIRMADO** (leído del descriptor de dispositivo); la implicación de que agrupa las
  dos interfaces de video de abajo es **INFERIDO** (razonable dado el patrón, no verificado
  leyendo el IAD explícito).

- **Interface #0: class=14, subclass=1, protocol=0**. La clase USB `14` (0x0E) está
  reservada por USB-IF para **Video**, y la subclase `1` corresponde a
  **VideoControl Interface**. **CONFIRMADO** que la interfaz declara esa clase/subclase;
  **INFERIDO** que funciona como VideoControl real (no se ha leído el Class-Specific
  VC Interface Descriptor ni comprobado ningún control transfer).
  Su endpoint `0x83 IN INTERRUPT` (max 16 bytes) es consistente con el "status interrupt
  endpoint" opcional que UVC define para VideoControl. **INFERIDO**, no confirmado.

- **Interface #1: class=14, subclass=2, protocol=0**. Subclase `2` corresponde a
  **VideoStreaming Interface** en la especificación UVC. **CONFIRMADO** a nivel de
  clase/subclase declarada. Su endpoint `0x81 IN BULK` (max 512 bytes, sin intervalo)
  indica transferencia **bulk**, no isócrona. **CONFIRMADO** que el tipo de transferencia
  es bulk (leído del descriptor de endpoint); esto descarta la hipótesis de streaming
  isócrono para este dispositivo, algo que antes era NO DETERMINADO.

- **Conjunto (class=14 en dos interfaces con subclases 1 y 2)**: es el patrón estructural
  estándar de un dispositivo **UVC (USB Video Class)** compuesto. **INFERIDO** con
  bastante fuerza a partir de evidencia directa de descriptors — pero **sigue sin
  confirmarse** mediante comunicación real (no se han leído los Class-Specific
  Descriptors de formato/frame UVC, ni enviado ningún control transfer, ni iniciado
  ningún stream). No se debe tratar como CONFIRMADO todavía.

### 12.3 Qué NO se puede concluir de esta evidencia

- No se confirma el **formato de vídeo** (YUYV/MJPEG/RAW16/otro): requiere leer los
  Class-Specific VS Interface Descriptors (Format/Frame descriptors) dentro de
  Interface #1, que Android `UsbInterface`/`UsbEndpoint` no expone directamente — haría
  falta leer el **descriptor de configuración crudo** (`UsbDeviceConnection.getRawDescriptors()`
  o equivalente) y parsearlo manualmente.
- No se confirma si existe **información térmica** en el stream, ni su ubicación, ni
  calibración.
- No se confirma el **método de inicio de streaming** (qué control transfers UVC
  estándar — `SET_CUR`/Probe-Commit — acepta o requiere este dispositivo en concreto).

### 12.4 Próximo paso sugerido (no implementado todavía)

Para avanzar en la cadena de evidencia sin inventar protocolo, el siguiente paso natural
sería leer y registrar los **descriptors crudos de configuración** (bytes completos vía
`getRawDescriptors()`), que contienen los Class-Specific Descriptors de UVC (formatos,
resoluciones, frame intervals) si el dispositivo los implementa. Esto seguiría siendo
**solo lectura/diagnóstico**, sin iniciar streaming ni enviar comandos de control.
**REQUIERE PRUEBA EN POCO F7 + GW192A.**

---

## 13. Lectura de descriptors crudos UVC e hipótesis de formato "doble altura"

### 13.1 Qué se implementó

Se implementó `UvcDescriptorParser` (`app/src/main/java/com/neosicher/app/usb/UvcDescriptorParser.kt`),
que lee `UsbDeviceConnection.getRawDescriptors()` (bytes crudos de la configuración USB
activa) y busca descriptors **class-specific** de UVC dentro de ellos:

- `VC_HEADER` dentro de la interfaz VideoControl (confirma o descarta que exista el
  descriptor de cabecera estándar de VideoControl).
- `VS_FORMAT_UNCOMPRESSED` / `VS_FORMAT_MJPEG` / `VS_FORMAT_FRAME_BASED` y sus
  `VS_FRAME_*` asociados dentro de la interfaz VideoStreaming: formato(s) y
  resolución(es)/frame-rate(s) que el dispositivo declara soportar.

**Fuente de la estructura de bytes parseada:** la especificación pública
**"USB Device Class Definition for Video Devices" (UVC 1.1/1.5, USB Implementers
Forum)** — un estándar publicado y de acceso público, igual que el USB 2.0 Core Spec ya
usado en las secciones 2 y 12. **No se ha usado THG Start ni ningún código propietario**
para construir este parser, en cumplimiento de la sección 10 de este documento.

Es una operación de **solo lectura**: abre la conexión únicamente para llamar a
`getRawDescriptors()` y la cierra inmediatamente. No reclama (`claimInterface`) ninguna
interfaz, no envía control transfers, no inicia ningún stream.

### 13.2 Hipótesis de trabajo: formato "doble altura" (HIPÓTESIS, no confirmada para el GW192A)

Es públicamente conocido —documentado en proyectos open-source de terceros que trabajan
con módulos térmicos UVC de bajo costo similares en tamaño/precio al GW192A (p. ej.
InfiRay P2 Pro y clones; ver repositorios públicos como `alufers/thermal-cat`,
`LeoDJ/P2Pro-Viewer`, `fbreitwieser/thermal-camera-android`, `ks00x/p2proviewer`,
`cfbird/HT203U-Thermal`)— que una familia de módulos sensores térmicos expone un único
stream UVC cuyo **frame declarado tiene el doble de alto que de ancho** (p. ej.
192×384 en vez de 192×192): la mitad superior transporta la imagen visible/paleta
(YUYV) y la mitad inferior transporta datos térmicos crudos empaquetados dentro del
mismo formato de píxel.

**Esto es una HIPÓTESIS aplicada por analogía con hardware de terceros, NO evidencia
directa del GW192A.** `UvcParseResult.doubleHeightCandidates` en el código marca
explícitamente (en comentario y en log) cualquier frame cuya altura declarada sea 2×
su ancho, precisamente para poder contrastar la hipótesis contra los bytes reales del
GW192A sin asumir que aplica de antemano.

### 13.3 Qué confirmaría o descartaría la hipótesis

- Si los `VS_FRAME_*` reales del GW192A declaran una resolución con **altura = 2×
  ancho** (p. ej. 192×384) → **evidencia a favor** de que sigue el mismo patrón que la
  familia de hardware de referencia. Seguiría siendo INFERIDO, no CONFIRMADO, hasta
  abrir el stream y verificar que los bytes de la mitad inferior no son imagen visible.
- Si declara una resolución **cuadrada** (192×192) u otra proporción → la hipótesis
  queda **descartada** para este dispositivo y no debe aplicarse el parseo de doble
  altura.
- Si no hay ningún `VS_FORMAT_*`/`VS_FRAME_*` en absoluto (p. ej. porque la clase 14
  declarada en las interfaces no corresponde realmente a descriptors UVC completos) →
  la hipótesis "es UVC estándar" pasa de INFERIDO a **NO DETERMINADO / posiblemente
  falso**, y no debe intentarse abrir el stream como UVC.

### 13.4 Estado de verificación

Con hardware real (POCO F7 + GW192A), los descriptors UVC **no declararon** ningún
frame de doble altura (`height == width * 2`): la app reportó *"Los descriptors UVC no
declaran un formato reconocible (no se confirma la hipótesis de doble altura para este
dispositivo)"*. **La hipótesis de la sección 13.2 queda DESCARTADA para el GW192A.**

### 13.5 Evidencia pública de terceros con el MISMO hardware (DOCUMENTADO)

Se localizó un repositorio público, independiente de este proyecto y de THG Start,
que documenta pruebas directas sobre un **GOYOJO GW192A real** desde Windows/Python +
OpenCV + ffmpeg:

**Fuente:** [`kuczy/GOYOJO-GW192A-Thermal-Camera`](https://github.com/kuczy/GOYOJO-GW192A-Thermal-Camera)
(repositorio público en GitHub, sin licencia explícita declarada en el repo; se cita
aquí únicamente como evidencia de investigación, sin copiar su código en NEOSICHER).

Hallazgos reportados por el autor de ese repositorio (clasificados como **DOCUMENTADO**:
evidencia de fuente externa fiable, con el mismo modelo de hardware, pero no verificada
directamente por nosotros en el mismo dispositivo físico que usa este proyecto):

- El GW192A se anuncia y vende como cámara de **192×192 píxeles**, pero el sensor
  óptico real es de **96×96 píxeles**. Confirmado por el autor mediante
  `ffmpeg -list_options true -f dshow -i video="UVC Camera"` en Windows.
- De los formatos que expone, **solo tres son reproducibles**:
  - `96×96 NV12` — imagen en escala de grises, limpia.
  - `96×100 YUYV422` — imagen con fuerte tinte verde, alto contraste.
  - `96×176 YUYV422` — un **compuesto**: la imagen 96×100 más dos copias adicionales
    más pequeñas debajo (no es "doble altura" simple en el sentido de la hipótesis
    13.2; es una composición distinta, con contenido duplicado, no datos crudos).
- **Ninguno de los formatos contiene datos de 16 bits en escala de grises** — es decir,
  ningún formato transporta un mapa de temperatura radiométrico. El propio autor lo
  intentó analizar (incluido un intento con asistencia de un LLM) sin obtener
  información térmica adicional.
- Sin lectura directa de temperatura, el autor solo pudo mostrar la **intensidad
  relativa de calor en escala 0–100%** a partir del valor de gris bajo el cursor — es
  decir, el mismo tipo de límite que ya aplicábamos en NEOSICHER por regla propia
  (sección 6 y 14 de este documento), ahora confirmado independientemente por otra
  persona con el mismo hardware.

**Conclusión para NEOSICHER (acción tomada en el código):**

- Se sustituyó la heurística de doble altura como estrategia principal por
  `UvcParseResult.recommendedFrame`, que prioriza: 1) cualquier formato cuyo FourCC
  (leído del GUID real de 16 bytes del descriptor UVC, no asumido) sea `NV12`; 2) el
  formato YUYV/YUY2/UYVY de **menor resolución** disponible (para evitar elegir por
  accidente un compuesto con copias extra, como el `96×176` descrito arriba); 3)
  cualquier otro formato sin comprimir; 4) como último recurso, el candidato de doble
  altura (útil solo si otra unidad/firmware sí lo implementara).
- `ThermalFrameInterpreter.interpretFrame` decodifica el frame según el FourCC real
  (NV12 semi-planar o YUYV empaquetado), extrae **solo la luminancia** (brillo) y
  aplica una paleta de calor **relativa** (normalizada min–max dentro del propio
  frame) — la misma estrategia que usa el script de referencia con `cv2.applyColorMap`.
  **Nunca se calcula temperatura en grados**, consistente con que ni siquiera la
  evidencia pública de terceros logró extraerla de este hardware.

**REQUIERE PRUEBA EN POCO F7 + GW192A** para confirmar qué FourCC(s) y resolución(es)
exactas declara *nuestra* unidad concreta (podría variar por lote/firmware respecto a
la unidad documentada por `kuczy`). El panel de diagnóstico USB de la app ahora muestra
el FourCC real y cuál frame fue elegido (`← elegido`), precisamente para registrar esa
evidencia aquí en una próxima actualización de este documento.

### 13.6 CONFIRMADO con hardware real: visualización en OBS (Windows) + fallo de GET_CUR(Probe) en Android

**Evidencia CONFIRMADA (observación directa del propio usuario del proyecto, no de
terceros):** se conectó el GW192A a un PC Windows y se abrió en **OBS Studio** como
"UVC Camera". OBS muestra el stream en vivo con normalidad: una imagen con paleta de
color (verde para zonas, magenta para contornos de manos) **compuesta en dos mitades
apiladas**, la inferior con **dos copias adicionales más pequeñas y relleno gris**. Esta
composición coincide visualmente con el formato `96×176 YUYV422` descrito en la sección
13.5 (DOCUMENTADO por `kuczy`), ahora **CONFIRMADO** visualmente con nuestra propia
unidad: el GW192A sí transmite ese compuesto de forma real y reproducible.

**Fallo observado en la app Android (POCO F7):** al negociar el streaming, el
dispositivo acepta `SET_CUR(VS_PROBE_CONTROL)` pero `GET_CUR(VS_PROBE_CONTROL)` **no
devuelve una respuesta UVC válida** (la app lo reportaba como error y detenía la
negociación). Esto es coherente con que el driver UVC de Windows **sí** logra
reproducir el stream: los drivers UVC de escritorio son tolerantes a implementaciones
parciales del estándar y pueden continuar la negociación sin depender de que el
dispositivo responda `GET_CUR` correctamente.

**Corrección aplicada (sin inventar protocolo):** cuando `GET_CUR(Probe)` falla, la
app ahora reutiliza como "negociado" el mismo struct `VideoProbeCommitControl` que
**ella misma propuso** en el `SET_CUR(Probe)` anterior (ver
`UvcControlRequests.requestAsResult`), y continúa con `SET_CUR(VS_COMMIT_CONTROL)`
usando ese struct. Esto sigue pidiendo exactamente el `formatIndex`/`frameIndex` que
el propio dispositivo declaró en sus descriptors (sección 13 anterior) — no es una
suposición sobre el protocolo, es una tolerancia estándar ante un `GET_CUR` no
implementado, equivalente a lo que ya hace el driver UVC de Windows con este mismo
hardware.

**Estado:** corregido en código; pendiente de volver a probar en POCO F7 + GW192A para
confirmar si, superado el `GET_CUR`, el `SET_CUR(Commit)` y la lectura por bulk
transfer completan la negociación y entregan frames reales.

**Actualización posterior:** confirmado — tras el fix, el stream se visualiza
correctamente en la app (ver sección 14).

---

## 14. Calibración empírica de temperatura (decisión explícita del usuario)

### 14.1 Contexto y decisión

Confirmado el streaming visual (sección 13.6), el usuario del proyecto solicitó
explícitamente una **aproximación de temperatura en °C**, siendo consciente de que:

- El GW192A **no** expone datos radiométricos (CONFIRMADO en secciones 13.5 y 13.6).
- No existe datasheet público del sensor ni fórmula de calibración oficial del
  fabricante (búsqueda pública realizada sin resultado — ver proceso de esta sección).
- El usuario **decidió explícitamente no contactar al fabricante** y optó por calibrar
  de forma empírica usando otro sensor de referencia de su propiedad.

Esta decisión quedó documentada aquí para que cualquier lectura futura de este
documento entienda que la "temperatura" mostrada en la app **no es una medición
radiométrica real**, sino una **aproximación por regresión lineal de 2-3 puntos**,
exactamente igual al método de "corrección de dos puntos" descrito en literatura
pública de calibración de sensores IR no enfriados (ver referencias de la búsqueda:
Theocalibration de cámaras térmicas no enfriadas, NIST HB.157, MDPI 20/11/3316).

### 14.2 Qué se implementó

- `ThermalCalibration` (`app/.../thermal/ThermalCalibration.kt`): hasta 3 puntos
  (frío conocido, caliente conocido, corporal). Con 2+ puntos de luminancia (`raw`)
  distinta, ajusta `temperatura = pendiente·raw + intercepto` por mínimos cuadrados.
  **Con 0 o 1 punto, no hay recta: no se inventa ninguna conversión.**
- El punto **corporal** puede arrancar con un **supuesto editable** de 36.0 °C
  (promedio de piel humana, a petición explícita del usuario) mientras no se mida con
  el sensor de referencia real — la UI marca ese punto como "supuesto, no medido" y
  reduce la confianza reportada (`confidence`) cuando se usa.
- `ThermalFrameResult.sampleCenterPoint()`: lee el valor de luminancia cruda del
  píxel central del frame (promediado en una ventana 5×5 para reducir ruido) — es
  el "punto central de medición" pedido, representando la zona apuntada por el usuario
  (p. ej. la frente).
- `NeosicherViewModel` aplica la calibración a cada frame nuevo y actualiza SOLO
  `thermalReading.temperatureCelsius`, sin tocar ningún otro componente de la UI.
  El resultado se marca siempre `ThermalReadingStatus.EXPERIMENTAL` — la tarjeta
  "Temperatura" ya mostraba ese estado como "Lectura térmica experimental"
  (ver `MonitoringCard.kt`, sin cambios).
- **Pantalla nueva e independiente** `ThermalCalibrationScreen`
  (`app/.../ui/ThermalCalibrationScreen.kt`): permite capturar el valor crudo actual
  y asociarlo a una temperatura real medida con el sensor de referencia del usuario,
  para cada uno de los 3 puntos. Accesible solo desde un enlace discreto dentro del
  panel de diagnóstico USB (ya oculto por defecto) — **no se modificó el diseño del
  dashboard principal**, tal como se pidió explícitamente.

### 14.3 Limitaciones honestas de este enfoque (no deben olvidarse)

- La luminancia (`raw`) ya pasó por procesamiento interno de la cámara (posible AGC/
  normalización) — no hay garantía de que la relación raw→temperatura sea lineal en
  todo el rango, solo se aproxima localmente entre los puntos calibrados.
- No hay compensación de emisividad, distancia, ángulo ni temperatura ambiente.
- La calibración se pierde si cambian las condiciones de la escena de forma
  significativa respecto al momento en que se tomaron los puntos.
- **Nunca debe presentarse como diagnóstico médico** (regla ya establecida en la
  sección 6 y 14 original del documento): es una aproximación experimental, con una
  confianza reportada deliberadamente baja (`confidence` 0.3–0.6).
