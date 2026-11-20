package com.example.shadowescape.engine3d

import android.content.Context
import android.view.Choreographer
import android.view.SurfaceView
import com.google.android.filament.Colors
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import com.google.android.filament.Skybox
import com.google.android.filament.View
import com.google.android.filament.utils.ModelViewer
import com.example.shadowescape.model.PlayerStats
import com.example.shadowescape.model.Weapon
import com.example.shadowescape.model.Weapons
import com.example.shadowescape.physics3d.Capsule3D
import com.example.shadowescape.physics3d.Vector3
import com.example.shadowescape.ui3d.DualZoneTouchOverlay
import java.nio.ByteBuffer
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Core 3D Scene Controller driving Google Filament PBR rendering and Soulslike gameplay loop.
 */
class FilamentGameScene(
    private val context: Context,
    val surfaceView: SurfaceView,
    val overlay: DualZoneTouchOverlay
) : Choreographer.FrameCallback {

    val modelViewer: ModelViewer = ModelViewer(surfaceView)
    val camera3D: ThirdPersonOrbitCamera = ThirdPersonOrbitCamera()

    // 3D Player State
    val playerPosition: Vector3 = Vector3(0f, 0f, 0f)
    var playerYawDeg: Float = 0f
    val playerCapsule: Capsule3D = Capsule3D(playerPosition, radius = 0.45f, height = 1.8f)

    // Combat & RPG Stats
    val stats: PlayerStats = PlayerStats()
    var equippedWeapon: Weapon = Weapons.SHADOW_KATANA
    var isAttacking: Boolean = false
    var attackTimer: Float = 0f
    val attackDuration: Float = 0.55f
    var isRolling: Boolean = false
    var rollTimer: Float = 0f
    val rollDuration: Float = 0.65f
    var rollVelocity: Vector3 = Vector3.ZERO

    // Target Dummy / Enemy
    val enemyPosition: Vector3 = Vector3(0f, 0f, -5.5f)
    val enemyCapsule: Capsule3D = Capsule3D(enemyPosition, radius = 0.5f, height = 2.0f)
    var enemyHp: Float = 800f
    val enemyMaxHp: Float = 800f

    private var lastFrameTimeNanos: Long = 0L
    private val choreographer: Choreographer = Choreographer.getInstance()
    private var isRunning: Boolean = false

    private var sunlightEntity: Int = 0
    private var torchEntity: Int = 0

    init {
        overlay.camera = camera3D
        overlay.equippedWeapon = equippedWeapon

        setupLighting()
        setupSkybox()
        wireOverlayCallbacks()
    }

    private fun setupSkybox() {
        // Dark fantasy abyss skybox (deep slate indigo with soft atmospheric tone)
        val skybox = Skybox.Builder()
            .color(0.04f, 0.06f, 0.10f, 1.0f)
            .build(modelViewer.engine)
        modelViewer.scene.skybox = skybox
    }

    private fun setupLighting() {
        val engine = modelViewer.engine
        val scene = modelViewer.scene

        // 1. Directional Sunlight (Dark Fantasy Amber Sun with Cast Shadows)
        sunlightEntity = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(0.98f, 0.92f, 0.82f)
            .intensity(95_000f)
            .direction(-0.6f, -1.2f, -0.8f)
            .castShadows(true)
            .build(engine, sunlightEntity)
        scene.addEntity(sunlightEntity)

        // 2. Point Light Torch (Atmospheric Warm Orange Glow near Arena center)
        torchEntity = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.POINT)
            .color(1.0f, 0.55f, 0.15f)
            .intensity(250_000f)
            .position(0f, 2.2f, 0f)
            .falloff(12f)
            .build(engine, torchEntity)
        scene.addEntity(torchEntity)
    }

    private fun wireOverlayCallbacks() {
        overlay.onLightAttack = {
            if (!isAttacking && !isRolling && stats.currentStamina >= 18f) {
                isAttacking = true
                attackTimer = 0f
                stats.useStamina(18f)
                overlay.isWeaponSheathed = false

                // Calculate damage based on exact weapon requirement penalty formula
                val damageOutput = equippedWeapon.calculateEffectiveDamage(stats)
                checkHit(damageOutput, isHeavy = false)
            }
        }

        overlay.onHeavyAttack = {
            if (!isAttacking && !isRolling && stats.currentStamina >= 35f) {
                isAttacking = true
                attackTimer = 0f
                stats.useStamina(35f)
                overlay.isWeaponSheathed = false

                val damageOutput = equippedWeapon.calculateEffectiveDamage(stats) * 1.85f
                checkHit(damageOutput, isHeavy = true)
            }
        }

        overlay.onDodgeRoll = {
            if (!isRolling && stats.currentStamina >= 20f) {
                isRolling = true
                rollTimer = 0f
                stats.useStamina(20f)

                // Direction of roll: if moving, roll in move direction; otherwise roll backwards
                val joyX = overlay.joystickX
                val joyY = overlay.joystickY
                if (joyX * joyX + joyY * joyY > 0.05f) {
                    val moveDir = (camera3D.horizontalForward * joyY) + (camera3D.horizontalRight * joyX)
                    rollVelocity = moveDir.normalized() * 7.5f
                } else {
                    rollVelocity = (camera3D.horizontalForward * -1f) * 6.0f
                }
            }
        }

        overlay.onLockOnToggle = {
            if (overlay.isTargetLocked) {
                camera3D.isLockedOn = true
                camera3D.lockTarget = enemyPosition
            } else {
                camera3D.isLockedOn = false
                camera3D.lockTarget = null
            }
        }

        overlay.onHealFlask = {
            stats.drinkCrimsonFlask()
            overlay.currentHp = stats.currentHp
        }
    }

    private fun checkHit(dmg: Float, isHeavy: Boolean) {
        val dist = playerPosition.distanceTo(enemyPosition)
        if (dist <= 2.2f) {
            enemyHp = (enemyHp - dmg).coerceAtLeast(0f)
        }
    }

    fun start() {
        if (!isRunning) {
            isRunning = true
            choreographer.postFrameCallback(this)
        }
    }

    fun stop() {
        isRunning = false
        choreographer.removeFrameCallback(this)
    }

    fun destroy() {
        stop()
        modelViewer.destroyModel()
        val engine = modelViewer.engine
        val scene = modelViewer.scene
        if (sunlightEntity != 0) {
            scene.removeEntity(sunlightEntity)
            engine.destroyEntity(sunlightEntity)
        }
        if (torchEntity != 0) {
            scene.removeEntity(torchEntity)
            engine.destroyEntity(torchEntity)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRunning) return

        val dt = if (lastFrameTimeNanos != 0L) {
            ((frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000.0f).coerceIn(0.001f, 0.05f)
        } else {
            0.016f
        }
        lastFrameTimeNanos = frameTimeNanos

        // 1. Update Stamina Recovery
        stats.update(dt)
        overlay.currentStamina = stats.currentStamina
        overlay.maxStamina = stats.maxStamina
        overlay.currentHp = stats.currentHp
        overlay.maxHp = stats.maxHp

        // 2. Handle Locomotion & Physics
        if (isRolling) {
            rollTimer += dt
            playerPosition.add(rollVelocity.x * dt, 0f, rollVelocity.z * dt)
            if (rollTimer >= rollDuration) {
                isRolling = false
            }
        } else if (isAttacking) {
            attackTimer += dt
            if (attackTimer >= attackDuration) {
                isAttacking = false
            }
        } else {
            // Player directional movement via virtual joystick
            val joyX = overlay.joystickX
            val joyY = overlay.joystickY
            val stickMagSq = joyX * joyX + joyY * joyY

            if (stickMagSq > 0.03f) {
                val speed = if (overlay.isSprinting && stats.currentStamina > 5f) {
                    stats.useStamina(12f * dt)
                    6.2f
                } else {
                    3.8f
                }

                val moveDir = (camera3D.horizontalForward * joyY) + (camera3D.horizontalRight * joyX)
                moveDir.y = 0f
                moveDir.normalize()

                playerPosition.add(moveDir.x * speed * dt, 0f, moveDir.z * speed * dt)

                // Smoothly rotate player towards movement direction
                val targetYaw = Math.toDegrees(atan2(moveDir.x.toDouble(), moveDir.z.toDouble())).toFloat()
                var yawDiff = (targetYaw - playerYawDeg) % 360f
                if (yawDiff < -180f) yawDiff += 360f
                if (yawDiff > 180f) yawDiff -= 360f
                playerYawDeg += yawDiff * (dt * 14.0f).coerceIn(0f, 1f)
            }
        }

        // 3. Constrain Player to Arena Perimeter (18m radius)
        playerCapsule.baseCenter = playerPosition
        playerCapsule.clampToArena(18f)

        // 4. Update Third-Person Orbit Camera
        camera3D.update(playerPosition, dt)
        val eye = camera3D.eye
        val center = camera3D.lookAt

        modelViewer.camera.lookAt(
            eye.x.toDouble(), eye.y.toDouble(), eye.z.toDouble(),
            center.x.toDouble(), center.y.toDouble(), center.z.toDouble(),
            0.0, 1.0, 0.0
        )

        // 5. Render 3D Frame
        modelViewer.render(frameTimeNanos)

        // 6. Request next frame
        choreographer.postFrameCallback(this)
    }
}
