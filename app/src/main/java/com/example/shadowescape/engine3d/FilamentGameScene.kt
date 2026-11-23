package com.example.shadowescape.engine3d

import android.content.Context
import android.opengl.Matrix
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceView
import com.google.android.filament.Box
import com.google.android.filament.Camera
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Skybox
import com.google.android.filament.SwapChain
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.Animator
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.Manipulator
import com.google.android.filament.utils.ModelViewer
import com.example.shadowescape.model.PlayerStats
import com.example.shadowescape.model.Weapon
import com.example.shadowescape.model.Weapons
import com.example.shadowescape.model.WeaponType
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

    val modelViewer: ModelViewer = ModelViewer(surfaceView = surfaceView, manipulator = null)
    val camera3D: ThirdPersonOrbitCamera = ThirdPersonOrbitCamera()

    // glTF Asset Loading System
    private val materialProvider = UbershaderProvider(modelViewer.engine)
    private val assetLoader = AssetLoader(modelViewer.engine, materialProvider, EntityManager.get())
    private val resourceLoader = ResourceLoader(modelViewer.engine)

    private var arenaAsset: FilamentAsset? = null
    private var playerAsset: FilamentAsset? = null
    private var enemyAsset: FilamentAsset? = null
    private var weaponAsset: FilamentAsset? = null
    private var shieldAsset: FilamentAsset? = null
    var isShieldEquipped: Boolean = false

    // Modular Animation Controller
    private var playerAnimator: Animator? = null
    private val animationIndices = mutableMapOf<String, Int>()
    private var playerAnimTime: Float = 0f
    private var currentAnimationName: String = "Idle_Bow"

    // 3D Player State
    val playerPosition: Vector3 = Vector3(0f, 0f, 0f)
    var playerYawDeg: Float = 180f // Initially facing forward towards arena dummy at (0, 0, -5.5)
    val playerCapsule: Capsule3D = Capsule3D(playerPosition, radius = 0.45f, height = 1.8f)

    private val playerTransform = FloatArray(16)
    private val enemyTransform = FloatArray(16)
    private val weaponTransform = FloatArray(16)

    // Combat & RPG Stats
    val stats: PlayerStats = PlayerStats()
    var equippedWeapon: Weapon = Weapons.SUN_BOW
    var isAttacking: Boolean = false
    var attackTimer: Float = 0f
    var currentAttackDuration: Float = 1.0f
    var currentAttackAnimName: String = "Draw_Arrow"
    var comboStep: Int = 0
    var comboQueued: Boolean = false
    private var pendingDamage: Float = 0f
    private var pendingIsHeavy: Boolean = false
    private var hitWindowTime: Float = 0.20f
    private var hasHitRegistered: Boolean = false

    // Modular Armor & Weapon Equipment Systems
    var equipmentManager: ModularEquipmentManager? = null
        private set
    private var currentWeaponType: Int = 0 // 0 = Longbow, 1 = Broadsword, 2 = Katana

    // Animation Cross-Fading & State Blending
    private var previousAnimIdx: Int = -1
    private var previousAnimTime: Float = 0f
    private var crossFadeTimer: Float = 0f
    private val crossFadeDuration: Float = 0.22f // 220ms smooth blend

    var isRolling: Boolean = false
    var rollTimer: Float = 0f
    val rollDuration: Float = 0.85f
    var rollVelocity: Vector3 = Vector3.ZERO

    // Target Dummy / Enemy (positioned at north edge of arena for spacious combat arena)
    val enemyPosition: Vector3 = Vector3(0f, 0f, -8.0f)
    val enemyCapsule: Capsule3D = Capsule3D(enemyPosition, radius = 0.5f, height = 2.0f)
    var enemyHp: Float = 800f
    val enemyMaxHp: Float = 800f

    private var lastFrameTimeNanos: Long = 0L
    private val choreographer: Choreographer = Choreographer.getInstance()
    private var isRunning: Boolean = false

    private var sunlightEntity: Int = 0
    private var torchEntity: Int = 0
    private var indirectLight: IndirectLight? = null

    // Reflection handles to bypass ModelViewer's internal cameraManipulator lookAt overwrite
    private val uiHelperField = ModelViewer::class.java.getDeclaredField("uiHelper").apply { isAccessible = true }
    private val swapChainField = ModelViewer::class.java.getDeclaredField("swapChain").apply { isAccessible = true }



    init {
        Log.d("FilamentGameScene", "Manipulator methods: " + Manipulator::class.java.methods.map { "${it.name}(${it.parameterTypes.map { p -> p.simpleName }.joinToString()})" }.joinToString("; "))
        overlay.camera = camera3D
        overlay.equippedWeapon = equippedWeapon
        overlay.currentWeaponName = "LONGBOW"
        overlay.isWeaponSheathed = false
        overlay.isShieldEquipped = false

        setupLighting()
        setupSkybox()
        wireOverlayCallbacks()
        load3DAssets()
    }

    private fun setupSkybox() {
        val skybox = Skybox.Builder()
            .color(0.52f, 0.70f, 0.88f, 1.0f) // Bright atmospheric stylized nature sky
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
        Log.d("FilamentGameScene", "Loading Quaternius nature sanctuary meadow arena...")
        arenaAsset = loadGlb("models/arena_nature_meadow.glb")
        Log.d("FilamentGameScene", "Loaded nature arena: $arenaAsset")

        Log.d("FilamentGameScene", "Loading Elf Male with Pro Longbow Pack...")
        playerAsset = loadGlb("models/player_elf_male.glb")
        Log.d("FilamentGameScene", "Loaded elf player: $playerAsset")

        playerAnimator = playerAsset?.instance?.animator
        Log.d("FilamentGameScene", "Player animator: $playerAnimator")
        playerAnimator?.let { anim ->
            Log.d("FilamentGameScene", "Animation count: ${anim.animationCount}")
            for (i in 0 until anim.animationCount) {
                val name = anim.getAnimationName(i)
                animationIndices[name] = i
                Log.d("FilamentGameScene", "Anim [$i]: $name")
            }
        }

        // Attach Longbow weapon
        attachWeapon("models/weapon_longbow.glb")
    }

    fun attachWeapon(assetPath: String) {
        weaponAsset?.let {
            modelViewer.scene.removeEntities(it.entities)
            modelViewer.scene.removeEntity(it.root)
            assetLoader.destroyAsset(it)
            weaponAsset = null
        }

        weaponAsset = loadGlb(assetPath)
        updateWeaponSocket()
    }

    fun updateWeaponSocket() {
        val player = playerAsset ?: return
        val wpn = weaponAsset ?: return
        val tm = modelViewer.engine.transformManager

        val isBow = equippedWeapon.type == WeaponType.BOW
        val targetBoneName = if (overlay.isWeaponSheathed) {
            if (player.getFirstEntityByName("mixamorig:Spine2") != 0) "mixamorig:Spine2" else "spine_03"
        } else {
            if (isBow) {
                if (player.getFirstEntityByName("mixamorig:LeftHand") != 0) "mixamorig:LeftHand"
                else if (player.getFirstEntityByName("mixamorig:RightHand") != 0) "mixamorig:RightHand"
                else "hand_l"
            } else {
                if (player.getFirstEntityByName("mixamorig:RightHand") != 0) "mixamorig:RightHand"
                else "hand_r"
            }
        }
        val boneEntity = player.getFirstEntityByName(targetBoneName)

        if (boneEntity != 0) {
            val boneInst = tm.getInstance(boneEntity)
            var wpnInst = tm.getInstance(wpn.root)
            if (wpnInst == 0) {
                tm.create(wpn.root)
                wpnInst = tm.getInstance(wpn.root)
            }
            if (boneInst != 0 && wpnInst != 0) {
                tm.setParent(wpnInst, boneInst)
                val localMat = FloatArray(16)
                Matrix.setIdentityM(localMat, 0)
                if (!overlay.isWeaponSheathed) {
                    if (isBow) {
                        // Longbow held in LeftHand: orient vertical with arrow rest pointing forward
                        Matrix.translateM(localMat, 0, 0.02f, 0.04f, -0.02f)
                        Matrix.rotateM(localMat, 0, 90f, 0f, 0f, 1f)
                        Matrix.rotateM(localMat, 0, 90f, 0f, 1f, 0f)
                    } else {
                        // Hand Grip Transform for sword
                        Matrix.translateM(localMat, 0, 0f, 0.04f, 0f)
                        Matrix.rotateM(localMat, 0, -90f, 1f, 0f, 0f)
                        Matrix.rotateM(localMat, 0, 180f, 0f, 1f, 0f)
                    }
                } else {
                    // Back Sheath Mount Transform (diagonally across back spine)
                    Matrix.translateM(localMat, 0, -0.05f, 0.12f, -0.16f)
                    Matrix.rotateM(localMat, 0, 145f, 0f, 0f, 1f)
                    Matrix.rotateM(localMat, 0, 15f, 1f, 0f, 0f)
                }
                tm.setTransform(wpnInst, localMat)
                Log.d("FilamentGameScene", "Socketed weapon to $targetBoneName (sheathed=${overlay.isWeaponSheathed})")
            }
        }
    }

    fun attachShield(assetPath: String?) {
        shieldAsset?.let {
            modelViewer.scene.removeEntities(it.entities)
            modelViewer.scene.removeEntity(it.root)
            assetLoader.destroyAsset(it)
            shieldAsset = null
        }
        if (assetPath != null) {
            shieldAsset = loadGlb(assetPath)
            updateShieldSocket()
        }
    }

    fun updateShieldSocket() {
        val player = playerAsset ?: return
        val shd = shieldAsset ?: return
        val tm = modelViewer.engine.transformManager

        val targetBoneName = if (overlay.isWeaponSheathed) "spine_02" else "lowerarm_l"
        val boneEntity = player.getFirstEntityByName(targetBoneName)

        if (boneEntity != 0) {
            val boneInst = tm.getInstance(boneEntity)
            var shdInst = tm.getInstance(shd.root)
            if (shdInst == 0) {
                tm.create(shd.root)
                shdInst = tm.getInstance(shd.root)
            }
            if (boneInst != 0 && shdInst != 0) {
                tm.setParent(shdInst, boneInst)
                val localMat = FloatArray(16)
                Matrix.setIdentityM(localMat, 0)
                if (!overlay.isWeaponSheathed) {
                    // Forearm Shield Guard Transform (mounted on outer forearm)
                    Matrix.translateM(localMat, 0, 0.05f, 0.15f, 0.03f)
                    Matrix.rotateM(localMat, 0, 90f, 0f, 1f, 0f)
                    Matrix.rotateM(localMat, 0, 180f, 1f, 0f, 0f)
                } else {
                    // Back Mount Transform
                    Matrix.translateM(localMat, 0, 0.05f, 0.10f, -0.16f)
                    Matrix.rotateM(localMat, 0, 180f, 0f, 1f, 0f)
                }
                tm.setTransform(shdInst, localMat)
                Log.d("FilamentGameScene", "Socketed shield to $targetBoneName (sheathed=${overlay.isWeaponSheathed})")
            }
        }
    }

    private fun loadGlb(assetPath: String): FilamentAsset? {
        Log.d("FilamentGameScene", "loadGlb: $assetPath")
        return try {
            val bytes = context.assets.open(assetPath).use { it.readBytes() }
            Log.d("FilamentGameScene", "Read ${bytes.size} bytes from $assetPath")
            val buffer = ByteBuffer.allocateDirect(bytes.size).apply {
                put(bytes)
                flip()
            }
            val asset = assetLoader.createAsset(buffer)
            if (asset != null) {
                resourceLoader.loadResources(asset)
                modelViewer.scene.addEntity(asset.root)
                modelViewer.scene.addEntities(asset.entities)

                val ready = IntArray(128)
                var count = asset.popRenderables(ready)
                val rm = modelViewer.engine.renderableManager
                while (count > 0) {
                    for (i in 0 until count) {
                        val entity = ready[i]
                        val inst = rm.getInstance(entity)
                        Log.d("FilamentGameScene", "loadGlb $assetPath popped entity=$entity inst=$inst")
                        if (inst != 0) {
                            rm.setAxisAlignedBoundingBox(inst, Box(0f, 0f, 0f, 50f, 50f, 50f))
                            rm.setCulling(inst, false)
                        }
                        // Add each ready renderable entity directly into the Filament scene!
                        modelViewer.scene.addEntity(entity)
                    }
                    count = asset.popRenderables(ready)
                }
            }
            asset
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun executeComboStrike(step: Int) {
        comboStep = step
        comboQueued = false
        isAttacking = true
        attackTimer = 0f
        hasHitRegistered = false
        if (overlay.isWeaponSheathed) {
            overlay.isWeaponSheathed = false
            updateWeaponSocket()
            updateShieldSocket()
        }

        when (step) {
            1 -> {
                currentAttackAnimName = "Draw_Arrow"
                currentAttackDuration = 0.80f
                stats.useStamina(10f)
                pendingDamage = equippedWeapon.calculateEffectiveDamage(stats)
                pendingIsHeavy = false
                hitWindowTime = 0.40f
            }
            2 -> {
                currentAttackAnimName = "Aim_Hold"
                currentAttackDuration = 0.70f
                stats.useStamina(12f)
                pendingDamage = equippedWeapon.calculateEffectiveDamage(stats) * 1.30f
                pendingIsHeavy = false
                hitWindowTime = 0.35f
            }
            3 -> {
                currentAttackAnimName = "Shoot_Recoil"
                currentAttackDuration = 0.60f
                stats.useStamina(16f)
                pendingDamage = equippedWeapon.calculateEffectiveDamage(stats) * 1.85f
                pendingIsHeavy = false
                hitWindowTime = 0.15f
            }
        }
    }

    private fun wireOverlayCallbacks() {
        overlay.onLightAttack = {
            if (!isRolling && stats.currentStamina >= 12f) {
                // If locked on, orient player towards target before striking
                if (camera3D.isLockedOn) {
                    val toEnemyX = enemyPosition.x - playerPosition.x
                    val toEnemyZ = enemyPosition.z - playerPosition.z
                    playerYawDeg = Math.toDegrees(atan2(toEnemyX.toDouble(), toEnemyZ.toDouble())).toFloat()
                }

                if (!isAttacking) {
                    executeComboStrike(1)
                } else if (attackTimer < 0.25f) {
                    comboQueued = true
                } else if (comboStep == 1 && attackTimer >= 0.25f) {
                    executeComboStrike(2)
                } else if (comboStep == 2 && attackTimer >= 0.25f) {
                    executeComboStrike(3)
                }
            }
        }

        overlay.onHeavyAttack = {
            if (!isRolling && stats.currentStamina >= 30f) {
                if (camera3D.isLockedOn) {
                    val toEnemyX = enemyPosition.x - playerPosition.x
                    val toEnemyZ = enemyPosition.z - playerPosition.z
                    playerYawDeg = Math.toDegrees(atan2(toEnemyX.toDouble(), toEnemyZ.toDouble())).toFloat()
                }
                comboStep = 0
                comboQueued = false
                isAttacking = true
                if (overlay.isWeaponSheathed) {
                    overlay.isWeaponSheathed = false
                    updateWeaponSocket()
                    updateShieldSocket()
                }
                currentAttackAnimName = "Melee_Kick"
                currentAttackDuration = 1.15f
                attackTimer = 0f
                hasHitRegistered = false
                stats.useStamina(25f)

                pendingDamage = equippedWeapon.calculateEffectiveDamage(stats) * 2.2f
                pendingIsHeavy = true
                hitWindowTime = 0.45f
            }
        }

        overlay.onDodgeRoll = {
            if (!isRolling && stats.currentStamina >= 20f) {
                isRolling = true
                isAttacking = false // Dodge roll cancels attack
                comboStep = 0
                comboQueued = false
                rollTimer = 0f
                stats.useStamina(20f)

                // Direction of roll: if moving, roll in move direction; otherwise roll backwards
                val joyX = overlay.joystickX
                val joyY = overlay.joystickY
                if (joyX * joyX + joyY * joyY > 0.05f) {
                    val moveDir = (camera3D.horizontalForward * joyY) + (camera3D.horizontalRight * joyX)
                    rollVelocity = moveDir.normalized() * 7.5f
                    playerYawDeg = Math.toDegrees(atan2(moveDir.x.toDouble(), moveDir.z.toDouble())).toFloat()
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
            if (!isAttacking && !isRolling) {
                updateWeaponSocket()
                updateShieldSocket()
                overlay.postInvalidate()
            }
        }

        overlay.onShieldToggle = {
            isShieldEquipped = overlay.isShieldEquipped
            if (isShieldEquipped) {
                attachShield("models/weapon_shield.glb")
            } else {
                attachShield(null)
            }
            overlay.postInvalidate()
        }

        overlay.onArmorToggle = {
            val nextSet = equipmentManager?.toggleArmorSet() ?: ArmorSet.RANGER
            overlay.currentArmorName = nextSet.name
            overlay.postInvalidate()
        }

        overlay.onWeaponToggle = {
            currentWeaponType = (currentWeaponType + 1) % 3
            when (currentWeaponType) {
                0 -> {
                    equippedWeapon = Weapons.SUN_BOW
                    overlay.equippedWeapon = equippedWeapon
                    overlay.currentWeaponName = "LONGBOW"
                    attachWeapon("models/weapon_longbow.glb")
                }
                1 -> {
                    equippedWeapon = Weapons.STEEL_BROADSWORD
                    overlay.equippedWeapon = equippedWeapon
                    overlay.currentWeaponName = "SWORD"
                    attachWeapon("models/weapon_broadsword.glb")
                }
                else -> {
                    equippedWeapon = Weapons.SHADOW_KATANA
                    overlay.equippedWeapon = equippedWeapon
                    overlay.currentWeaponName = "KATANA"
                    attachWeapon("models/weapon_katana.glb")
                }
            }
            overlay.postInvalidate()
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
        enemyAsset?.let {
            modelViewer.scene.removeEntities(it.entities)
            assetLoader.destroyAsset(it)
        }
        weaponAsset?.let {
            modelViewer.scene.removeEntities(it.entities)
            assetLoader.destroyAsset(it)
        }
        shieldAsset?.let {
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
        val joyX = overlay.joystickX
        val joyY = overlay.joystickY
        val stickMagSq = joyX * joyX + joyY * joyY

        if (isRolling) {
            rollTimer += dt
            playerPosition.add(rollVelocity.x * dt, 0f, rollVelocity.z * dt)
            if (rollTimer >= rollDuration) {
                isRolling = false
            }
        } else if (isAttacking) {
            attackTimer += dt

            // Synchronized hit registration at swing impact
            if (!hasHitRegistered && attackTimer >= hitWindowTime) {
                hasHitRegistered = true
                checkHit(pendingDamage, pendingIsHeavy)
            }

            // Check if user buffered next combo attack during windup
            if (comboQueued && attackTimer >= 0.25f) {
                if (comboStep == 1 && stats.currentStamina >= 15f) {
                    executeComboStrike(2)
                } else if (comboStep == 2 && stats.currentStamina >= 20f) {
                    executeComboStrike(3)
                } else {
                    comboQueued = false
                }
            }

            // Natural recovery cancel: after strike connects and blade completes arc (~65% duration),
            // movement joystick smoothly cancels remaining recovery into walking
            val canCancelRecovery = attackTimer >= (currentAttackDuration * 0.65f).coerceAtLeast(0.40f)
            if (canCancelRecovery && stickMagSq > 0.08f) {
                isAttacking = false
                comboStep = 0
                comboQueued = false
            } else if (attackTimer >= currentAttackDuration) {
                isAttacking = false
                comboStep = 0
                comboQueued = false
            }
        }

        // Player directional movement via virtual joystick with dynamic walk/run speeds
        val isMoving = stickMagSq > 0.03f && !isRolling && !isAttacking
        val stickMag = kotlin.math.sqrt(stickMagSq).coerceIn(0f, 1f)
        // Natural run: joystick pushed past 60% outer deflection OR holding Sprint button
        val isRunning = isMoving && (overlay.isSprinting || stickMag > 0.60f) && stats.currentStamina > 3f
        var currentMoveSpeed = 0f

        if (!isRolling && !isAttacking && isMoving) {
            currentMoveSpeed = if (isRunning) {
                if (overlay.isSprinting) {
                    stats.useStamina(10f * dt)
                }
                // High-velocity combat run: scales from 4.8m/s to 6.8m/s based on stick deflection
                4.8f + (stickMag - 0.60f).coerceAtLeast(0f) / 0.40f * 2.0f
            } else {
                // Tactical walk: scales from 1.8m/s to 3.0m/s
                1.8f + (stickMag / 0.60f) * 1.2f
            }

            val moveDir = (camera3D.horizontalForward * joyY) + (camera3D.horizontalRight * joyX)
            moveDir.y = 0f
            moveDir.normalize()

            playerPosition.add(moveDir.x * currentMoveSpeed * dt, 0f, moveDir.z * currentMoveSpeed * dt)

            // Smoothly rotate player towards movement direction
            val targetYaw = Math.toDegrees(atan2(moveDir.x.toDouble(), moveDir.z.toDouble())).toFloat()
            var yawDiff = (targetYaw - playerYawDeg) % 360f
            if (yawDiff < -180f) yawDiff += 360f
            if (yawDiff > 180f) yawDiff -= 360f
            playerYawDeg += yawDiff * (dt * 14.0f).coerceIn(0f, 1f)
        }

        // 3. Constrain Player to Arena Perimeter (17.5m radius)
        playerCapsule.baseCenter = playerPosition
        playerCapsule.clampToArena(17.5f)

        // 4. Update Skeletal Animation
        val targetAnim = when {
            isRolling -> "Roll"
            isAttacking -> currentAttackAnimName
            overlay.isWeaponSheathed -> {
                when {
                    isRunning -> "Run_Bow"
                    isMoving -> "Walk_Bow"
                    else -> "Idle_Unarmed"
                }
            }
            else -> {
                when {
                    isRunning -> "Run_Bow"
                    isMoving -> "Walk_Bow"
                    else -> "Idle_Bow"
                }
            }
        }

        if (targetAnim != currentAnimationName) {
            val oldIdx = animationIndices[currentAnimationName]
                ?: animationIndices[currentAnimationName.lowercase()]
                ?: 0
            previousAnimIdx = oldIdx
            previousAnimTime = playerAnimTime
            crossFadeTimer = 0f

            currentAnimationName = targetAnim
            playerAnimTime = 0f
        } else {
            // Dynamically scale animation stride rate to velocity so boots plant with zero sliding
            val playbackRate = when {
                isRunning -> (currentMoveSpeed / 4.8f).coerceIn(0.9f, 1.4f)
                isMoving -> (currentMoveSpeed / 2.2f).coerceIn(0.85f, 1.35f)
                else -> 1.0f
            }
            playerAnimTime += dt * playbackRate
        }

        val hasAnimations = (playerAnimator?.animationCount ?: 0) > 0
        if (hasAnimations) {
            val animIdx = animationIndices[currentAnimationName]
                ?: animationIndices[currentAnimationName.lowercase()]
                ?: 0

            val duration = playerAnimator?.getAnimationDuration(animIdx)?.coerceAtLeast(0.01f) ?: 1f
            // Locomotion and idle animations loop continuously so the player never freezes mid-stride
            val isLoop = currentAnimationName.contains("Loop", ignoreCase = true) ||
                         currentAnimationName.contains("Idle", ignoreCase = true) ||
                         currentAnimationName.contains("Walk", ignoreCase = true) ||
                         currentAnimationName.contains("Run", ignoreCase = true) ||
                         isMoving
            val timeToApply = if (isLoop) playerAnimTime % duration else playerAnimTime.coerceAtMost(duration)

            playerAnimator?.let { anim ->
                anim.applyAnimation(animIdx, timeToApply)
                if (previousAnimIdx != -1 && crossFadeTimer < crossFadeDuration) {
                    val alpha = (1.0f - (crossFadeTimer / crossFadeDuration)).coerceIn(0f, 1f)
                    val prevDuration = anim.getAnimationDuration(previousAnimIdx).coerceAtLeast(0.01f)
                    val blendPrevTime = previousAnimTime.coerceIn(0f, prevDuration)
                    anim.applyCrossFade(previousAnimIdx, blendPrevTime, alpha)
                    crossFadeTimer += dt
                    previousAnimTime += dt
                } else if (crossFadeTimer >= crossFadeDuration) {
                    previousAnimIdx = -1
                }
                anim.updateBoneMatrices()
            }
        }

        // 5. Update 3D Player Transform
        playerAsset?.let { asset ->
            val tm = modelViewer.engine.transformManager
            var inst = tm.getInstance(asset.root)
            if (inst == 0) {
                tm.create(asset.root)
                inst = tm.getInstance(asset.root)
            }
            if (inst != 0) {
                Matrix.setIdentityM(playerTransform, 0)
                Matrix.translateM(playerTransform, 0, playerPosition.x, playerPosition.y, playerPosition.z)
                // Orient character directly towards playerYawDeg (matches movement direction)
                Matrix.rotateM(playerTransform, 0, playerYawDeg, 0f, 1f, 0f)

                // If rolling, execute full 360° forward tactical combat roll
                if (isRolling) {
                    val rollProgress = (rollTimer / rollDuration).coerceIn(0f, 1f)
                    val pitchAngle = -rollProgress * 360f
                    Matrix.rotateM(playerTransform, 0, pitchAngle, 1f, 0f, 0f)
                }
                tm.setTransform(inst, playerTransform)
            }
        }

        // 6. Update Target Sentinel / Training Dummy Transform
        enemyAsset?.let { enemy ->
            val tm = modelViewer.engine.transformManager
            var inst = tm.getInstance(enemy.root)
            if (inst == 0) {
                tm.create(enemy.root)
                inst = tm.getInstance(enemy.root)
            }
            if (inst != 0) {
                Matrix.setIdentityM(enemyTransform, 0)
                Matrix.translateM(enemyTransform, 0, enemyPosition.x, enemyPosition.y, enemyPosition.z)
                Matrix.rotateM(enemyTransform, 0, 180f, 0f, 1f, 0f)
                tm.setTransform(inst, enemyTransform)
            }
        }

        // 7. Weapon transform is parented directly to skeleton bones (hand_r / spine_03)
        // via TransformManager.setParent in updateWeaponSocket(), so it automatically inherits
        // bone rotation, swing arcs, and player translation with zero manual frame offsets.

        // 8. Update Third-Person Orbit Camera
        camera3D.update(playerPosition, dt)
        val eye = camera3D.eye
        val center = camera3D.lookAt

        val w = surfaceView.width.toDouble().coerceAtLeast(1.0)
        val h = surfaceView.height.toDouble().coerceAtLeast(1.0)
        val aspect = w / h
        modelViewer.camera.setProjection(45.0, aspect, 0.1, 150.0, Camera.Fov.VERTICAL)

        modelViewer.camera.lookAt(
            eye.x.toDouble(), eye.y.toDouble(), eye.z.toDouble(),
            center.x.toDouble(), center.y.toDouble(), center.z.toDouble(),
            0.0, 1.0, 0.0
        )

        // 9. Render 3D Frame directly to avoid ModelViewer cameraManipulator overwrite
        val uiHelper = uiHelperField.get(modelViewer) as? UiHelper
        if (uiHelper?.isReadyToRender == true) {
            resourceLoader.asyncUpdateLoad()

            val swapChain = swapChainField.get(modelViewer) as? SwapChain
            if (swapChain != null && modelViewer.renderer.beginFrame(swapChain, frameTimeNanos)) {
                modelViewer.renderer.render(modelViewer.view)
                modelViewer.renderer.endFrame()
            }
        }

        val camPos = FloatArray(3)
        modelViewer.camera.getPosition(camPos)
        val camFwd = FloatArray(3)
        modelViewer.camera.getForwardVector(camFwd)
        Log.d("FilamentGameScene", "Cam after render: pos=(${camPos[0]}, ${camPos[1]}, ${camPos[2]}) fwd=(${camFwd[0]}, ${camFwd[1]}, ${camFwd[2]})")

        // 10. Request next frame
        choreographer.postFrameCallback(this)
    }
}
