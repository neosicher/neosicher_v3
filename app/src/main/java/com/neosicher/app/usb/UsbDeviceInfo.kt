package com.neosicher.app.usb

/**
 * Información USB REAL leída de un dispositivo conectado.
 *
 * Todos los campos provienen de la enumeración de bajo nivel de Android USB Host
 * (UsbDevice / UsbConfiguration / UsbInterface / UsbEndpoint). No se inventa nada:
 * si un dato no está disponible, se deja null o vacío.
 *
 * Este modelo alimenta la pantalla de diagnóstico y la futura investigación del
 * protocolo del GW192A (ver docs/GW192A_INVESTIGACION.md).
 */
data class UsbDeviceInfo(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val deviceClass: Int,
    val deviceSubclass: Int,
    val deviceProtocol: Int,
    val manufacturerName: String?,
    val productName: String?,
    val serialNumber: String?,
    val configurations: List<UsbConfigurationInfo> = emptyList(),
) {
    /** VID en formato hexadecimal (p. ej. "0x37B4"). */
    val vendorIdHex: String get() = "0x%04X".format(vendorId)

    /** PID en formato hexadecimal (p. ej. "0x0102"). */
    val productIdHex: String get() = "0x%04X".format(productId)

    /**
     * true si VID/PID coinciden con el GW192A observado (evidencia CONFIRMADA:
     * VID=0x37B4, PID=0x0102). No implica nada sobre el protocolo.
     */
    val matchesGw192a: Boolean
        get() = vendorId == GW192A_VENDOR_ID && productId == GW192A_PRODUCT_ID

    companion object {
        const val GW192A_VENDOR_ID = 0x37B4   // 14260
        const val GW192A_PRODUCT_ID = 0x0102  // 258
    }
}

/** Una configuración USB del dispositivo. */
data class UsbConfigurationInfo(
    val id: Int,
    val name: String?,
    val maxPowerMilliAmps: Int,
    val isSelfPowered: Boolean,
    val interfaces: List<UsbInterfaceInfo> = emptyList(),
)

/** Una interfaz USB (con su alternate setting) del dispositivo. */
data class UsbInterfaceInfo(
    val id: Int,
    val alternateSetting: Int,
    val interfaceClass: Int,
    val interfaceSubclass: Int,
    val interfaceProtocol: Int,
    val name: String?,
    val endpoints: List<UsbEndpointInfo> = emptyList(),
)

/** Dirección de un endpoint USB. */
enum class UsbEndpointDirection { IN, OUT }

/**
 * Tipo de transferencia de un endpoint USB.
 * Se mapea desde UsbConstants.USB_ENDPOINT_XFER_*.
 */
enum class UsbTransferType { CONTROL, ISOCHRONOUS, BULK, INTERRUPT, UNKNOWN }

/** Un endpoint USB del dispositivo. */
data class UsbEndpointInfo(
    val address: Int,
    val endpointNumber: Int,
    val direction: UsbEndpointDirection,
    val type: UsbTransferType,
    val maxPacketSize: Int,
    val interval: Int,
) {
    val addressHex: String get() = "0x%02X".format(address)
}
