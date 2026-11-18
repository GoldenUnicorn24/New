package de.goldenunicorn.kronenkrieg.engine

import kotlin.math.roundToInt

enum class Res(val label: String, val short: String) {
    FOOD("Nahrung", "N"), WOOD("Holz", "H"), GOLD("Gold", "G"), STONE("Stein", "S")
}

class Cost(val food: Int = 0, val wood: Int = 0, val gold: Int = 0, val stone: Int = 0) {
    operator fun get(r: Res): Int = when (r) {
        Res.FOOD -> food
        Res.WOOD -> wood
        Res.GOLD -> gold
        Res.STONE -> stone
    }

    fun scaled(m: Float): Cost = Cost(
        (food * m).roundToInt(), (wood * m).roundToInt(),
        (gold * m).roundToInt(), (stone * m).roundToInt()
    )

    fun label(): String {
        val parts = ArrayList<String>()
        for (r in Res.values()) if (this[r] > 0) parts.add("${this[r]} ${r.short}")
        return if (parts.isEmpty()) "gratis" else parts.joinToString(" ")
    }
}

enum class UnitKind {
    WORKER, INFANTRY, SPEAR, ARCHER, CAVALRY, SPECIAL, SIEGE;

    val isMilitary: Boolean get() = this != WORKER
}

/** Grobe Kategorie für Boni im Kampf. */
enum class UnitClass { CIVIL, FOOT, RANGED, MOUNTED, SIEGE }

class UnitStats(
    val kind: UnitKind,
    val name: String,
    val unitClass: UnitClass,
    val hp: Int,
    val attack: Int,
    val armor: Int,
    val range: Float,
    val speed: Float,
    val sight: Float,
    val cooldown: Float,
    val cost: Cost,
    val trainTime: Float,
    val bonusVsMounted: Int = 0,
    val bonusVsBuilding: Int = 0,
    val bonusVsFoot: Int = 0,
    val bonusVsRanged: Int = 0,
    val canAttackUnits: Boolean = true,
    val age: Int = 1,
    val description: String = ""
) {
    val isRanged: Boolean get() = range > 1.6f
}

enum class BuildingKind {
    TOWNHALL, HOUSE, STOREHOUSE, FARM, BARRACKS, RANGE, STABLE, TOWER, WORKSHOP
}

class BuildingStats(
    val kind: BuildingKind,
    val name: String,
    val hp: Int,
    val size: Int,
    val cost: Cost,
    val buildTime: Float,
    val pop: Int,
    val sight: Float,
    val age: Int,
    val trains: List<UnitKind>,
    val dropoff: Boolean,
    val attack: Int,
    val range: Float,
    val cooldown: Float,
    val walkable: Boolean,
    val armor: Int,
    val description: String
)

class AgeInfo(val number: Int, val name: String, val cost: Cost, val time: Float, val description: String)

object Rules {
    const val TICK = 0.05f
    const val CARRY = 10
    const val MAX_POP = 150
    const val QUEUE_MAX = 5

    val ages = listOf(
        AgeInfo(1, "Zeit der Gründer", Cost(), 0f, "Der Anfang eures Reiches."),
        AgeInfo(2, "Zeit der Festen", Cost(food = 400, gold = 50), 50f,
            "Schaltet Schießplatz, Stall und Wachturm frei. Sammeln +10 %."),
        AgeInfo(3, "Zeit der Kronen", Cost(food = 800, gold = 350), 80f,
            "Schaltet Elite-Einheit und Belagerung frei. Armee +1 Angriff, +1 Rüstung.")
    )

    /** Grundwerte der Sammelraten pro Sekunde. */
    fun baseGatherRate(r: Res, fromFarm: Boolean): Float = when (r) {
        Res.FOOD -> if (fromFarm) 0.36f else 0.48f
        Res.WOOD -> 0.42f
        Res.GOLD -> 0.38f
        Res.STONE -> 0.36f
    }

    val startRes = Cost(food = 200, wood = 220, gold = 100, stone = 150)
}
