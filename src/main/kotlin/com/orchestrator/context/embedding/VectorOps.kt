package com.orchestrator.context.embedding

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Lightweight vector math utilities optimized for embedding operations.
 */
object VectorOps {

    private const val EPS = 1e-12f

    fun normalize(vec: FloatArray): FloatArray {
        if (vec.isEmpty()) return FloatArray(0)

        var sum = 0.0
        for (value in vec) {
            sum += value * value
        }

        if (sum <= EPS) {
            return FloatArray(vec.size)
        }

        val invNorm = (1.0 / sqrt(sum)).toFloat()
        val result = FloatArray(vec.size)
        for (i in vec.indices) {
            result[i] = vec[i] * invNorm
        }
        return result
    }

    fun dotProduct(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Vector size mismatch: ${a.size} vs ${b.size}" }

        var sum = 0f
        for (i in a.indices) {
            sum += a[i] * b[i]
        }
        return sum
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Vector size mismatch: ${a.size} vs ${b.size}" }

        if (a.isEmpty()) return 0f

        var dot = 0f
        var normASum = 0f
        var normBSum = 0f

        for (i in a.indices) {
            val av = a[i]
            val bv = b[i]
            dot += av * bv
            normASum += av * av
            normBSum += bv * bv
        }

        if (normASum <= EPS || normBSum <= EPS) {
            return 0f
        }

        val denom = sqrt(normASum.toDouble() * normBSum.toDouble()).toFloat()
        return dot / denom
    }

    fun serialize(vec: FloatArray): ByteArray {
        val length = vec.size
        val byteCount = length * Float.SIZE_BYTES
        val buffer = ByteBuffer.allocate(Int.SIZE_BYTES + byteCount).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(length)
        for (value in vec) {
            buffer.putFloat(value)
        }
        return buffer.array()
    }

    fun deserialize(bytes: ByteArray): FloatArray {
        require(bytes.size >= Int.SIZE_BYTES) { "Byte array too small to contain vector length" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val length = buffer.int
        require(length >= 0) { "Negative vector length: $length" }

        val expectedBytes = length * Float.SIZE_BYTES
        require(buffer.remaining() == expectedBytes) {
            "Unexpected byte length: expected $expectedBytes remaining, got ${buffer.remaining()}"
        }

        val result = FloatArray(length)
        for (i in 0 until length) {
            result[i] = buffer.float
        }
        return result
    }
}
