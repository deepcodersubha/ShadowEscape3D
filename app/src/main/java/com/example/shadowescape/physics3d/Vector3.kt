package com.example.shadowescape.physics3d

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * High-performance 3D vector for character locomotion, orbit camera, and hitbox tracking.
 */
data class Vector3(
    var x: Float = 0f,
    var y: Float = 0f,
    var z: Float = 0f
) {
    fun set(nx: Float, ny: Float, nz: Float): Vector3 {
        x = nx
        y = ny
        z = nz
        return this
    }

    fun set(other: Vector3): Vector3 = set(other.x, other.y, other.z)

    fun lengthSquared(): Float = x * x + y * y + z * z

    fun length(): Float = sqrt(lengthSquared())

    fun normalize(): Vector3 {
        val len = length()
        if (len > 1e-6f) {
            val inv = 1f / len
            x *= inv
            y *= inv
            z *= inv
        } else {
            x = 0f
            y = 0f
            z = 0f
        }
        return this
    }

    fun normalized(): Vector3 = Vector3(x, y, z).normalize()

    fun dot(other: Vector3): Float = x * other.x + y * other.y + z * other.z

    fun cross(other: Vector3): Vector3 = Vector3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x
    )

    fun distanceTo(other: Vector3): Float {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    fun distanceSquared(other: Vector3): Float {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return dx * dx + dy * dy + dz * dz
    }

    operator fun plus(other: Vector3): Vector3 = Vector3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vector3): Vector3 = Vector3(x - other.x, y - other.y, z - other.z)
    operator fun times(scalar: Float): Vector3 = Vector3(x * scalar, y * scalar, z * scalar)

    fun add(dx: Float, dy: Float, dz: Float): Vector3 {
        x += dx
        y += dy
        z += dz
        return this
    }

    fun lerp(target: Vector3, t: Float): Vector3 {
        val clampedT = t.coerceIn(0f, 1f)
        x += (target.x - x) * clampedT
        y += (target.y - y) * clampedT
        z += (target.z - z) * clampedT
        return this
    }

    companion object {
        val ZERO get() = Vector3(0f, 0f, 0f)
        val UP get() = Vector3(0f, 1f, 0f)
        val FORWARD get() = Vector3(0f, 0f, -1f)
        val RIGHT get() = Vector3(1f, 0f, 0f)
    }
}
