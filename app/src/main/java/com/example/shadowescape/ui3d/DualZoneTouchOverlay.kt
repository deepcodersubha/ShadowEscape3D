package com.example.shadowescape.ui3d

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.example.shadowescape.engine3d.ThirdPersonOrbitCamera
import com.example.shadowescape.model.PlayerStats
import com.example.shadowescape.model.Weapon
import com.example.shadowescape.model.WieldStance
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * High-precision dual-zone touch overlay for 3D third-person Soulslike combat.
 * Left Zone: 360° Floating Analog Joystick.
 * Right Zone: Drag-to-Orbit Look + Action Button Cluster.
 */
class DualZoneTouchOverlay(context: Context) : View(context) {

    var camera: ThirdPersonOrbitCamera? = null

    // Player & Combat state callbacks
    var onLightAttack: (() -> Unit)? = null
    var onHeavyAttack: (() -> Unit)? = null
    var onDodgeRoll: (() -> Unit)? = null
    var onSprintToggle: ((Boolean) -> Unit)? = null
    var onLockOnToggle: (() -> Unit)? = null
    var onHealFlask: (() -> Unit)? = null
    var onSheathToggle: (() -> Unit)? = null

    // Locomotion Output
    var joystickX: Float = 0f  // [-1f, 1f]
    var joystickY: Float = 0f  // [-1f, 1f] (positive = forward)
    var isSprinting: Boolean = false

    // HUD Data
    var currentHp: Float = 550f
    var maxHp: Float = 600f
    var currentStamina: Float = 140f
    var maxStamina: Float = 140f
    var equippedWeapon: Weapon? = null
    var isWeaponSheathed: Boolean = true
    var isTargetLocked: Boolean = false

    // Left Zone Joystick Tracking
    private var joystickPointerId: Int = -1
    private var stickBaseX: Float = 0f
    private var stickBaseY: Float = 0f
    private var stickKnobX: Float = 0f
    private var stickKnobY: Float = 0f
    private val stickRadius: Float = 110f
    private val knobRadius: Float = 44f
    private var isStickActive: Boolean = false

    // Right Zone Camera Drag Tracking
    private var cameraPointerId: Int = -1
    private var lastTouchX: Float = 0f
    private var lastTouchY: Float = 0f
    private val lookSensitivity: Float = 0.22f

    // Action Buttons
    private val attackBtnRect = RectF()
    private val heavyBtnRect = RectF()
    private val rollBtnRect = RectF()
    private val lockBtnRect = RectF()
    private val healBtnRect = RectF()
    private val sheathBtnRect = RectF()

    private var rollTouchStartTime: Long = 0L

    // Dedicated Paints to avoid state leakage
    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#E2E8F0")
    }
    private val hudTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        color = Color.parseColor("#FDE047")
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        setupButtonLayout(w.toFloat(), h.toFloat())
    }

    private fun setupButtonLayout(w: Float, h: Float) {
        val density = resources.displayMetrics.density
        val btnRadius = 36f * density
        val margin = 24f * density

        // Primary Action: Light Attack (Large central circle in right thumb arc)
        val atkX = w - margin - btnRadius - 70f * density
        val atkY = h - margin - btnRadius - 40f * density
        attackBtnRect.set(atkX - btnRadius, atkY - btnRadius, atkX + btnRadius, atkY + btnRadius)

        // Heavy Attack: Above Light Attack
        val hvyRadius = 28f * density
        val hvyX = atkX + 15f * density
        val hvyY = atkY - btnRadius - hvyRadius - 16f * density
        heavyBtnRect.set(hvyX - hvyRadius, hvyY - hvyRadius, hvyX + hvyRadius, hvyY + hvyRadius)

        // Dodge / Sprint: To the right of Light Attack
        val rollX = atkX + btnRadius + hvyRadius + 18f * density
        val rollY = atkY + 10f * density
        rollBtnRect.set(rollX - hvyRadius, rollY - hvyRadius, rollX + hvyRadius, rollY + hvyRadius)

        // Sheath / Draw: Below Heavy Attack, left of Light Attack
        val shRadius = 24f * density
        val shX = atkX - btnRadius - shRadius - 14f * density
        val shY = atkY + 12f * density
        sheathBtnRect.set(shX - shRadius, shY - shRadius, shX + shRadius, shY + shRadius)

        // Lock-On: Above Sheath
        val lockRadius = 24f * density
        val lockX = shX + 10f * density
        val lockY = hvyY + 10f * density
        lockBtnRect.set(lockX - lockRadius, lockY - lockRadius, lockX + lockRadius, lockY + lockRadius)

        // Estus Flask: Top right utility area
        val healRadius = 26f * density
        val healX = w - margin - healRadius - 10f * density
        val healY = 80f * density
        healBtnRect.set(healX - healRadius, healY - healRadius, healX + healRadius, healY + healRadius)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val actionIndex = event.actionIndex
        val pointerId = event.getPointerId(actionIndex)
        val x = event.getX(actionIndex)
        val y = event.getY(actionIndex)

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // Check if touch hit any action button
                if (attackBtnRect.contains(x, y)) {
                    onLightAttack?.invoke()
                    invalidate()
                    return true
                }
                if (heavyBtnRect.contains(x, y)) {
                    onHeavyAttack?.invoke()
                    invalidate()
                    return true
                }
                if (sheathBtnRect.contains(x, y)) {
                    isWeaponSheathed = !isWeaponSheathed
                    onSheathToggle?.invoke()
                    invalidate()
                    return true
                }
                if (lockBtnRect.contains(x, y)) {
                    isTargetLocked = !isTargetLocked
                    onLockOnToggle?.invoke()
                    invalidate()
                    return true
                }
                if (healBtnRect.contains(x, y)) {
                    onHealFlask?.invoke()
                    invalidate()
                    return true
                }
                if (rollBtnRect.contains(x, y)) {
                    rollTouchStartTime = System.currentTimeMillis()
                    return true
                }

                // If on left side: initialize floating joystick
                if (x < width * 0.42f && joystickPointerId == -1) {
                    joystickPointerId = pointerId
                    stickBaseX = x
                    stickBaseY = y
                    stickKnobX = x
                    stickKnobY = y
                    isStickActive = true
                    invalidate()
                    return true
                }

                // If on right side: initialize camera orbit drag
                if (x >= width * 0.42f && cameraPointerId == -1) {
                    cameraPointerId = pointerId
                    lastTouchX = x
                    lastTouchY = y
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pId = event.getPointerId(i)
                    val px = event.getX(i)
                    val py = event.getY(i)

                    if (pId == joystickPointerId) {
                        val dx = px - stickBaseX
                        val dy = py - stickBaseY
                        val dist = hypot(dx, dy)

                        if (dist > 0.001f) {
                            val clampedDist = dist.coerceAtMost(stickRadius)
                            val factor = clampedDist / dist
                            stickKnobX = stickBaseX + dx * factor
                            stickKnobY = stickBaseY + dy * factor

                            // Joystick vector normalized [-1, 1]
                            joystickX = (dx * factor) / stickRadius
                            // In screen coordinates Y is down, so invert for forward
                            joystickY = -(dy * factor) / stickRadius
                        }
                        invalidate()
                    } else if (pId == cameraPointerId) {
                        val dx = px - lastTouchX
                        val dy = py - lastTouchY
                        camera?.rotate(dx * lookSensitivity, dy * lookSensitivity)
                        lastTouchX = px
                        lastTouchY = py
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (pointerId == joystickPointerId) {
                    joystickPointerId = -1
                    isStickActive = false
                    joystickX = 0f
                    joystickY = 0f
                    invalidate()
                } else if (pointerId == cameraPointerId) {
                    cameraPointerId = -1
                }

                if (rollBtnRect.contains(x, y)) {
                    val duration = System.currentTimeMillis() - rollTouchStartTime
                    if (duration > 350) {
                        // Long press = Sprint toggle
                        isSprinting = !isSprinting
                        onSprintToggle?.invoke(isSprinting)
                    } else {
                        // Quick tap = Dodge Roll
                        onDodgeRoll?.invoke()
                    }
                    invalidate()
                }
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Draw Virtual Joystick (Left Thumb)
        if (isStickActive) {
            // Outer ring
            hudPaint.color = Color.parseColor("#44101A28")
            hudPaint.style = Paint.Style.FILL
            canvas.drawCircle(stickBaseX, stickBaseY, stickRadius, hudPaint)

            borderPaint.color = Color.parseColor("#8838BDF8")
            borderPaint.strokeWidth = 3f
            canvas.drawCircle(stickBaseX, stickBaseY, stickRadius, borderPaint)

            // Inner knob
            hudPaint.color = Color.parseColor("#CC00E5FF")
            canvas.drawCircle(stickKnobX, stickKnobY, knobRadius, hudPaint)
            borderPaint.color = Color.WHITE
            borderPaint.strokeWidth = 2.5f
            canvas.drawCircle(stickKnobX, stickKnobY, knobRadius, borderPaint)
        }

        // 2. Draw Action Buttons
        drawActionButton(canvas, attackBtnRect, "⚔️", "ATTACK", "#990369A1", "#38BDF8")
        drawActionButton(canvas, heavyBtnRect, "💥", "HEAVY", "#997C2D12", "#FB923C")
        drawActionButton(canvas, rollBtnRect, if (isSprinting) "⚡" else "🌀", if (isSprinting) "SPRINT" else "ROLL", "#991E293B", "#94A3B8")
        drawActionButton(canvas, sheathBtnRect, if (isWeaponSheathed) "🗡️" else "✨", if (isWeaponSheathed) "DRAW" else "SHEATH", "#99334155", "#CBD5E1")
        drawActionButton(canvas, lockBtnRect, "🎯", "LOCK", if (isTargetLocked) "#99B91C1C" else "#991E293B", if (isTargetLocked) "#EF4444" else "#64748B")
        drawActionButton(canvas, healBtnRect, "🧪", "FLASK", "#99065F46", "#34D399")

        // 3. Draw Top-Left Soulslike HUD: HP & Stamina Bars
        drawSoulslikeHUD(canvas)
    }

    private fun drawActionButton(
        canvas: Canvas,
        rect: RectF,
        icon: String,
        label: String,
        bgColorHex: String,
        borderColorHex: String
    ) {
        hudPaint.color = Color.parseColor(bgColorHex)
        hudPaint.style = Paint.Style.FILL
        canvas.drawRoundRect(rect, rect.width() / 2f, rect.height() / 2f, hudPaint)

        borderPaint.color = Color.parseColor(borderColorHex)
        borderPaint.strokeWidth = 2.5f
        canvas.drawRoundRect(rect, rect.width() / 2f, rect.height() / 2f, borderPaint)

        // Mathematically centered Emoji Icon
        val iconSize = rect.width() * 0.35f
        iconPaint.textSize = iconSize
        val iconCenterY = rect.centerY() - rect.height() * 0.10f
        val iconBaseline = iconCenterY - (iconPaint.fontMetrics.ascent + iconPaint.fontMetrics.descent) / 2f
        canvas.drawText(icon, rect.centerX(), iconBaseline, iconPaint)

        // Mathematically centered Text Label
        val labelSize = (9.5f * resources.displayMetrics.density).coerceAtMost(rect.width() * 0.20f)
        labelPaint.textSize = labelSize
        val labelCenterY = rect.centerY() + rect.height() * 0.28f
        val labelBaseline = labelCenterY - (labelPaint.fontMetrics.ascent + labelPaint.fontMetrics.descent) / 2f
        canvas.drawText(label, rect.centerX(), labelBaseline, labelPaint)
    }

    private fun drawSoulslikeHUD(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val startX = 24f * density
        var curY = 24f * density

        // HP Bar
        val barW = 260f * density
        val barH = 14f * density
        hudPaint.color = Color.parseColor("#551E293B")
        canvas.drawRoundRect(startX, curY, startX + barW, curY + barH, 4f, 4f, hudPaint)
        val hpRatio = (currentHp / maxHp).coerceIn(0f, 1f)
        hudPaint.color = Color.parseColor("#DC2626")
        canvas.drawRoundRect(startX, curY, startX + barW * hpRatio, curY + barH, 4f, 4f, hudPaint)
        borderPaint.color = Color.parseColor("#99EF4444")
        borderPaint.strokeWidth = 1.5f
        canvas.drawRoundRect(startX, curY, startX + barW, curY + barH, 4f, 4f, borderPaint)

        // Stamina Bar
        curY += barH + 6f * density
        val stamW = barW * 0.78f
        val stamH = 10f * density
        hudPaint.color = Color.parseColor("#551E293B")
        canvas.drawRoundRect(startX, curY, startX + stamW, curY + stamH, 4f, 4f, hudPaint)
        val stamRatio = (currentStamina / maxStamina).coerceIn(0f, 1f)
        hudPaint.color = Color.parseColor("#16A34A")
        canvas.drawRoundRect(startX, curY, startX + stamW * stamRatio, curY + stamH, 4f, 4f, hudPaint)
        borderPaint.color = Color.parseColor("#9922C55E")
        canvas.drawRoundRect(startX, curY, startX + stamW, curY + stamH, 4f, 4f, borderPaint)

        // Weapon Stance & Stat Info
        equippedWeapon?.let { wpn ->
            curY += stamH + 16f * density
            val stanceText = if (wpn.isTwoHanded) "⚔️ 2-HANDED" else "🗡️ 1-HANDED"
            hudTextPaint.textSize = 12f * density
            hudTextPaint.color = Color.parseColor("#FDE047")
            canvas.drawText("${wpn.name}  [$stanceText]", startX, curY, hudTextPaint)
        }
    }
}
