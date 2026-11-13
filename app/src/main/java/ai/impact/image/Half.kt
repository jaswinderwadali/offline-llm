package ai.impact.image

/**
 * IEEE 754 half-precision conversion, for models whose inputs or outputs are float16.
 * (android.util.Half needs API 26; the app supports 24.)
 */
internal object Half {

    fun toFloat(bits: Short): Float {
        val h = bits.toInt() and 0xFFFF
        val sign = (h ushr 15) and 0x1
        val exponent = (h ushr 10) and 0x1F
        val mantissa = h and 0x3FF
        val out = when (exponent) {
            0 -> {
                if (mantissa == 0) {
                    sign shl 31
                } else {
                    // Subnormal: normalise it.
                    var e = -1
                    var m = mantissa
                    do {
                        e++
                        m = m shl 1
                    } while (m and 0x400 == 0)
                    (sign shl 31) or ((127 - 15 - e) shl 23) or ((m and 0x3FF) shl 13)
                }
            }
            0x1F -> (sign shl 31) or (0xFF shl 23) or (mantissa shl 13)
            else -> (sign shl 31) or ((exponent - 15 + 127) shl 23) or (mantissa shl 13)
        }
        return java.lang.Float.intBitsToFloat(out)
    }

    /** Round-to-nearest-even conversion, saturating to infinity. */
    fun fromFloat(value: Float): Short {
        val f = java.lang.Float.floatToRawIntBits(value)
        val sign = (f ushr 16) and 0x8000
        val exponent = (f ushr 23) and 0xFF
        var mantissa = f and 0x7FFFFF

        if (exponent == 0xFF) {
            // Inf or NaN (keep NaN quiet).
            return (sign or 0x7C00 or (if (mantissa != 0) 0x200 else 0)).toShort()
        }
        val e = exponent - 127 + 15
        if (e >= 0x1F) return (sign or 0x7C00).toShort()
        if (e <= 0) {
            if (e < -10) return sign.toShort()
            mantissa = mantissa or 0x800000
            val shift = 14 - e
            var half = mantissa ushr shift
            val remainder = mantissa and ((1 shl shift) - 1)
            val midpoint = 1 shl (shift - 1)
            if (remainder > midpoint || (remainder == midpoint && (half and 1) == 1)) half++
            return (sign or half).toShort()
        }
        var half = (e shl 10) or (mantissa ushr 13)
        val remainder = mantissa and 0x1FFF
        if (remainder > 0x1000 || (remainder == 0x1000 && (half and 1) == 1)) half++
        return (sign or half).toShort()
    }
}
