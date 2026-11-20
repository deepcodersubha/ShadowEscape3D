package com.example.shadowescape.engine3d

import com.example.shadowescape.physics3d.Vector3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Cinematic Soulslike Third-Person Orbit Camera.
 * Tracks target with smooth damping, pitch constraints, and auto-centering target lock-on.
 */
class ThirdPersonOrbitCamera {

    var distance: Float = 4.2f
    var minDistance: Float = 2.0f
    var maxDistance: Float = 7.0f

    var yawDeg: Float = 0f          // Horizontal angle in degrees
    var pitchDeg: Float = 22f       // Vertical angle in degrees (higher = looking down)
    val minPitchDeg: Float = -10f
    val maxPitchDeg: Float = 65f

    // Dynamic smoothing
    private var currentYaw: Float = 0f
    private var currentPitch: Float = 22f
    private var currentEye: Vector3 = Vector3(0f, 2f, 4f)
    private var currentLookAt: Vector3 = Vector3(0f, 1f, 0f)

    // Lock-on targeting
    var isLockedOn: Boolean = false
    var lockTarget: Vector3? = null

    val eye: Vector3 get() = currentEye
    val lookAt: Vector3 get() = currentLookAt

    /**
     * Camera's horizontal forward direction vector on X-Z plane (unit vector).
     * Used for translating 2D virtual joystick into 3D world movement.
     */
    val horizontalForward: Vector3
        get() {
            val radYaw = Math.toRadians(currentYaw.toDouble()).toFloat()
            // Forward is pointing away from eye towards lookAt
            return Vector3(-sin(radYaw), 0f, -cos(radYaw)).normalize()
        }

    /**
     * Camera's horizontal right direction vector on X-Z plane (unit vector).
     */
    val horizontalRight: Vector3
        get() {
            val radYaw = Math.toRadians(currentYaw.toDouble()).toFloat()
            return Vector3(cos(radYaw), 0f, -sin(radYaw)).normalize()
        }

    fun rotate(deltaYaw: Float, deltaPitch: Float) {
        if (!isLockedOn) {
            yawDeg = (yawDeg + deltaYaw) % 360f
            if (yawDeg < 0f) yawDeg += 360f
            pitchDeg = (pitchDeg - deltaPitch).coerceIn(minPitchDeg, maxPitchDeg)
        }
    }

    fun update(playerPos: Vector3, dt: Float) {
        val targetFocus = Vector3(playerPos.x, playerPos.y + 1.25f, playerPos.z)

        if (isLockedOn && lockTarget != null) {
            // Calculate lock-on camera orientation (framing player & enemy)
            val enemy = lockTarget!!
            val toEnemyX = enemy.x - playerPos.x
            val toEnemyZ = enemy.z - playerPos.z
            val desiredYaw = Math.toDegrees(atan2(-toEnemyX.toDouble(), -toEnemyZ.toDouble())).toFloat()

            // Smoothly rotate yaw towards enemy
            var diff = (desiredYaw - yawDeg) % 360f
            if (diff < -180f) diff += 360f
            if (diff > 180f) diff -= 360f
            yawDeg += diff * (dt * 6.0f).coerceIn(0f, 1f)

            // Dynamic pitch based on distance
            val dist = sqrt(toEnemyX * toEnemyX + toEnemyZ * toEnemyZ)
            pitchDeg = (25f - dist * 1.2f).coerceIn(12f, 38f)
        }

        // Smooth camera damping
        val smoothFactor = (dt * 12.0f).coerceIn(0.01f, 1f)
        var yawDiff = (yawDeg - currentYaw) % 360f
        if (yawDiff < -180f) yawDiff += 360f
        if (yawDiff > 180f) yawDiff -= 360f
        currentYaw += yawDiff * smoothFactor
        currentPitch += (pitchDeg - currentPitch) * smoothFactor

        currentLookAt.lerp(targetFocus, smoothFactor)

        // Spherical coordinate math
        val radPitch = Math.toRadians(currentPitch.toDouble())
        val radYaw = Math.toRadians(currentYaw.toDouble())
        val eyeX = currentLookAt.x + (distance * cos(radPitch) * sin(radYaw)).toFloat()
        val eyeY = (currentLookAt.y + distance * sin(radPitch)).toFloat().coerceAtLeast(0.4f)
        val eyeZ = currentLookAt.z + (distance * cos(radPitch) * cos(radYaw)).toFloat()

        currentEye.lerp(Vector3(eyeX, eyeY, eyeZ), smoothFactor * 1.2f)
    }
}
