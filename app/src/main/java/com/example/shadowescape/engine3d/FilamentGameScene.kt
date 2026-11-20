package com.example.shadowescape.engine3d

import android.content.Context
import android.opengl.Matrix
import android.view.Choreographer
import android.view.SurfaceView
import com.google.android.filament.Box
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Skybox
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.Manipulator
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
 * Core 3D Scene Controller driving Google Filament PBR rendering, 3D glTF modular actors,
 * and Soulslike gameplay loop.
 */
class FilamentGameScene(
    private val context: Context,
    val surfaceView: SurfaceView,
    val overlay: DualZoneTouchOverlay
) : Choreographer.FrameCallback {

    val manipulator: Manipulator = Manipulator.Builder()
        .targetPosition(0f, 1.25f, 0f)
        .orbitHomePosition(0f, 2.8f, 4.2f)
        .build(Manipulator.Mode.ORBIT)

    val modelViewer: ModelViewer = ModelViewer(surfaceView = surfaceView, manipulator = manipulator)
    val camera3D: ThirdPersonOrbitCamera = ThirdPersonOrbitCamera()

    // glTF Asset Loading System
    private val materialProvider = UbershaderProvider(modelViewer.engine)
    private val assetLoader = AssetLoader(modelViewer.engine, materialProvider, EntityManager.get())
    private val resourceLoader = ResourceLoader(modelViewer.engine)

    private var arenaAsset: FilamentAsset? = null
    private var playerAsset: FilamentAsset? = null
    private var weaponAsset: FilamentAsset? = null

    // 3D Player State
    val playerPosition: Vector3 = Vector3(0f, 0f, 0f)
    var playerYawDeg: Float = 0f
    val playerCapsule: Capsule3D = Capsule3D(playerPosition, radius = 0.45f, height = 1.8f)

    private val playerTransform = FloatArray(16)
    private val weaponTransform = FloatArray(16)

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
    private var indirectLight: IndirectLight? = null

    init {
        overlay.camera = camera3D
        overlay.equippedWeapon = equippedWeapon

        setupLighting()
        setupSkybox()
        wireOverlayCallbacks()
        load3DAssets()
    }

    private fun setupSkybox() {
        val skybox = Skybox.Builder()
            .color(0.14f, 0.18f, 0.26f, 1.0f) // Twilight atmospheric dusk
            .build(modelViewer.engine)
        modelViewer.scene.skybox = skybox
    }

    private fun setupLighting() {
        val engine = modelViewer.engine
        val scene = modelViewer.scene

        // 1. Directional Sunlight (Dark Fantasy Amber Sun with Cast Shadows)
        sunlightEntity = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 0.94f, 0.85f)
            .intensity(130_000f)
            .direction(0.4f, -1.2f, -0.6f)
            .castShadows(true)
            .build(engine, sunlightEntity)
        scene.addEntity(sunlightEntity)

        // 2. Point Light Torch (Atmospheric Warm Orange Glow near Arena center)
        torchEntity = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.POINT)
            .color(1.0f, 0.65f, 0.22f)
            .intensity(320_000f)
            .position(0f, 2.5f, 0f)
            .falloff(22f)
            .build(engine, torchEntity)
        scene.addEntity(torchEntity)

        // 3. Ambient Indirect Light (Spherical Harmonics Irradiance)
        // Illuminates all faces of ground and models with soft ambient light
        val sh = floatArrayOf(
            0.75f, 0.75f, 0.82f,
            0.12f, 0.12f, 0.16f,
            0.18f, 0.18f, 0.22f,
            0.08f, 0.08f, 0.12f,
            0.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 0.0f
        )
        indirectLight = IndirectLight.Builder()
            .irradiance(3, sh)
            .intensity(50_000f)
            .build(engine)
        scene.indirectLight = indirectLight
    }

    private fun load3DAssets() {
        arenaAsset = loadGlb("models/arena_dark_sanctuary.glb")
        playerAsset = loadGlb("models/modular_mannequin.glb")
        weaponAsset = loadGlb("models/weapon_katana.glb")
    }

    private fun loadGlb(assetPath: String): FilamentAsset? {
        return try {
            val bytes = context.assets.open(assetPath).use { it.readBytes() }
            val buffer = ByteBuffer.allocateDirect(bytes.size).apply {
                put(bytes)
                flip()
            }
            val asset = assetLoader.createAsset(buffer)
            if (asset != null) {
                resourceLoader.loadResources(asset)
                val rm = modelViewer.engine.renderableManager
                for (entity in asset.entities) {
                    val inst = rm.getInstance(entity)
                    if (inst != 0) {
                        rm.setAxisAlignedBoundingBox(inst, Box(0f, 0f, 0f, 50f, 50f, 50f))
                        rm.setCulling(inst, false)
                    }
                }
                modelViewer.scene.addEntities(asset.entities)
            }
            asset
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
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

        overlay.onSheathToggle = {
            overlay.isWeaponSheathed = !overlay.isWeaponSheathed
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
        arenaAsset?.let {
            modelViewer.scene.removeEntities(it.entities)
            assetLoader.destroyAsset(it)
        }
        playerAsset?.let {
            modelViewer.scene.removeEntities(it.entities)
            assetLoader.destroyAsset(it)
        }
        weaponAsset?.let {
            modelViewer.scene.removeEntities(it.entities)
            assetLoader.destroyAsset(it)
        }
        assetLoader.destroy()
        materialProvider.destroy()
        resourceLoader.destroy()

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
        indirectLight?.let {
            scene.indirectLight = null
            engine.destroyIndirectLight(it)
            indirectLight = null
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

        // 1. Update Stamina Recovery according to Player Stats & Endurance
        stats.update(dt)
        overlay.currentStamina = stats.currentStamina
        overlay.maxStamina = stats.maxStamina
        overlay.currentHp = stats.currentHp
        overlay.maxHp = stats.maxHp
        overlay.endurance = stats.endurance
        overlay.agility = stats.agility
        overlay.staminaRegenRate = stats.staminaRegenRate
        overlay.postInvalidate() // Ensures continuous 60fps re-draw of refilling green stamina bar

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
                overlay.isWeaponSheathed = true
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

        // 3. Constrain Player to Arena Perimeter (17.5m radius)
        playerCapsule.baseCenter = playerPosition
        playerCapsule.clampToArena(17.5f)

        // 4. Update 3D Player Transform
        playerAsset?.let { asset ->
            val tm = modelViewer.engine.transformManager
            val inst = tm.getInstance(asset.root)
            if (inst != 0) {
                Matrix.setIdentityM(playerTransform, 0)
                Matrix.translateM(playerTransform, 0, playerPosition.x, playerPosition.y, playerPosition.z)
                Matrix.rotateM(playerTransform, 0, playerYawDeg, 0f, 1f, 0f)

                // If rolling, pitch forward around X axis
                if (isRolling) {
                    val rollProgress = (rollTimer / rollDuration).coerceIn(0f, 1f)
                    val pitchAngle = rollProgress * 360f
                    Matrix.rotateM(playerTransform, 0, pitchAngle, 1f, 0f, 0f)
                }
                tm.setTransform(inst, playerTransform)
            }
        }

        // 5. Update 3D Weapon Transform (Sheathed vs In Hand Swing)
        weaponAsset?.let { wpn ->
            val tm = modelViewer.engine.transformManager
            val inst = tm.getInstance(wpn.root)
            if (inst != 0) {
                Matrix.setIdentityM(weaponTransform, 0)
                if (isAttacking) {
                    // Attack Swing: drawn in right hand with dynamic slashing arc
                    Matrix.translateM(weaponTransform, 0, playerPosition.x, playerPosition.y, playerPosition.z)
                    Matrix.rotateM(weaponTransform, 0, playerYawDeg, 0f, 1f, 0f)
                    Matrix.translateM(weaponTransform, 0, 0.35f, 0.95f, 0.15f)

                    val swingProgress = (attackTimer / attackDuration).coerceIn(0f, 1f)
                    val swingAngle = -35f + swingProgress * 130f
                    Matrix.rotateM(weaponTransform, 0, swingAngle, 1f, 0f, 0f)
                    Matrix.rotateM(weaponTransform, 0, -25f, 0f, 1f, 0f)
                } else if (!overlay.isWeaponSheathed) {
                    // Drawn in right hand, resting in ready combat guard stance
                    Matrix.translateM(weaponTransform, 0, playerPosition.x, playerPosition.y, playerPosition.z)
                    Matrix.rotateM(weaponTransform, 0, playerYawDeg, 0f, 1f, 0f)
                    Matrix.translateM(weaponTransform, 0, 0.32f, 0.85f, 0.10f)
                    Matrix.rotateM(weaponTransform, 0, -25f, 1f, 0f, 0f)
                } else {
                    // Sheathed diagonally on back!
                    Matrix.translateM(weaponTransform, 0, playerPosition.x, playerPosition.y, playerPosition.z)
                    Matrix.rotateM(weaponTransform, 0, playerYawDeg, 0f, 1f, 0f)
                    Matrix.translateM(weaponTransform, 0, 0.08f, 1.25f, -0.18f)
                    Matrix.rotateM(weaponTransform, 0, 160f, 0f, 0f, 1f)
                }
                tm.setTransform(inst, weaponTransform)
            }
        }

        // 6. Update Third-Person Orbit Camera
        camera3D.update(playerPosition, dt)
        val eye = camera3D.eye
        val center = camera3D.lookAt

        modelViewer.camera.lookAt(
            eye.x.toDouble(), eye.y.toDouble(), eye.z.toDouble(),
            center.x.toDouble(), center.y.toDouble(), center.z.toDouble(),
            0.0, 1.0, 0.0
        )

        // 7. Render 3D Frame
        modelViewer.render(frameTimeNanos)

        // 8. Request next frame
        choreographer.postFrameCallback(this)
    }
}
