package com.rembrowser.rembrowse.engine

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Icon files made the way sites make theirs, for the tests: `.ico` files holding BMP images
 * (16-colour or 32-bit, each with its transparency mask) and PNG images.
 */
internal object TestIcons {

    /** A [side]-pixel PNG filled with [color]. */
    fun png(side: Int, color: Int = 0xFF8A1538.toInt()): ByteArray {
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /**
     * The BMP image an `.ico` holds for a [side]-pixel icon: a BITMAPINFOHEADER with twice the
     * height (the colours, then the 1-bit mask), the 16-colour palette for [bitsPerPixel] 4,
     * the colour rows and the mask rows, each row padded to 4 bytes.
     */
    fun bmpEntry(side: Int, bitsPerPixel: Int): ByteArray {
        require(bitsPerPixel == 4 || bitsPerPixel == 32)
        val paletteColors = if (bitsPerPixel == 4) 16 else 0
        val colorRow = padded(side * bitsPerPixel / 8)
        val maskRow = padded((side + 7) / 8)
        val imageSize = (colorRow + maskRow) * side
        val buffer = ByteBuffer.allocate(40 + paletteColors * 4 + imageSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(40).putInt(side).putInt(side * 2).putShort(1).putShort(bitsPerPixel.toShort())
            .putInt(0).putInt(imageSize).putInt(0).putInt(0).putInt(paletteColors).putInt(0)
        // A palette of greys (blue, green, red, unused)
        repeat(paletteColors) { i -> val v = (i * 17).toByte(); buffer.put(v).put(v).put(v).put(0) }
        repeat(side) { row ->
            val start = buffer.position()
            repeat(side) { column ->
                if (bitsPerPixel == 32) {
                    buffer.put(0x38).put(0x15).put(0x8A.toByte()).put(0xFF.toByte())
                } else if (column % 2 == 0) {
                    // Two pixels a byte: a pattern of palette entries
                    buffer.put((((row + column) % 16) shl 4 or ((row + column + 1) % 16)).toByte())
                }
            }
            buffer.position(start + colorRow)
        }
        // An all-zero mask: every pixel shown
        buffer.position(buffer.position() + maskRow * side)
        return buffer.array()
    }

    /** An `.ico` file of [images], each a [side] (256 is written as 0) and its BMP or PNG bytes. */
    fun ico(images: List<Pair<Int, ByteArray>>, bitsPerPixel: Int = 32): ByteArray {
        val headerSize = 6 + 16 * images.size
        val buffer = ByteBuffer.allocate(headerSize + images.sumOf { it.second.size }).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(0).putShort(1).putShort(images.size.toShort())
        var offset = headerSize
        for ((side, bytes) in images) {
            val written = if (side >= 256) 0 else side
            buffer.put(written.toByte()).put(written.toByte())
                .put((if (bitsPerPixel == 4) 16 else 0).toByte()).put(0)
                .putShort(1).putShort(bitsPerPixel.toShort())
                .putInt(bytes.size).putInt(offset)
            offset += bytes.size
        }
        images.forEach { buffer.put(it.second) }
        return buffer.array()
    }

    /**
     * Shaped like en.wikipedia.org's `/favicon.ico` (2,734 bytes): three 16-colour BMP images,
     * 48, 32 and 16 pixels, largest first.
     */
    fun wikipediaLikeIco(): ByteArray = ico(listOf(48, 32, 16).map { it to bmpEntry(it, 4) }, bitsPerPixel = 4)

    /**
     * Shaped like pump.fun's `/favicon.ico` (182,941 bytes): eight 32-bit BMP images from 16 to
     * 128 pixels, smallest first, and a 256-pixel PNG at the end.
     */
    fun pumpFunLikeIco(): ByteArray =
        ico(listOf(16, 24, 32, 48, 64, 72, 96, 128).map { it to bmpEntry(it, 32) } + (256 to png(256)))

    private fun padded(bytes: Int) = (bytes + 3) / 4 * 4
}
