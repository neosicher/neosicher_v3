package com.neosicher.app.usb

import android.graphics.Bitmap

/**
 * Decodificador de **YUYV (YUY2) a RGB**.
 *
 * YUYV es un formato de píxel **estándar público** (FourCC "YUY2"/"YUYV",
 * conversión YCbCr→RGB según ITU-R BT.601) usado por miles de cámaras UVC de
 * cualquier fabricante. No es específico del GW192A ni proviene de THG Start:
 * es la misma conversión matemática publicada en la especificación de
 * FourCC.org y en cualquier libro de procesamiento de vídeo. Se usa aquí
 * únicamente para dibujar la mitad "imagen visible" de un frame, cuando el
 * formato declarado por el dispositivo (ver [UvcFormatDescriptor.guidHex])
 * corresponde a YUY2.
 */
object YuyvDecoder {

    /**
     * Convierte un bloque YUYV de `width x height` píxeles a un [Bitmap] ARGB_8888.
     * Si [bytes] no tiene el tamaño esperado (width*height*2), decodifica lo
     * que pueda y deja el resto en negro, sin lanzar excepción.
     */
    fun decode(bytes: ByteArray, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        val expected = width * height * 2
        val limit = minOf(bytes.size, expected)

        var pixelIndex = 0
        var i = 0
        while (i + 4 <= limit && pixelIndex + 2 <= pixels.size) {
            val y0 = bytes[i].toIntUnsigned()
            val u = bytes[i + 1].toIntUnsigned() - 128
            val y1 = bytes[i + 2].toIntUnsigned()
            val v = bytes[i + 3].toIntUnsigned() - 128

            pixels[pixelIndex] = yuvToRgb(y0, u, v)
            pixels[pixelIndex + 1] = yuvToRgb(y1, u, v)

            pixelIndex += 2
            i += 4
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun yuvToRgb(y: Int, u: Int, v: Int): Int {
        // Conversión estándar ITU-R BT.601 (YCbCr -> RGB), pública y de uso general.
        val r = (y + 1.402 * v).toInt().coerceIn(0, 255)
        val g = (y - 0.344136 * u - 0.714136 * v).toInt().coerceIn(0, 255)
        val b = (y + 1.772 * u).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun Byte.toIntUnsigned(): Int = this.toInt() and 0xFF
}
