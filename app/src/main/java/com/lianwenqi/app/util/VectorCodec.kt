package com.lianwenqi.app.util

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** 把向量编码为 Room 可直接存 BLOB 的小端字节数组 */
fun encodeVector(vector: FloatArray): ByteArray {
    val buffer = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    vector.forEach { buffer.putFloat(it) }
    return buffer.array()
}

/** 还原 [encodeVector] 编码的向量；字节数不是 4 的倍数时按整除截断，容错不抛异常 */
fun decodeVector(bytes: ByteArray): FloatArray {
    val count = bytes.size / Float.SIZE_BYTES
    if (count == 0) return FloatArray(0)
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val out = FloatArray(count)
    for (i in 0 until count) out[i] = buffer.getFloat()
    return out
}

/** 余弦相似度；维度不一致或任一为零向量时返回 0（视为不相关） */
fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
    if (a.isEmpty() || a.size != b.size) return 0f
    var dot = 0.0
    var na = 0.0
    var nb = 0.0
    for (i in a.indices) {
        dot += a[i] * b[i]
        na += a[i] * a[i]
        nb += b[i] * b[i]
    }
    if (na == 0.0 || nb == 0.0) return 0f
    return (dot / (sqrt(na) * sqrt(nb))).toFloat()
}
