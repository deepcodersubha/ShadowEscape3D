package com.example.shadowescape.engine3d

import android.util.Log
import com.google.android.filament.Scene
import com.google.android.filament.gltfio.FilamentAsset

enum class ArmorSet {
    RANGER,
    PEASANT,
    HYBRID
}

class ModularEquipmentManager(
    private val scene: Scene,
    private val playerAsset: FilamentAsset
) {
    private val TAG = "ModularEquipment"

    // Modular part entity lists
    private val hoodEntities = mutableListOf<Int>()
    private val pauldronEntities = mutableListOf<Int>()
    private val rangerBodyEntities = mutableListOf<Int>()
    private val rangerArmsEntities = mutableListOf<Int>()
    private val rangerLegsEntities = mutableListOf<Int>()
    private val rangerFeetEntities = mutableListOf<Int>()

    private val peasantBodyEntities = mutableListOf<Int>()
    private val peasantArmsEntities = mutableListOf<Int>()
    private val peasantLegsEntities = mutableListOf<Int>()
    private val peasantFeetEntities = mutableListOf<Int>()

    var isHoodEquipped: Boolean = true
        private set
    var isPauldronEquipped: Boolean = true
        private set
    var currentSet: ArmorSet = ArmorSet.RANGER
        private set

    init {
        val renderables = playerAsset.renderableEntities
        Log.d(TAG, "Initializing with ${renderables.size} renderable entities")
        for (entity in renderables) {
            val name = playerAsset.getName(entity) ?: ""
            Log.d(TAG, "Entity $entity name='$name'")
            when {
                name.contains("Hood", ignoreCase = true) -> hoodEntities.add(entity)
                name.contains("Pauldron", ignoreCase = true) -> pauldronEntities.add(entity)
                name.contains("Ranger_Body", ignoreCase = true) || name.contains("Belt", ignoreCase = true) -> rangerBodyEntities.add(entity)
                name.contains("Ranger_Arms", ignoreCase = true) || name.contains("Bracer", ignoreCase = true) -> rangerArmsEntities.add(entity)
                name.contains("Ranger_Legs", ignoreCase = true) -> rangerLegsEntities.add(entity)
                name.contains("Ranger_Feet", ignoreCase = true) || name.contains("Boots", ignoreCase = true) -> rangerFeetEntities.add(entity)

                name.contains("Peasant_Body", ignoreCase = true) -> peasantBodyEntities.add(entity)
                name.contains("Peasant_Arms", ignoreCase = true) -> peasantArmsEntities.add(entity)
                name.contains("Peasant_Legs", ignoreCase = true) -> peasantLegsEntities.add(entity)
                name.contains("Peasant_Feet", ignoreCase = true) -> peasantFeetEntities.add(entity)
            }
        }
        Log.d(TAG, "Slots: Hood=${hoodEntities.size}, Pauldron=${pauldronEntities.size}, RangerBody=${rangerBodyEntities.size}, PeasantBody=${peasantBodyEntities.size}")

        // Default setup: Equip full Ranger armor, hide Peasant armor
        equipArmorSet(ArmorSet.RANGER)
    }

    fun equipArmorSet(set: ArmorSet) {
        currentSet = set
        when (set) {
            ArmorSet.RANGER -> {
                // Show Ranger
                setEntitiesVisible(rangerBodyEntities, true)
                setEntitiesVisible(rangerArmsEntities, true)
                setEntitiesVisible(rangerLegsEntities, true)
                setEntitiesVisible(rangerFeetEntities, true)
                setEntitiesVisible(hoodEntities, isHoodEquipped)
                setEntitiesVisible(pauldronEntities, isPauldronEquipped)

                // Hide Peasant
                setEntitiesVisible(peasantBodyEntities, false)
                setEntitiesVisible(peasantArmsEntities, false)
                setEntitiesVisible(peasantLegsEntities, false)
                setEntitiesVisible(peasantFeetEntities, false)
            }
            ArmorSet.PEASANT -> {
                // Show Peasant
                setEntitiesVisible(peasantBodyEntities, true)
                setEntitiesVisible(peasantArmsEntities, true)
                setEntitiesVisible(peasantLegsEntities, true)
                setEntitiesVisible(peasantFeetEntities, true)

                // Hide Ranger body parts, but keep Head Hood equipped so head is never hollow
                setEntitiesVisible(rangerBodyEntities, false)
                setEntitiesVisible(rangerArmsEntities, false)
                setEntitiesVisible(rangerLegsEntities, false)
                setEntitiesVisible(rangerFeetEntities, false)
                setEntitiesVisible(hoodEntities, isHoodEquipped)
                setEntitiesVisible(pauldronEntities, false)
            }
            ArmorSet.HYBRID -> {
                // Ranger Hood & Boots + Peasant Tunic & Trousers
                setEntitiesVisible(hoodEntities, isHoodEquipped)
                setEntitiesVisible(pauldronEntities, false)
                setEntitiesVisible(peasantBodyEntities, true)
                setEntitiesVisible(peasantArmsEntities, true)
                setEntitiesVisible(peasantLegsEntities, true)
                setEntitiesVisible(rangerFeetEntities, true)

                setEntitiesVisible(rangerBodyEntities, false)
                setEntitiesVisible(rangerArmsEntities, false)
                setEntitiesVisible(rangerLegsEntities, false)
                setEntitiesVisible(peasantFeetEntities, false)
            }
        }
    }

    fun toggleHood(): Boolean {
        isHoodEquipped = !isHoodEquipped
        if (currentSet == ArmorSet.RANGER || currentSet == ArmorSet.HYBRID) {
            setEntitiesVisible(hoodEntities, isHoodEquipped)
        }
        return isHoodEquipped
    }

    fun togglePauldron(): Boolean {
        isPauldronEquipped = !isPauldronEquipped
        if (currentSet == ArmorSet.RANGER) {
            setEntitiesVisible(pauldronEntities, isPauldronEquipped)
        }
        return isPauldronEquipped
    }

    fun toggleArmorSet(): ArmorSet {
        val next = when (currentSet) {
            ArmorSet.RANGER -> ArmorSet.PEASANT
            ArmorSet.PEASANT -> ArmorSet.HYBRID
            ArmorSet.HYBRID -> ArmorSet.RANGER
        }
        equipArmorSet(next)
        return next
    }

    private fun setEntitiesVisible(entities: List<Int>, visible: Boolean) {
        for (entity in entities) {
            if (entity != 0) {
                if (visible) {
                    scene.addEntity(entity)
                } else {
                    scene.removeEntity(entity)
                }
            }
        }
    }
}
