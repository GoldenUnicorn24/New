package de.goldenunicorn.kronenkrieg.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

const val GAIA = -1

abstract class Entity(val id: Int, var owner: Int) {
    var x = 0f
    var y = 0f
    var hp = 1f
    var maxHp = 1f
    var alive = true
    /** Zeitpunkt des letzten erlittenen Angriffs (Spielzeit). */
    var lastHitTime = -100f
    var lastHitBy = 0

    abstract val radius: Float

    /** Abstand vom Punkt zur Kante dieses Objekts. */
    open fun edgeDistance(px: Float, py: Float): Float {
        val dx = px - x
        val dy = py - y
        return max(0f, sqrt(dx * dx + dy * dy) - radius)
    }
}

enum class Order { IDLE, MOVE, ATTACK, GATHER, RETURN, BUILD }

class GUnit(id: Int, owner: Int, val kind: UnitKind, var stats: UnitStats) : Entity(id, owner) {
    override val radius: Float get() = if (kind == UnitKind.CAVALRY || kind == UnitKind.SIEGE) 0.42f else 0.32f

    var order = Order.IDLE
    var targetId = 0
    /** Für Bäume: Kachel des Ziels. */
    var treeX = -1
    var treeY = -1
    var gatherRes: Res? = null
    var carryRes: Res? = null
    var carry = 0f
    var cooldown = 0f
    var attackMove = false
    var goalX = 0f
    var goalY = 0f

    var path: IntArray? = null
    var pathIdx = 0
    var repathTimer = 0f
    var stuckTimer = 0f
    var lastTargetX = 0f
    var lastTargetY = 0f
    var facing = 0f
    var animTime = 0f
    var working = false
    var homeX = 0f
    var homeY = 0f
    var idleTime = 0f

    fun clearPath() {
        path = null
        pathIdx = 0
    }
}

class QueueItem(val kind: UnitKind?, val isAgeUp: Boolean, var time: Float, val totalTime: Float, val cost: Cost)

class Building(id: Int, owner: Int, val kind: BuildingKind, var stats: BuildingStats, val tx: Int, val ty: Int) :
    Entity(id, owner) {
    val size: Int get() = stats.size
    override val radius: Float get() = size / 2f

    var complete = false
    var progress = 0f
    val queue = ArrayList<QueueItem>()
    var rallyX = -1f
    var rallyY = -1f
    var cooldown = 0f
    /** Belegt (z. B. Feld von Arbeiter-ID). */
    var farmer = 0

    init {
        x = tx + size / 2f
        y = ty + size / 2f
    }

    override fun edgeDistance(px: Float, py: Float): Float {
        val dx = max(max(tx - px, 0f), px - (tx + size))
        val dy = max(max(ty - py, 0f), py - (ty + size))
        return sqrt(dx * dx + dy * dy)
    }

    fun contains(px: Float, py: Float): Boolean = px >= tx && py >= ty && px < tx + size && py < ty + size
}

class ResourceNode(id: Int, val res: Res, val tx: Int, val ty: Int, var amount: Float) : Entity(id, GAIA) {
    override val radius: Float get() = 0.5f
    val maxAmount = amount

    init {
        x = tx + 0.5f
        y = ty + 0.5f
    }

    override fun edgeDistance(px: Float, py: Float): Float {
        val dx = max(max(tx - px, 0f), px - (tx + 1))
        val dy = max(max(ty - py, 0f), py - (ty + 1))
        return sqrt(dx * dx + dy * dy)
    }
}

class Projectile(
    val fromX: Float, val fromY: Float, var x: Float, var y: Float,
    val targetId: Int, val damage: Int, val sourceOwner: Int, val sourceId: Int, val speed: Float, val heavy: Boolean
) {
    var alive = true
    var tx = 0f
    var ty = 0f
}

enum class EffectKind { CORPSE, RUBBLE, HIT, DUST }

class Effect(val kind: EffectKind, val x: Float, val y: Float, val owner: Int, val duration: Float, val size: Float) {
    var t = 0f
}

fun clampF(v: Float, lo: Float, hi: Float) = min(hi, max(lo, v))
