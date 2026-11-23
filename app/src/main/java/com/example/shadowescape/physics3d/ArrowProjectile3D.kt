package com.example.shadowescape.physics3d

import com.google.android.filament.gltfio.FilamentAsset

/**
 * 3D Ballistic projectile representing a fired arrow from the longbow.
 * Tracks 3D position, ballistic velocity arc with gravity, flight lifetime,
 * and handles ground collision and impact registration.
 */
data class ArrowProjectile3D(
    val asset: FilamentAsset,
    var position: Vector3,
    var velocity: Vector3,
    val damage: Float,
    val gravity: Float = 5.2f,     // Realistic ballistic drop arc
    var lifetime: Float = 3.5f,    // Max flight time before despawn
    var isDead: Boolean = false,
    var hasHitTarget: Boolean = false
) {
    fun update(dt: Float) {
        if (isDead) return

        // 1. Apply ballistic gravity arc
        velocity.y -= gravity * dt

        // 2. Advance 3D world position
        position.x += velocity.x * dt
        position.y += velocity.y * dt
        position.z += velocity.z * dt

        lifetime -= dt

        // 3. Ground collision at meadow planar surface Y = 0.05m
        if (position.y <= 0.05f) {
            position.y = 0.05f
            isDead = true
        }

        if (lifetime <= 0f) {
            isDead = true
        }
    }
}
