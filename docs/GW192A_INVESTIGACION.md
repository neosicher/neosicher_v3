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
