package com.dastyab.app

/**
 * QrCodeEncoder — Pure-Kotlin ISO/IEC 18004 Byte-mode QR Code matrix encoder.
 *
 * Supports QR Versions 1 through 15 at Error Correction Level L (up to 520 UTF-8 bytes),
 * covering virtually all `vless://`, `trojan://`, `hy2://`, and subscription URIs offline
 * with zero external Gradle dependencies.
 */
object QrCodeEncoder {

    data class QrMatrix(
        val version: Int,
        val size: Int,
        val modules: BooleanArray
    ) {
        fun get(x: Int, y: Int): Boolean = modules[y * size + x]

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is QrMatrix) return false
            return version == other.version && size == other.size && modules.contentEquals(other.modules)
        }

        override fun hashCode(): Int {
            var result = version
            result = 31 * result + size
            result = 31 * result + modules.contentHashCode()
            return result
        }
    }

    private data class VersionSpec(
        val version: Int,
        val totalCodewords: Int,
        val ecCodewordsPerBlock: Int,
        val numBlocksG1: Int,
        val dataCwG1: Int,
        val numBlocksG2: Int = 0,
        val dataCwG2: Int = 0
    ) {
        val totalDataCodewords: Int get() = numBlocksG1 * dataCwG1 + numBlocksG2 * dataCwG2
        val byteCapacity: Int
            get() {
                val countBits = if (version <= 9) 8 else 16
                val headerBits = 4 + countBits
                return ((totalDataCodewords * 8) - headerBits) / 8
            }
    }

    // ISO/IEC 18004 Table 9 — Error Correction Level L for Versions 1..15
    private val SPECS_L = listOf(
        VersionSpec(1, 26, 7, 1, 19),
        VersionSpec(2, 44, 10, 1, 34),
        VersionSpec(3, 70, 15, 1, 55),
        VersionSpec(4, 100, 20, 1, 80),
        VersionSpec(5, 134, 26, 1, 108),
        VersionSpec(6, 172, 18, 2, 68),
        VersionSpec(7, 196, 20, 2, 78),
        VersionSpec(8, 242, 24, 2, 97),
        VersionSpec(9, 292, 30, 2, 116),
        VersionSpec(10, 346, 18, 2, 68, 2, 69),
        VersionSpec(11, 404, 20, 4, 81),
        VersionSpec(12, 466, 24, 2, 92, 2, 93),
        VersionSpec(13, 532, 26, 4, 107),
        VersionSpec(14, 581, 30, 3, 115, 1, 116),
        VersionSpec(15, 655, 22, 5, 87, 1, 88)
    )

    // Alignment pattern center coordinates for Versions 1..15
    private val ALIGNMENT_POSITIONS = arrayOf(
        intArrayOf(),                    // V1
        intArrayOf(6, 18),               // V2
        intArrayOf(6, 22),               // V3
        intArrayOf(6, 26),               // V4
        intArrayOf(6, 30),               // V5
        intArrayOf(6, 34),               // V6
        intArrayOf(6, 22, 38),           // V7
        intArrayOf(6, 24, 42),           // V8
        intArrayOf(6, 26, 46),           // V9
        intArrayOf(6, 28, 50),           // V10
        intArrayOf(6, 30, 54),           // V11
        intArrayOf(6, 32, 58),           // V12
        intArrayOf(6, 34, 62),           // V13
        intArrayOf(6, 26, 46, 66),       // V14
        intArrayOf(6, 26, 48, 70)        // V15
    )

    // Precomputed GF(256) exp/log tables with primitive polynomial 0x11D
    private val gfExp = IntArray(512)
    private val gfLog = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            gfExp[i] = x
            gfLog[x] = i
            x = x shl 1
            if ((x and 0x100) != 0) x = x xor 0x11D
        }
        for (i in 255 until 512) {
            gfExp[i] = gfExp[i - 255]
        }
    }

    /**
     * Encodes [text] into a scannable ISO/IEC 18004 QR Code matrix.
     * Returns null if [text] is empty or exceeds Version 15 Level-L capacity (520 bytes).
     */
    fun encode(text: String): QrMatrix? {
        if (text.isEmpty()) return null
        val payload = text.toByteArray(Charsets.UTF_8)
        val spec = SPECS_L.firstOrNull { payload.size <= it.byteCapacity } ?: return null

        val dataCodewords = buildDataCodewords(payload, spec)
        val interleaved = interleaveBlocksAndEc(dataCodewords, spec)

        val size = spec.version * 4 + 17
        val baseModules = BooleanArray(size * size)
        val isFunction = BooleanArray(size * size)

        drawFunctionPatterns(spec.version, size, baseModules, isFunction)
        placeDataBits(size, baseModules, isFunction, interleaved)

        // Evaluate all 8 mask patterns and pick the lowest penalty
        var bestMask = 0
        var bestPenalty = Int.MAX_VALUE
        var bestModules = baseModules
        for (mask in 0 until 8) {
            val candidate = baseModules.copyOf()
            applyMask(size, candidate, isFunction, mask)
            drawFormatBits(size, candidate, mask)
            val penalty = computePenalty(size, candidate)
            if (penalty < bestPenalty) {
                bestPenalty = penalty
                bestMask = mask
                bestModules = candidate
            }
        }
        // Keep bestMask referenced cleanly
        if (bestMask < 0) return null
        return QrMatrix(spec.version, size, bestModules)
    }

    private fun buildDataCodewords(payload: ByteArray, spec: VersionSpec): ByteArray {
        val bits = ArrayList<Boolean>(spec.totalDataCodewords * 8)
        fun appendBits(value: Int, count: Int) {
            for (i in count - 1 downTo 0) {
                bits.add(((value ushr i) and 1) != 0)
            }
        }
        // Mode indicator: Byte mode = 0100
        appendBits(0b0100, 4)
        // Character count indicator (8 bits for V1..V9, 16 bits for V10..V15)
        appendBits(payload.size, if (spec.version <= 9) 8 else 16)
        // Payload bytes
        for (b in payload) {
            appendBits(b.toInt() and 0xFF, 8)
        }
        val capacityBits = spec.totalDataCodewords * 8
        // Terminator (up to 4 zero bits)
        val termLen = minOf(4, capacityBits - bits.size)
        appendBits(0, termLen)
        // Pad to byte boundary
        while (bits.size % 8 != 0) bits.add(false)
        // Pad codewords 0xEC, 0x11 alternating
        var padToggle = false
        while (bits.size < capacityBits) {
            appendBits(if (padToggle) 0x11 else 0xEC, 8)
            padToggle = !padToggle
        }
        val out = ByteArray(spec.totalDataCodewords)
        for (i in out.indices) {
            var v = 0
            for (b in 0 until 8) {
                v = (v shl 1) or if (bits[i * 8 + b]) 1 else 0
            }
            out[i] = v.toByte()
        }
        return out
    }

    private fun interleaveBlocksAndEc(data: ByteArray, spec: VersionSpec): ByteArray {
        val dataBlocks = ArrayList<ByteArray>()
        val ecBlocks = ArrayList<ByteArray>()
        val gen = reedSolomonGenerator(spec.ecCodewordsPerBlock)

        var offset = 0
        for (i in 0 until spec.numBlocksG1) {
            val block = data.copyOfRange(offset, offset + spec.dataCwG1)
            offset += spec.dataCwG1
            dataBlocks.add(block)
            ecBlocks.add(reedSolomonRemainder(block, gen))
        }
        for (i in 0 until spec.numBlocksG2) {
            val block = data.copyOfRange(offset, offset + spec.dataCwG2)
            offset += spec.dataCwG2
            dataBlocks.add(block)
            ecBlocks.add(reedSolomonRemainder(block, gen))
        }

        val result = ByteArray(spec.totalCodewords)
        var pos = 0
        val maxDataLen = maxOf(spec.dataCwG1, spec.dataCwG2)
        for (i in 0 until maxDataLen) {
            for (b in dataBlocks) {
                if (i < b.size) result[pos++] = b[i]
            }
        }
        for (i in 0 until spec.ecCodewordsPerBlock) {
            for (ec in ecBlocks) {
                result[pos++] = ec[i]
            }
        }
        return result
    }

    private fun gfMul(a: Int, b: Int): Int {
        if (a == 0 || b == 0) return 0
        return gfExp[gfLog[a] + gfLog[b]]
    }

    private fun reedSolomonGenerator(degree: Int): IntArray {
        var poly = intArrayOf(1)
        for (i in 0 until degree) {
            val next = IntArray(poly.size + 1)
            val factor = gfExp[i]
            for (j in poly.indices) {
                next[j] = next[j] xor poly[j]
                next[j + 1] = next[j + 1] xor gfMul(poly[j], factor)
            }
            poly = next
        }
        return poly
    }

    private fun reedSolomonRemainder(data: ByteArray, generator: IntArray): ByteArray {
        val ecLen = generator.size - 1
        val rem = IntArray(ecLen)
        for (b in data) {
            val factor = (b.toInt() and 0xFF) xor rem[0]
            System.arraycopy(rem, 1, rem, 0, ecLen - 1)
            rem[ecLen - 1] = 0
            for (j in 0 until ecLen) {
                rem[j] = rem[j] xor gfMul(generator[j + 1], factor)
            }
        }
        return ByteArray(ecLen) { rem[it].toByte() }
    }

    private fun drawFunctionPatterns(
        version: Int,
        size: Int,
        modules: BooleanArray,
        isFunction: BooleanArray
    ) {
        fun setFunc(x: Int, y: Int, dark: Boolean) {
            if (x in 0 until size && y in 0 until size) {
                modules[y * size + x] = dark
                isFunction[y * size + x] = true
            }
        }

        // Finder patterns + 1-module white separator
        fun drawFinder(cx: Int, cy: Int) {
            for (dy in -4..4) {
                for (dx in -4..4) {
                    val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                    val dark = dist != 2 && dist != 4
                    setFunc(cx + dx, cy + dy, dark)
                }
            }
        }
        drawFinder(3, 3)
        drawFinder(size - 4, 3)
        drawFinder(3, size - 4)

        // Timing patterns
        for (i in 8 until size - 8) {
            setFunc(i, 6, i % 2 == 0)
            setFunc(6, i, i % 2 == 0)
        }

        // Alignment patterns
        val alignCoords = ALIGNMENT_POSITIONS[version - 1]
        for (cy in alignCoords) {
            for (cx in alignCoords) {
                // Skip corners occupied by finder patterns
                if ((cx <= 8 && cy <= 8) || (cx >= size - 9 && cy <= 8) || (cx <= 8 && cy >= size - 9)) {
                    continue
                }
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                        setFunc(cx + dx, cy + dy, dist != 1)
                    }
                }
            }
        }

        // Reserve format info areas + dark module
        for (i in 0..8) {
            if (i != 6) {
                setFunc(8, i, false)
                setFunc(i, 8, false)
            }
        }
        for (i in 0 until 8) {
            setFunc(size - 1 - i, 8, false)
        }
        for (i in 0 until 7) {
            setFunc(8, size - 1 - i, false)
        }
        setFunc(8, size - 8, true) // Dark module

        // Version information (Versions 7..15)
        if (version >= 7) {
            val bits = computeVersionBits(version)
            for (i in 0 until 18) {
                val dark = ((bits ushr i) and 1) != 0
                val a = size - 11 + (i % 3)
                val b = i / 3
                setFunc(a, b, dark)
                setFunc(b, a, dark)
            }
        }
    }

    private fun computeVersionBits(version: Int): Int {
        var rem = version
        for (i in 0 until 12) {
            rem = (rem shl 1) xor (((rem ushr 11) and 1) * 0x1F25)
        }
        return (version shl 12) or rem
    }

    private fun drawFormatBits(size: Int, modules: BooleanArray, mask: Int) {
        // Error correction level L = 01
        val data = (0b01 shl 3) or mask
        var rem = data
        for (i in 0 until 10) {
            rem = (rem shl 1) xor (((rem ushr 9) and 1) * 0x537)
        }
        val bits = ((data shl 10) or rem) xor 0x5412

        fun bit(i: Int): Boolean = ((bits ushr i) and 1) != 0

        for (i in 0..5) modules[i * size + 8] = bit(i)
        modules[7 * size + 8] = bit(6)
        modules[8 * size + 8] = bit(7)
        modules[8 * size + 7] = bit(8)
        for (i in 9..14) modules[8 * size + (14 - i)] = bit(i)

        for (i in 0..7) modules[8 * size + (size - 1 - i)] = bit(i)
        for (i in 8..14) modules[(size - 15 + i) * size + 8] = bit(i)
        modules[(size - 8) * size + 8] = true
    }

    private fun placeDataBits(
        size: Int,
        modules: BooleanArray,
        isFunction: BooleanArray,
        codewords: ByteArray
    ) {
        var bitIdx = 0
        val totalBits = codewords.size * 8
        var upward = true
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) {
                val y = if (upward) size - 1 - vert else vert
                for (j in 0..1) {
                    val x = right - j
                    val idx = y * size + x
                    if (!isFunction[idx]) {
                        val dark = if (bitIdx < totalBits) {
                            val cw = codewords[bitIdx ushr 3].toInt() and 0xFF
                            ((cw ushr (7 - (bitIdx and 7))) and 1) != 0
                        } else {
                            false
                        }
                        modules[idx] = dark
                        bitIdx++
                    }
                }
            }
            upward = !upward
            right -= 2
        }
    }

    private fun applyMask(size: Int, modules: BooleanArray, isFunction: BooleanArray, mask: Int) {
        for (y in 0 until size) {
            for (x in 0 until size) {
                val idx = y * size + x
                if (isFunction[idx]) continue
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> ((x / 3) + (y / 2)) % 2 == 0
                    5 -> ((x * y) % 2) + ((x * y) % 3) == 0
                    6 -> (((x * y) % 2) + ((x * y) % 3)) % 2 == 0
                    7 -> (((x + y) % 2) + ((x * y) % 3)) % 2 == 0
                    else -> false
                }
                if (invert) modules[idx] = !modules[idx]
            }
        }
    }

    private fun computePenalty(size: Int, modules: BooleanArray): Int {
        var penalty = 0
        // Rule 1: Runs of >= 5 same-color modules horizontally & vertically
        for (y in 0 until size) {
            var runColor = modules[y * size]
            var runLen = 1
            for (x in 1 until size) {
                val c = modules[y * size + x]
                if (c == runColor) {
                    runLen++
                } else {
                    if (runLen >= 5) penalty += 3 + (runLen - 5)
                    runColor = c
                    runLen = 1
                }
            }
            if (runLen >= 5) penalty += 3 + (runLen - 5)
        }
        for (x in 0 until size) {
            var runColor = modules[x]
            var runLen = 1
            for (y in 1 until size) {
                val c = modules[y * size + x]
                if (c == runColor) {
                    runLen++
                } else {
                    if (runLen >= 5) penalty += 3 + (runLen - 5)
                    runColor = c
                    runLen = 1
                }
            }
            if (runLen >= 5) penalty += 3 + (runLen - 5)
        }
        // Rule 2: 2x2 blocks of same color
        for (y in 0 until size - 1) {
            for (x in 0 until size - 1) {
                val c = modules[y * size + x]
                if (c == modules[y * size + x + 1] &&
                    c == modules[(y + 1) * size + x] &&
                    c == modules[(y + 1) * size + x + 1]
                ) {
                    penalty += 3
                }
            }
        }
        return penalty
    }
}
