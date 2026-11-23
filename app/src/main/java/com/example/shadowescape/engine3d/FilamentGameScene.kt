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
import com.example.shadowescape.audio.SoundManager
import com.example.shadowescape.model.PlayerStats
import com.example.shadowescape.model.Weapon
import com.example.shadowescape.model.Weapons
import com.example.shadowescape.model.WeaponType
import com.example.shadowescape.physics3d.ArrowProjectile3D
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
    private val soundManager = SoundManager(context)
    var equippedWeapon: Weapon = Weapons.STEEL_BROADSWORD
    var isAttacking: Boolean = false
    var attackTimer: Float = 0f
    var currentAttackDuration: Float = 1.0f
    var currentAttackAnimName: String = "Slash_Inward"
    var comboStep: Int = 0
    var comboQueued: Boolean = false
    private var pendingDamage: Float = 0f
    private var pendingIsHeavy: Boolean = false
    private var hitWindowTime: Float = 0.20f
    private var hasHitRegistered: Boolean = false
    private var hasFiredArrowThisAttack: Boolean = false
    private val activeArrows = mutableListOf<ArrowProjectile3D>()

    // Modular Armor & Weapon Equipment Systems
    var equipmentManager: ModularEquipmentManager? = null
        private set
    private var currentWeaponType: Int = 1 // 0 = Longbow, 1 = Broadsword, 2 = Katana

    // Animation Cross-Fading & State Blending
    private var previousAnimIdx: Int = -1
    private var previousAnimTime: Float = 0f
    private var crossFadeTimer: Float = 0f
    private var crossFadeDuration: Float = 0.20f // Dynamic crossfade blend duration

    var isDodging: Boolean = false
    var dodgeTimer: Float = 0f
    var currentDodgeDuration: Float = 0.85f
    var currentDodgeAnimName: String = "Dodge_Fwd"
    var dodgeVelocity: Vector3 = Vector3.ZERO

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
        overlay.currentWeaponName = "SWORD"
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

        // Attach Steel Broadsword weapon by default
        attachWeapon("models/weapon_broadsword.glb")
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
        hasFiredArrowThisAttack = false
        if (overlay.isWeaponSheathed) {
            overlay.isWeaponSheathed = false
            updateWeaponSocket()
            updateShieldSocket()
        }

        val isBow = equippedWeapon.type == WeaponType.BOW
        if (isBow) {
            when (step) {
                1 -> {
                    currentAttackAnimName = "Draw_Arrow"
                    currentAttackDuration = 0.80f
                    crossFadeDuration = 0.18f
                    stats.useStamina(10f)
                    pendingDamage = equippedWeapon.calculateEffectiveDamage(stats)
                    pendingIsHeavy = false
                    hitWindowTime = 0.40f
                }
                2 -> {
                    currentAttackAnimName = "Aim_Hold"
                    currentAttackDuration = 0.70f
                    crossFadeDuration = 0.15f
                    stats.useStamina(12f)
                    pendingDamage = equippedWeapon.calculateEffectiveDamage(stats) * 1.30f
                    pendingIsHeavy = false
                    hitWindowTime = 0.35f
                }
                3 -> {
                    currentAttackAnimName = "Shoot_Recoil"
                    currentAttackDuration = 0.60f
                    crossFadeDuration = 0.10f
                    stats.useStamina(16f)
                    pendingDamage = equippedWeapon.calculateEffectiveDamage(stats) * 1.85f
                    pendingIsHeavy = false
                    hitWindowTime = 0.15f
                }
            }
        } else {
            // Mixamo 3-strike sword combo: Inward Slash -> Outward Return -> Downward Finisher
            when (step) {
                1 -> {
                    currentAttackAnimName = "Slash_Inward"
                    currentAttackDuration = 0.72f
                    crossFadeDuration = 0.15f
                    stats.useStamina(10f)
                    pendingDamage = equippedWeapon.calculateEffectiveDamage(stats)
                    pendingIsHeavy = false
                    hitWindowTime = 0.38f
                    soundManager.playSlash(1.0f)
                }
                2 -> {
                    currentAttackAnimName = "Slash_Outward"
                    currentAttackDuration = 0.75f
                    crossFadeDuration = 0.10f // Fast 100ms combo link
                    stats.useStamina(12f)
                    pendingDamage = equippedWeapon.calculateEffectiveDamage(stats) * 1.35f
                    pendingIsHeavy = false
                    hitWindowTime = 0.42f
                    soundManager.playSlash(1.15f)
                }
                3 -> {
                    currentAttackAnimName = "Slash_Downward"
                    currentAttackDuration = 0.88f
                    crossFadeDuration = 0.10f // Fast 100ms combo link
                    stats.useStamina(18f)
                    pendingDamage = equippedWeapon.calculateEffectiveDamage(stats) * 1.85f
                    pendingIsHeavy = false
                    hitWindowTime = 0.38f
                    soundManager.playSlash(0.85f)
                }
            }
        }
        Log.i("FilamentGameScene", ">>> COMBO STRIKE $step: anim=$currentAttackAnimName, wpn=${equippedWeapon.name}")
    }

    private fun spawnArrow() {
        val arrowAsset = loadGlb("models/weapon_arrow.glb") ?: return

        // 1. Arrow spawn point: chest / bow grip height
        val rad = Math.toRadians(playerYawDeg.toDouble())
        val fwdX = kotlin.math.sin(rad).toFloat()
        val fwdZ = kotlin.math.cos(rad).toFloat()

        val spawnPos = Vector3(
            playerPosition.x + fwdX * 0.35f,
            playerPosition.y + 1.25f,
            playerPosition.z + fwdZ * 0.35f
        )

        // 2. Flight Direction: Aim at locked target or straight ahead with slight loft
        val flightDir = if (camera3D.isLockedOn && camera3D.lockTarget != null) {
            val target = Vector3(enemyPosition.x, enemyPosition.y + 1.15f, enemyPosition.z)
            (target - spawnPos).normalized()
        } else {
            Vector3(fwdX, 0.035f, fwdZ).normalized()
        }

        val arrowSpeed = 38.0f // 38 m/s
        val velocity = flightDir * arrowSpeed
        val dmg = pendingDamage

        activeArrows.add(ArrowProjectile3D(
            asset = arrowAsset,
            position = spawnPos,
            velocity = velocity,
            damage = dmg,
            gravity = 5.2f,
            lifetime = 3.5f
        ))

        soundManager.playSlash(1.35f) // Bow twang / whoosh
        Log.i("FilamentGameScene", ">>> ARROW FIRED! Pos=(${spawnPos.x}, ${spawnPos.y}, ${spawnPos.z}), Vel=(${velocity.x}, ${velocity.y}, ${velocity.z})")
    }

    private fun wireOverlayCallbacks() {
        overlay.onLightAttack = {
            if (!isDodging && stats.currentStamina >= 10f) {
                // If locked on, orient player towards target before striking
                if (camera3D.isLockedOn) {
                    val toEnemyX = enemyPosition.x - playerPosition.x
                    val toEnemyZ = enemyPosition.z - playerPosition.z
                    playerYawDeg = Math.toDegrees(atan2(toEnemyX.toDouble(), toEnemyZ.toDouble())).toFloat()
                }

                if (!isAttacking) {
                    executeComboStrike(1)
                } else {
                    val isBow = equippedWeapon.type == WeaponType.BOW
                    if (!isBow) {
                        if (comboStep == 1) {
                            if (attackTimer >= 0.42f) {
                                executeComboStrike(2)
                            } else {
                                comboQueued = true
                            }
                        } else if (comboStep == 2) {
                            if (attackTimer >= 0.45f) {
                                executeComboStrike(3)
                            } else {
                                comboQueued = true
                            }
                        }
                    } else {
                        comboQueued = true
                    }
                }
            }
        }

        overlay.onHeavyAttack = {
            if (!isDodging && stats.currentStamina >= 25f && !isAttacking) {
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
                val isBow = equippedWeapon.type == WeaponType.BOW
                if (isBow) {
                    currentAttackAnimName = "Shoot_Recoil"
                    currentAttackDuration = 0.70f
                    hitWindowTime = 0.15f
                } else {
                    currentAttackAnimName = "Slash_Downward"
                    currentAttackDuration = 0.90f
                    hitWindowTime = 0.35f
                    soundManager.playSlash(0.80f)
                }
                attackTimer = 0f
                hasHitRegistered = false
                stats.useStamina(25f)

                pendingDamage = equippedWeapon.calculateEffectiveDamage(stats) * 2.2f
                pendingIsHeavy = true
            }
        }

        overlay.onDodgeRoll = {
            if (!isDodging && stats.currentStamina >= 15f) {
                isDodging = true
                isAttacking = false // Dodge cancels attack
                comboStep = 0
                comboQueued = false
                dodgeTimer = 0f
                stats.useStamina(15f)

                var joyX = overlay.joystickX
                var joyY = overlay.joystickY
                var stickMagSq = joyX * joyX + joyY * joyY

                // Support quick-tap grace period: if stick was deflected within last 250ms, use that direction
                if (stickMagSq < 0.05f && (System.currentTimeMillis() - overlay.lastActiveJoystickTime) < 250L) {
                    val lastSq = overlay.lastActiveJoystickX * overlay.lastActiveJoystickX + overlay.lastActiveJoystickY * overlay.lastActiveJoystickY
                    if (lastSq >= 0.05f) {
                        joyX = overlay.lastActiveJoystickX
                        joyY = overlay.lastActiveJoystickY
                        stickMagSq = lastSq
                    }
                }

                val fwd = camera3D.horizontalForward.normalized()
                val right = camera3D.horizontalRight.normalized()

                if (stickMagSq < 0.05f) {
                    // Neutral / No joystick input: backward leap dodge retreat
                    currentDodgeAnimName = "Dodge_Bwd"
                    currentDodgeDuration = 0.95f
                    dodgeVelocity = (fwd * -1f) * 4.8f
                } else {
                    val absX = kotlin.math.abs(joyX)
                    val absY = kotlin.math.abs(joyY)

                    if (absY >= absX) {
                        if (joyY > 0f) {
                            // Forward dash dodge
                            currentDodgeAnimName = "Dodge_Fwd"
                            currentDodgeDuration = 0.85f
                            dodgeVelocity = fwd * 6.5f
                            playerYawDeg = Math.toDegrees(atan2(fwd.x.toDouble(), fwd.z.toDouble())).toFloat()
                        } else {
                            // Backward leap dodge
                            currentDodgeAnimName = "Dodge_Bwd"
                            currentDodgeDuration = 0.95f
                            dodgeVelocity = (fwd * -1f) * 5.0f
                        }
                    } else {
                        if (joyX < 0f) {
                            // Left sidestep dodge
                            currentDodgeAnimName = "Dodge_Left"
                            currentDodgeDuration = 0.80f
                            dodgeVelocity = (right * -1f) * 5.2f
                        } else {
                            // Right sidestep dodge
                            currentDodgeAnimName = "Dodge_Right"
                            currentDodgeDuration = 0.80f
                            dodgeVelocity = right * 5.2f
                        }
                    }
                }
                Log.i("FilamentGameScene", ">>> DODGE TRIGGERED: anim=$currentDodgeAnimName, joy=($joyX, $joyY), vel=(${dodgeVelocity.x}, ${dodgeVelocity.z})")
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
            if (!isAttacking && !isDodging) {
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
        if (dist <= 2.6f) {
            enemyHp = (enemyHp - dmg).coerceAtLeast(0f)
            soundManager.playSlash(if (isHeavy) 0.8f else 1.1f)
            Log.i("FilamentGameScene", ">>> MELEE HIT ENEMY! Damage: $dmg, remaining enemyHp: $enemyHp")
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
        for (arrow in activeArrows) {
            modelViewer.scene.removeEntities(arrow.asset.entities)
            modelViewer.scene.removeEntity(arrow.asset.root)
            assetLoader.destroyAsset(arrow.asset)
        }
        activeArrows.clear()

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

        if (isDodging) {
            dodgeTimer += dt
            val progress = (dodgeTimer / currentDodgeDuration).coerceIn(0f, 1f)
            val speedDecay = (1.0f - progress * 0.65f).coerceAtLeast(0.20f)
            playerPosition.add(dodgeVelocity.x * speedDecay * dt, 0f, dodgeVelocity.z * speedDecay * dt)
            if (dodgeTimer >= currentDodgeDuration) {
                isDodging = false
            }
        } else if (isAttacking) {
            attackTimer += dt

            // Synchronized arrow spawn at bowstring release
            if (currentAttackAnimName == "Shoot_Recoil") {
                if (!hasFiredArrowThisAttack && attackTimer >= 0.12f) {
                    hasFiredArrowThisAttack = true
                    spawnArrow()
                }
            } else {
                // Synchronized melee hit registration
                if (!hasHitRegistered && attackTimer >= hitWindowTime) {
                    hasHitRegistered = true
                    checkHit(pendingDamage, pendingIsHeavy)
                }
            }

            // Small forward lunge on sword strikes for authentic melee momentum
            if (currentAttackAnimName.startsWith("Slash_") && attackTimer < 0.25f) {
                val lungeSpeed = when (comboStep) {
                    1 -> 1.0f
                    2 -> 1.2f
                    3 -> 1.8f
                    else -> 0.8f
                } * (1.0f - attackTimer / 0.25f)
                val rad = Math.toRadians(playerYawDeg.toDouble())
                val lungeX = kotlin.math.sin(rad).toFloat() * lungeSpeed * dt
                val lungeZ = kotlin.math.cos(rad).toFloat() * lungeSpeed * dt
                playerPosition.add(lungeX, 0f, lungeZ)
            }

            // Check if user buffered next combo attack during windup
            if (comboQueued && attackTimer >= 0.20f) {
                if (comboStep == 1 && stats.currentStamina >= 12f) {
                    executeComboStrike(2)
                } else if (comboStep == 2 && stats.currentStamina >= 18f) {
                    executeComboStrike(3)
                } else {
                    comboQueued = false
                }
            }

            // Archery combo flow: if player drew arrow (step 1) and didn't hold, smoothly release into shoot (step 3)
            val isArchery = equippedWeapon.type == WeaponType.BOW
            if (isArchery && comboStep == 1 && !comboQueued && attackTimer >= currentAttackDuration) {
                executeComboStrike(3)
            } else if (isArchery && comboStep == 2 && !comboQueued && attackTimer >= currentAttackDuration) {
                executeComboStrike(3)
            } else {
                val canCancelRecovery = attackTimer >= (currentAttackDuration * 0.60f).coerceAtLeast(0.30f)
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
        }

        // Player directional movement via virtual joystick with dynamic walk/run speeds
        val isMoving = stickMagSq > 0.03f && !isDodging && !isAttacking
        val stickMag = kotlin.math.sqrt(stickMagSq).coerceIn(0f, 1f)
        // Natural run: joystick pushed past 60% outer deflection OR holding Sprint button
        val isRunning = isMoving && (overlay.isSprinting || stickMag > 0.60f) && stats.currentStamina > 3f
        var currentMoveSpeed = 0f

        if (!isDodging && !isAttacking && isMoving) {
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

        // 3b. Update Active 3D Arrow Projectiles
        val tm = modelViewer.engine.transformManager
        val arrowIter = activeArrows.iterator()
        while (arrowIter.hasNext()) {
            val arrow = arrowIter.next()
            arrow.update(dt)

            var inst = tm.getInstance(arrow.asset.root)
            if (inst == 0) {
                tm.create(arrow.asset.root)
                inst = tm.getInstance(arrow.asset.root)
            }
            if (inst != 0) {
                val arrowMat = FloatArray(16)
                Matrix.setIdentityM(arrowMat, 0)
                Matrix.translateM(arrowMat, 0, arrow.position.x, arrow.position.y, arrow.position.z)

                // Orient arrow along velocity vector
                val yaw = Math.toDegrees(atan2(arrow.velocity.x.toDouble(), arrow.velocity.z.toDouble())).toFloat()
                val horizSpeed = kotlin.math.sqrt(arrow.velocity.x * arrow.velocity.x + arrow.velocity.z * arrow.velocity.z)
                val pitch = Math.toDegrees(atan2(arrow.velocity.y.toDouble(), horizSpeed.toDouble())).toFloat()

                Matrix.rotateM(arrowMat, 0, yaw, 0f, 1f, 0f)
                Matrix.rotateM(arrowMat, 0, -pitch, 1f, 0f, 0f)
                tm.setTransform(inst, arrowMat)
            }

            // Hit detection against Enemy
            val enemyCenter = Vector3(enemyPosition.x, enemyPosition.y + 1.1f, enemyPosition.z)
            val distToEnemy = arrow.position.distanceTo(enemyCenter)
            if (!arrow.hasHitTarget && distToEnemy <= 1.15f) {
                arrow.hasHitTarget = true
                arrow.isDead = true
                enemyHp = (enemyHp - arrow.damage).coerceAtLeast(0f)
                soundManager.playEnemyHit()
                Log.i("FilamentGameScene", ">>> ARROW HIT ENEMY! Damage: ${arrow.damage}, remaining enemyHp: $enemyHp")
            }

            // Cleanup dead arrows
            if (arrow.isDead) {
                modelViewer.scene.removeEntities(arrow.asset.entities)
                modelViewer.scene.removeEntity(arrow.asset.root)
                assetLoader.destroyAsset(arrow.asset)
                arrowIter.remove()
                Log.d("FilamentGameScene", "Arrow removed from scene.")
            }
        }

        // 4. Update Skeletal Animation
        val targetAnim = when {
            isDodging -> currentDodgeAnimName
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
                isAttacking && currentAnimationName.startsWith("Slash_") -> 1.35f
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
