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
     * Extrae el plano de luminancia (Y) de un frame **NV12** (YUV 4:2:0
     * semi-planar: primero `width*height` bytes de luma, luego el plano
     * entrelazado de croma U/V que aquí se descarta). Es el formato más
     * simple y limpio que expone el GW192A según evidencia pública (ver
     * docs/GW192A_INVESTIGACION.md §13.5): no requiere combinar croma, por lo
     * que es el candidato más fiable para una visualización en escala de
     * grises / paleta de calor relativa.
     */
    fun extractLumaNv12(bytes: ByteArray, width: Int, height: Int): IntArray {
        val count = width * height
        val luma = IntArray(count)
        val limit = minOf(bytes.size, count)
        for (i in 0 until limit) {
            luma[i] = bytes[i].toIntUnsigned()
        }
        return luma
    }

    /**
     * Extrae solo el canal de luminancia (Y) de un frame YUYV/YUY2 (4:2:2
     * empaquetado: Y0 U Y1 V por cada 2 píxeles), ignorando el croma U/V.
     * Útil cuando la imagen de color no aporta información térmica real
     * (ver evidencia en docs/GW192A_INVESTIGACION.md §13.5: el stream YUYV
     * del GW192A solo entrega un tinte verde de alto contraste, sin color
     * real útil).
     */
    fun extractLumaYuyv(bytes: ByteArray, width: Int, height: Int): IntArray {
        val count = width * height
        val luma = IntArray(count)
        var pixelIndex = 0
        var i = 0
        val limit = minOf(bytes.size, count * 2)
        while (i + 4 <= limit && pixelIndex + 2 <= count) {
            luma[pixelIndex] = bytes[i].toIntUnsigned()
            luma[pixelIndex + 1] = bytes[i + 2].toIntUnsigned()
            pixelIndex += 2
            i += 4
        }
        return luma
    }

    /**
     * Convierte un array de luminancia (0..255 por píxel) en un [Bitmap]
     * aplicando una paleta de calor **relativa** (normalizada min–max dentro
     * del propio frame). No representa temperatura en °C: es una ayuda visual
     * de contraste, igual que la que ya usan herramientas públicas de
     * terceros para este mismo hardware (colormap sobre escala de grises).
     */
    fun lumaToHeatmap(luma: IntArray, width: Int, height: Int): Bitmap {
        var min = 255
        var max = 0
        for (v in luma) {
            if (v < min) min = v
            if (v > max) max = v
        }
        val range = (max - min).coerceAtLeast(1)
        val pixels = IntArray(luma.size)
        for (i in luma.indices) {
            val t = (luma[i] - min).toFloat() / range
            pixels[i] = heatColor(t)
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    /** Gradiente azul→rojo simple (t en [0,1]). Solo visualización relativa. */
    private fun heatColor(t: Float): Int {
        val clamped = t.coerceIn(0f, 1f)
        val r = (clamped * 255).toInt()
        val b = ((1f - clamped) * 255).toInt()
        val g = (255 - kotlin.math.abs(clamped - 0.5f) * 2 * 255).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }


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
