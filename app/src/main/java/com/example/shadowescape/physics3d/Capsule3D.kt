package com.example.shadowescape.physics3d

import kotlin.math.max
import kotlin.math.min

/**
 * 3D Capsule representation for Soulslike character controllers, player movement, and hurtboxes.
 * Base is at centerBottom, extending vertically along +Y.
 */
data class Capsule3D(
    var baseCenter: Vector3 = Vector3(0f, 0f, 0f),
    var radius: Float = 0.4f,
    var height: Float = 1.8f
) {
    val topCenter: Vector3
        get() = Vector3(baseCenter.x, baseCenter.y + height, baseCenter.z)

    /**
     * Checks if this capsule intersects with another capsule.
     */
    fun intersects(other: Capsule3D): Boolean {
        val radSum = radius + other.radius
        val radSumSq = radSum * radSum

        // Segment-to-segment distance squared between capsules
        val p1 = baseCenter
        val q1 = topCenter
        val p2 = other.baseCenter
        val q2 = other.topCenter

        val d1 = q1 - p1
        val d2 = q2 - p2
        val r = p1 - p2

        val a = d1.dot(d1)
        val e = d2.dot(d2)
        val f = d2.dot(r)

        var s: Float
        var t: Float

        if (a <= 1e-6f && e <= 1e-6f) {
            return r.dot(r) <= radSumSq
        }
        if (a <= 1e-6f) {
            s = 0f
            t = (f / e).coerceIn(0f, 1f)
        } else {
            val c = d1.dot(r)
            if (e <= 1e-6f) {
                t = 0f
                s = (-c / a).coerceIn(0f, 1f)
            } else {
                val b = d1.dot(d2)
                val denom = a * e - b * b
                s = if (denom != 0f) {
                    ((b * f - c * e) / denom).coerceIn(0f, 1f)
                } else {
                    0f
                }
                t = (b * s + f) / e
                if (t < 0f) {
                    t = 0f
                    s = (-c / a).coerceIn(0f, 1f)
                } else if (t > 1f) {
                    t = 1f
                    s = ((b - c) / a).coerceIn(0f, 1f)
                }
            }
        }

        val c1 = p1 + (d1 * s)
        val c2 = p2 + (d2 * t)
        return c1.distanceSquared(c2) <= radSumSq
    }

    /**
     * Constrains player within a circular or square arena boundary.
     */
    fun clampToArena(arenaRadius: Float) {
        val distHorizontal = kotlin.math.sqrt(baseCenter.x * baseCenter.x + baseCenter.z * baseCenter.z)
        val maxDist = arenaRadius - radius
        if (distHorizontal > maxDist && distHorizontal > 0.001f) {
            val scale = maxDist / distHorizontal
            baseCenter.x *= scale
            baseCenter.z *= scale
        }
    }
}
