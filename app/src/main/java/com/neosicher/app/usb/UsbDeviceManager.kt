package com.neosicher.app.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Capa de acceso a Android USB Host, independiente de cualquier protocolo.
 *
 * Responsabilidades (y SOLO estas, según docs/GW192A_INVESTIGACION.md §4 y §10):
 *   1. Detectar dispositivos USB conectados.
 *   2. Comprobar VID/PID contra el GW192A (0x37B4 / 0x0102).
 *   3. Solicitar el permiso USB (independiente del permiso de cámara Android).
 *   4. Abrir la UsbDeviceConnection.
 *   5. Enumerar configuraciones, interfaces, alternate settings y endpoints.
 *   6. Registrar (log) toda la información encontrada para la investigación.
 *
 * NO implementa ningún protocolo de streaming, ni control transfers, ni comandos.
 * NO asume UVC, YUYV, MJPEG, RAW16, ni ninguna estructura de frame.
 */
class UsbDeviceManager(private val appContext: Context) {

    private val usbManager: UsbManager? =
        appContext.getSystemService(Context.USB_SERVICE) as? UsbManager

    private val _events = MutableStateFlow<UsbEvent>(UsbEvent.Idle)
    val events: StateFlow<UsbEvent> = _events.asStateFlow()

    /** true si el dispositivo/entorno declara soporte de USB Host. */
    val isUsbHostAvailable: Boolean
        get() = usbManager != null &&
            appContext.packageManager.hasSystemFeature("android.hardware.usb.host")

    private var permissionReceiver: BroadcastReceiver? = null

    // -- Detección ----------------------------------------------------------

    /**
     * Busca un GW192A entre los dispositivos actualmente conectados.
     * Devuelve el [UsbDevice] si VID/PID coinciden, o null.
     *
     * No abre nada ni solicita permiso: solo detecta.
     */
    fun findGw192a(): UsbDevice? {
        val manager = usbManager ?: return null
        return manager.deviceList.values.firstOrNull { device ->
            device.vendorId == UsbDeviceInfo.GW192A_VENDOR_ID &&
                device.productId == UsbDeviceInfo.GW192A_PRODUCT_ID
        }
    }

    /** true si ya tenemos permiso concedido para [device]. */
    fun hasPermission(device: UsbDevice): Boolean =
        usbManager?.hasPermission(device) == true

    // -- Permiso ------------------------------------------------------------

    /**
     * Solicita el permiso USB para [device]. El resultado llega por [events]
     * como [UsbEvent.PermissionResult]. Es un permiso distinto del de cámara.
     */
    fun requestPermission(device: UsbDevice) {
        val manager = usbManager ?: run {
            _events.value = UsbEvent.Error("USB no disponible en este dispositivo")
            return
        }

        registerPermissionReceiverIfNeeded()

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val intent = PendingIntent.getBroadcast(
            appContext,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName),
            flags,
        )
        manager.requestPermission(device, intent)
    }

    private fun registerPermissionReceiverIfNeeded() {
        if (permissionReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                Log.i(TAG, "Permiso USB resultado=$granted para ${device?.deviceName}")
                _events.value = UsbEvent.PermissionResult(device, granted)
            }
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
        permissionReceiver = receiver
    }

    fun release() {
        permissionReceiver?.let {
            runCatching { appContext.unregisterReceiver(it) }
        }
        permissionReceiver = null
    }

    // -- Apertura + enumeración --------------------------------------------

    /**
     * Abre la conexión con [device] (requiere permiso previo) y enumera TODA la
     * estructura USB: configuraciones, interfaces (con alternate settings) y
     * endpoints (dirección, tipo, maxPacketSize, interval).
     *
     * La conexión se cierra al terminar la enumeración: en este MVP solo
     * investigamos descriptors, no mantenemos ningún stream abierto.
     *
     * @return [UsbDeviceInfo] real, o null si no se pudo abrir.
     */
    fun openAndEnumerate(device: UsbDevice): UsbDeviceInfo? {
        val manager = usbManager ?: return null
        if (!manager.hasPermission(device)) {
            Log.w(TAG, "openAndEnumerate sin permiso para ${device.deviceName}")
            _events.value = UsbEvent.Error("Permiso USB requerido")
            return null
        }

        var connection: UsbDeviceConnection? = null
        return try {
            connection = manager.openDevice(device)
            if (connection == null) {
                _events.value = UsbEvent.Error("No se pudo abrir la conexión USB")
                return null
            }
            val info = buildDeviceInfo(device, connection)
            logDeviceInfo(info)
            info
        } catch (t: Throwable) {
            Log.e(TAG, "Error enumerando dispositivo USB", t)
            _events.value = UsbEvent.Error(t.message ?: "Error de enumeración USB")
            null
        } finally {
            connection?.close()
        }
    }

    /**
     * Lee los descriptors de configuración **crudos** del dispositivo y los
     * analiza en busca de descriptors class-specific de UVC (VideoControl /
     * VideoStreaming, formatos y resoluciones declaradas).
     *
     * Es de solo lectura: abre la conexión únicamente para leer
     * `getRawDescriptors()`, sin reclamar interfaces ni iniciar transferencias.
     * No confirma que el GW192A "sea" UVC ni que vaya a poder transmitir un
     * stream: solo reporta qué declaran sus propios descriptors.
     *
     * @return [UvcParseResult], o null si no se pudo leer (sin permiso, sin
     *   conexión, o error de E/S).
     */
    fun readUvcDescriptors(device: UsbDevice): UvcParseResult? {
        val manager = usbManager ?: return null
        if (!manager.hasPermission(device)) {
            Log.w(TAG, "readUvcDescriptors sin permiso para ${device.deviceName}")
            return null
        }

        var connection: UsbDeviceConnection? = null
        return try {
            connection = manager.openDevice(device) ?: return null
            val raw = connection.rawDescriptors
            if (raw == null || raw.isEmpty()) {
                Log.w(TAG, "getRawDescriptors() devolvió vacío/null")
                return null
            }
            val result = UvcDescriptorParser.parse(raw)
            logUvcResult(result)
            result
        } catch (t: Throwable) {
            Log.e(TAG, "Error leyendo descriptors crudos UVC", t)
            null
        } finally {
            connection?.close()
        }
    }

    /**
     * Abre una [UsbDeviceConnection] persistente para [device] y la deja
     * abierta (a diferencia de [openAndEnumerate] / [readUvcDescriptors], que
     * la cierran tras leer). El llamador es responsable de cerrarla cuando
     * termine (p. ej. al detener un [UvcStreamingSession]).
     *
     * @return la conexión abierta, o null si no hay permiso o falla la apertura.
     */
    fun openPersistentConnection(device: UsbDevice): UsbDeviceConnection? {
        val manager = usbManager ?: return null
        if (!manager.hasPermission(device)) {
            Log.w(TAG, "openPersistentConnection sin permiso para ${device.deviceName}")
            return null
        }
        return try {
            manager.openDevice(device)
        } catch (t: Throwable) {
            Log.e(TAG, "Error abriendo conexión persistente", t)
            null
        }
    }

    private fun logUvcResult(result: UvcParseResult) {
        Log.i(TAG, "== Parseo UVC (bytes crudos, ${result.rawDescriptorTotalBytes}B) ==")
        result.parseLog.forEach { Log.i(TAG, "  $it") }
        Log.i(TAG, "VideoControl (VC_HEADER) encontrado=${result.videoControlInterfaceFound}")
        result.streamingFormats.forEach { fmt ->
            Log.i(TAG, "  Formato ${fmt.kind} idx=${fmt.formatIndex} fourCC=${fmt.fourCc} " +
                "guid=${fmt.guidHex} bpp=${fmt.bitsPerPixel}")
            fmt.frames.forEach { fr ->
                Log.i(TAG, "    Frame idx=${fr.frameIndex} ${fr.widthPx}x${fr.heightPx} " +
                    "~${"%.1f".format(fr.approxFps)}fps")
            }
        }
        val doubleHeight = result.doubleHeightCandidatesWithFormat
        if (doubleHeight.isNotEmpty()) {
            Log.i(TAG, "HIPÓTESIS (no confirmada, descartada para GW192A por evidencia pública — " +
                "ver docs §13.5): ${doubleHeight.size} frame(s) con altura = 2x ancho.")
        }
        val chosen = result.recommendedFrame
        if (chosen != null) {
            val (fmt, fr) = chosen
            Log.i(TAG, "Frame recomendado: fourCC=${fmt.fourCc} ${fr.widthPx}x${fr.heightPx} " +
                "formatIdx=${fmt.formatIndex} frameIdx=${fr.frameIndex}")
        } else {
            Log.i(TAG, "Ningún frame declarado para recomendar.")
        }
    }

    /**
     * Construye [UsbDeviceInfo] leyendo descriptors reales. Los nombres de string
     * (manufacturer/product/serial) requieren la conexión abierta para resolverse.
     */
    private fun buildDeviceInfo(
        device: UsbDevice,
        connection: UsbDeviceConnection,
    ): UsbDeviceInfo {
        val configurations = (0 until device.configurationCount).map { cfgIndex ->
            val cfg = device.getConfiguration(cfgIndex)
            val interfaces = (0 until cfg.interfaceCount).map { ifIndex ->
                val iface = cfg.getInterface(ifIndex)
                val endpoints = (0 until iface.endpointCount).map { epIndex ->
                    val ep = iface.getEndpoint(epIndex)
                    UsbEndpointInfo(
                        address = ep.address,
                        endpointNumber = ep.endpointNumber,
                        direction = when (ep.direction) {
                            UsbConstants.USB_DIR_IN -> UsbEndpointDirection.IN
                            else -> UsbEndpointDirection.OUT
                        },
                        type = mapTransferType(ep.type),
                        maxPacketSize = ep.maxPacketSize,
                        interval = ep.interval,
                    )
                }
                UsbInterfaceInfo(
                    id = iface.id,
                    alternateSetting = iface.alternateSetting,
                    interfaceClass = iface.interfaceClass,
                    interfaceSubclass = iface.interfaceSubclass,
                    interfaceProtocol = iface.interfaceProtocol,
                    name = iface.name,
                    endpoints = endpoints,
                )
            }
            UsbConfigurationInfo(
                id = cfg.id,
                name = cfg.name,
                maxPowerMilliAmps = cfg.maxPower * 2, // maxPower se expresa en unidades de 2 mA
                isSelfPowered = cfg.isSelfPowered,
                interfaces = interfaces,
            )
        }

        return UsbDeviceInfo(
            deviceName = device.deviceName,
            vendorId = device.vendorId,
            productId = device.productId,
            deviceClass = device.deviceClass,
            deviceSubclass = device.deviceSubclass,
            deviceProtocol = device.deviceProtocol,
            manufacturerName = runCatching { device.manufacturerName }.getOrNull(),
            productName = runCatching { device.productName }.getOrNull(),
            serialNumber = runCatching { device.serialNumber }.getOrNull(),
            configurations = configurations,
        )
    }

    private fun mapTransferType(type: Int): UsbTransferType = when (type) {
        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> UsbTransferType.CONTROL
        UsbConstants.USB_ENDPOINT_XFER_ISOC -> UsbTransferType.ISOCHRONOUS
        UsbConstants.USB_ENDPOINT_XFER_BULK -> UsbTransferType.BULK
        UsbConstants.USB_ENDPOINT_XFER_INT -> UsbTransferType.INTERRUPT
        else -> UsbTransferType.UNKNOWN
    }

    private fun logDeviceInfo(info: UsbDeviceInfo) {
        Log.i(TAG, "== USB device: ${info.deviceName} ==")
        Log.i(TAG, "VID=${info.vendorIdHex} PID=${info.productIdHex} " +
            "class=${info.deviceClass} subclass=${info.deviceSubclass} protocol=${info.deviceProtocol}")
        Log.i(TAG, "manufacturer=${info.manufacturerName} product=${info.productName} serial=${info.serialNumber}")
        Log.i(TAG, "matchesGW192A=${info.matchesGw192a}")
        info.configurations.forEach { cfg ->
            Log.i(TAG, "  config #${cfg.id} maxPower=${cfg.maxPowerMilliAmps}mA selfPowered=${cfg.isSelfPowered}")
            cfg.interfaces.forEach { iface ->
                Log.i(TAG, "    interface #${iface.id} alt=${iface.alternateSetting} " +
                    "class=${iface.interfaceClass} subclass=${iface.interfaceSubclass} protocol=${iface.interfaceProtocol}")
                iface.endpoints.forEach { ep ->
                    Log.i(TAG, "      endpoint ${ep.addressHex} dir=${ep.direction} type=${ep.type} " +
                        "maxPacket=${ep.maxPacketSize} interval=${ep.interval}")
                }
            }
        }
        // NOTA: a partir de aquí NO se interpreta nada. Determinar protocolo,
        // stream y datos térmicos REQUIERE PRUEBA EN POCO F7 + GW192A.
    }

    companion object {
        private const val TAG = "NeoUsb"
        const val ACTION_USB_PERMISSION = "com.neosicher.app.USB_PERMISSION"
    }
}

/** Eventos emitidos por [UsbDeviceManager]. */
sealed interface UsbEvent {
    data object Idle : UsbEvent
    data class PermissionResult(val device: UsbDevice?, val granted: Boolean) : UsbEvent
    data class Error(val message: String) : UsbEvent
}
