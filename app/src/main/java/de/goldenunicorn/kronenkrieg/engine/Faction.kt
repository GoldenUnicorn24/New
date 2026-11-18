package de.goldenunicorn.kronenkrieg.engine

import kotlin.math.roundToInt

/**
 * Die vier Völker der Welt Eldmar. Alle Namen und Hintergründe sind eigene Schöpfungen.
 */
enum class FactionId { MENSCHEN, ELBEN, ORKS, EISENBRUT }

class Faction(
    val id: FactionId,
    val name: String,
    val realm: String,
    val lore: String,
    val bonusText: List<String>,
    /** Grundfarbe der Mauern, Dachfarbe, Akzent */
    val wallColor: Int,
    val roofColor: Int,
    val accentColor: Int,
    val unitNames: Map<UnitKind, String>,
    val buildingNames: Map<BuildingKind, String>,
    private val unitDescriptions: Map<UnitKind, String>,
    // Modifikatoren
    val buildingHpMul: Float = 1f,
    val buildSpeedMul: Float = 1f,
    val gatherMul: Map<Res, Float> = emptyMap(),
    val militaryCostMul: Float = 1f,
    val trainSpeedMul: Float = 1f,
    val footHpMul: Float = 1f,
    val rangedRangeBonus: Float = 0f,
    val sightBonus: Float = 0f,
    val mountedSpeedMul: Float = 1f,
    val mountedHpMul: Float = 1f,
    private val special: (Faction) -> UnitStats
) {
    private val unitCache = HashMap<UnitKind, UnitStats>()
    private val buildingCache = HashMap<BuildingKind, BuildingStats>()

    fun gatherMultiplier(r: Res): Float = gatherMul[r] ?: 1f

    fun unitName(k: UnitKind): String = unitNames[k] ?: k.name
    fun buildingName(k: BuildingKind): String = buildingNames[k] ?: k.name

    fun unit(k: UnitKind): UnitStats = unitCache.getOrPut(k) { buildUnit(k) }
    fun building(k: BuildingKind): BuildingStats = buildingCache.getOrPut(k) { buildBuilding(k) }

    private fun mc(c: Cost): Cost = Cost(
        (c.food * militaryCostMul).roundToInt(), (c.wood * militaryCostMul).roundToInt(),
        c.gold, c.stone
    )

    private fun buildUnit(k: UnitKind): UnitStats {
        val n = unitName(k)
        val d = unitDescriptions[k] ?: ""
        val ts = 1f / trainSpeedMul
        return when (k) {
            UnitKind.WORKER -> UnitStats(k, n, UnitClass.CIVIL, 25, 3, 0, 0.9f, 1.7f, 5f + sightBonus, 1.5f,
                Cost(food = 50), 18f * ts, description = d.ifEmpty { "Sammelt Rohstoffe und errichtet Gebäude." })
            UnitKind.INFANTRY -> UnitStats(k, n, UnitClass.FOOT, (60 * footHpMul).roundToInt(), 8, 1, 0.9f, 1.45f,
                5f + sightBonus, 1.5f, mc(Cost(food = 60, gold = 20)), 18f * ts, bonusVsBuilding = 3, bonusVsRanged = 2,
                description = d.ifEmpty { "Solider Nahkämpfer. Gut gegen Schützen und Gebäude." })
            UnitKind.SPEAR -> UnitStats(k, n, UnitClass.FOOT, (50 * footHpMul).roundToInt(), 4, 0, 1.0f, 1.5f,
                5f + sightBonus, 1.6f, mc(Cost(food = 35, wood = 25)), 15f * ts, bonusVsMounted = 14,
                description = d.ifEmpty { "Günstig. Sehr stark gegen Reiter." })
            UnitKind.ARCHER -> UnitStats(k, n, UnitClass.RANGED, 35, 5, 0, 5f + rangedRangeBonus, 1.5f,
                7f + sightBonus + rangedRangeBonus, 2.0f, mc(Cost(wood = 25, gold = 45)), 20f * ts, bonusVsFoot = 3,
                age = 2, description = d.ifEmpty { "Fernkämpfer. Stark gegen Fußvolk, schwach gegen Reiter." })
            UnitKind.CAVALRY -> UnitStats(k, n, UnitClass.MOUNTED, (100 * mountedHpMul).roundToInt(), 9, 2, 0.9f,
                2.4f * mountedSpeedMul, 6f + sightBonus, 1.8f, mc(Cost(food = 80, gold = 70)), 24f * ts,
                bonusVsRanged = 5, age = 2, description = d.ifEmpty { "Schnell und zäh. Jagt Schützen, fürchtet Speere." })
            UnitKind.SIEGE -> UnitStats(k, n, UnitClass.SIEGE, 180, 4, 6, 1.0f, 0.9f, 4f + sightBonus, 4f,
                mc(Cost(wood = 160, gold = 75)), 34f * ts, bonusVsBuilding = 80, canAttackUnits = false, age = 3,
                description = d.ifEmpty { "Zertrümmert Gebäude. Kann keine Einheiten angreifen." })
            UnitKind.SPECIAL -> special(this)
        }
    }

    private fun buildBuilding(k: BuildingKind): BuildingStats {
        val n = buildingName(k)
        fun hp(v: Int) = (v * buildingHpMul).roundToInt()
        return when (k) {
            BuildingKind.TOWNHALL -> BuildingStats(k, n, hp(2400), 4, Cost(wood = 275, stone = 100), 110f, 5, 9f, 1,
                listOf(UnitKind.WORKER, UnitKind.SPECIAL), true, 5, 6f, 2f, false, 3,
                "Herz eures Reiches. Bildet Arbeiter aus, nimmt alle Rohstoffe an und erforscht neue Zeitalter.")
            BuildingKind.HOUSE -> BuildingStats(k, n, hp(550), 2, Cost(wood = 30), 18f, 5, 3f, 1, emptyList(), false,
                0, 0f, 0f, false, 1, "+5 Bevölkerung.")
            BuildingKind.STOREHOUSE -> BuildingStats(k, n, hp(600), 2, Cost(wood = 100), 25f, 0, 4f, 1, emptyList(),
                true, 0, 0f, 0f, false, 1, "Abgabestelle für alle Rohstoffe. Nahe an Wald oder Minen bauen.")
            BuildingKind.FARM -> BuildingStats(k, n, hp(300), 3, Cost(wood = 60), 15f, 0, 2f, 1, emptyList(), false,
                0, 0f, 0f, true, 0, "Unerschöpfliche Nahrungsquelle für einen Arbeiter.")
            BuildingKind.BARRACKS -> BuildingStats(k, n, hp(1200), 3, Cost(wood = 175), 40f, 0, 5f, 1,
                listOf(UnitKind.INFANTRY, UnitKind.SPEAR), false, 0, 0f, 0f, false, 2, "Bildet Fußvolk aus.")
            BuildingKind.RANGE -> BuildingStats(k, n, hp(1000), 3, Cost(wood = 175), 40f, 0, 5f, 2,
                listOf(UnitKind.ARCHER), false, 0, 0f, 0f, false, 2, "Bildet Schützen aus.")
            BuildingKind.STABLE -> BuildingStats(k, n, hp(1000), 3, Cost(wood = 175), 40f, 0, 5f, 2,
                listOf(UnitKind.CAVALRY), false, 0, 0f, 0f, false, 2, "Bildet Reiter aus.")
            BuildingKind.TOWER -> BuildingStats(k, n, hp(900), 2, Cost(wood = 50, stone = 125), 45f, 0, 9f, 2,
                emptyList(), false, 7, 7f, 2f, false, 4, "Verteidigungsturm. Beschießt Feinde in Reichweite.")
            BuildingKind.WORKSHOP -> BuildingStats(k, n, hp(1000), 3, Cost(wood = 200, gold = 50), 45f, 0, 5f, 3,
                listOf(UnitKind.SIEGE), false, 0, 0f, 0f, false, 2, "Baut Belagerungsgerät.")
        }
    }

    companion object {
        private val commonBuildings = mapOf(
            BuildingKind.HOUSE to "Haus", BuildingKind.STOREHOUSE to "Lager", BuildingKind.FARM to "Feld"
        )

        val MENSCHEN = Faction(
            FactionId.MENSCHEN, "Menschen", "Königreich Varnholm",
            "Die Erben der alten Steinkönige halten die Ebenen von Varnholm. Ihre Burgen sind die stärksten Eldmars.",
            listOf("Gebäude +20 % Lebenspunkte", "Arbeiter bauen 25 % schneller", "Elite: Königsgarde"),
            0xFFB8B2A6.toInt(), 0xFF3F5E8C.toInt(), 0xFFD8B54A.toInt(),
            mapOf(
                UnitKind.WORKER to "Bauer", UnitKind.INFANTRY to "Schwertkämpfer", UnitKind.SPEAR to "Pikenier",
                UnitKind.ARCHER to "Armbrustschütze", UnitKind.CAVALRY to "Ritter", UnitKind.SPECIAL to "Königsgarde",
                UnitKind.SIEGE to "Rammbock"
            ),
            commonBuildings + mapOf(
                BuildingKind.TOWNHALL to "Burg", BuildingKind.BARRACKS to "Kaserne", BuildingKind.RANGE to "Schießplatz",
                BuildingKind.STABLE to "Rossstall", BuildingKind.TOWER to "Wachturm", BuildingKind.WORKSHOP to "Werkstatt"
            ),
            emptyMap(),
            buildingHpMul = 1.2f, buildSpeedMul = 1.25f,
            special = { f ->
                UnitStats(UnitKind.SPECIAL, f.unitName(UnitKind.SPECIAL), UnitClass.FOOT, 130, 12, 3, 0.9f, 1.4f, 6f,
                    1.5f, Cost(food = 90, gold = 70), 26f, bonusVsMounted = 4, bonusVsBuilding = 4, age = 3,
                    description = "Schwer gepanzerte Leibwache des Königs. Stark gegen alles am Boden.")
            }
        )

        val ELBEN = Faction(
            FactionId.ELBEN, "Elben", "Waldreich Sylvanthar",
            "Seit Jahrtausenden hüten die Elben von Sylvanthar die Silberhaine. Ihre Pfeile treffen, bevor man sie sieht.",
            listOf("Schützen +1 Reichweite", "Holz sammeln +20 %", "Alle Einheiten +1 Sicht", "Elite: Sternenschütze"),
            0xFFE6E2CF.toInt(), 0xFF3E7A4A.toInt(), 0xFFB7D9E8.toInt(),
            mapOf(
                UnitKind.WORKER to "Waldhüter", UnitKind.INFANTRY to "Klingentänzer", UnitKind.SPEAR to "Hainwächter",
                UnitKind.ARCHER to "Bogenschütze", UnitKind.CAVALRY to "Hirschreiter", UnitKind.SPECIAL to "Sternenschütze",
                UnitKind.SIEGE to "Baumbrecher"
            ),
            commonBuildings + mapOf(
                BuildingKind.TOWNHALL to "Hallenbaum", BuildingKind.BARRACKS to "Klingenhain",
                BuildingKind.RANGE to "Bogenlichtung", BuildingKind.STABLE to "Hirschgehege",
                BuildingKind.TOWER to "Wipfelwacht", BuildingKind.WORKSHOP to "Wurzelwerkstatt",
                BuildingKind.HOUSE to "Laubhaus"
            ),
            emptyMap(),
            gatherMul = mapOf(Res.WOOD to 1.2f), rangedRangeBonus = 1f, sightBonus = 1f,
            special = { f ->
                UnitStats(UnitKind.SPECIAL, f.unitName(UnitKind.SPECIAL), UnitClass.RANGED, 55, 9, 1, 7f, 1.6f, 9f,
                    1.8f, Cost(wood = 50, gold = 80), 24f, bonusVsFoot = 3, bonusVsMounted = 2, age = 3,
                    description = "Meisterschütze mit gewaltiger Reichweite.")
            }
        )

        val ORKS = Faction(
            FactionId.ORKS, "Orks", "Grakhul-Horde",
            "Aus den Aschebergen stürmt die Grakhul-Horde. Was ihr an Ordnung fehlt, macht sie durch Masse wett.",
            listOf("Armee 20 % billiger (Nahrung/Holz)", "Ausbildung 20 % schneller", "Elite: Berserker"),
            0xFF7A5A3C.toInt(), 0xFF8C2F24.toInt(), 0xFFC9C07A.toInt(),
            mapOf(
                UnitKind.WORKER to "Knecht", UnitKind.INFANTRY to "Schläger", UnitKind.SPEAR to "Spießer",
                UnitKind.ARCHER to "Pfeilschleuderer", UnitKind.CAVALRY to "Wolfsreiter", UnitKind.SPECIAL to "Berserker",
                UnitKind.SIEGE to "Sturmbock"
            ),
            commonBuildings + mapOf(
                BuildingKind.TOWNHALL to "Kriegsfeste", BuildingKind.BARRACKS to "Raufgrube",
                BuildingKind.RANGE to "Schleuderplatz", BuildingKind.STABLE to "Wolfszwinger",
                BuildingKind.TOWER to "Spähturm", BuildingKind.WORKSHOP to "Schmiedegrube",
                BuildingKind.HOUSE to "Hütte"
            ),
            mapOf(UnitKind.CAVALRY to "Blitzschnell, aber weniger zäh als andere Reiter."),
            militaryCostMul = 0.8f, trainSpeedMul = 1.2f, mountedSpeedMul = 1.15f, mountedHpMul = 0.85f,
            special = { f ->
                UnitStats(UnitKind.SPECIAL, f.unitName(UnitKind.SPECIAL), UnitClass.FOOT, 95, 15, 0, 0.9f, 1.75f, 6f,
                    1.3f, Cost(food = 70, gold = 35), 20f, bonusVsRanged = 4, bonusVsBuilding = 3, age = 3,
                    description = "Rasender Krieger. Enormer Schaden, kaum Rüstung.")
            }
        )

        val EISENBRUT = Faction(
            FactionId.EISENBRUT, "Eisenbrut", "Feste Vorgrath",
            "In den Schmiedeschloten von Vorgrath wurde ein Kriegervolk aus Eisen und Asche geformt – zäh, schwer und unerbittlich.",
            listOf("Fußvolk +20 % Lebenspunkte", "Gold und Stein sammeln +20 %", "Elite: Schwarzschild"),
            0xFF4A4A50.toInt(), 0xFF1F1F24.toInt(), 0xFFD0602A.toInt(),
            mapOf(
                UnitKind.WORKER to "Schürfer", UnitKind.INFANTRY to "Eisenkrieger", UnitKind.SPEAR to "Hakenträger",
                UnitKind.ARCHER to "Schwarzbogen", UnitKind.CAVALRY to "Keilerreiter", UnitKind.SPECIAL to "Schwarzschild",
                UnitKind.SIEGE to "Kriegswidder"
            ),
            commonBuildings + mapOf(
                BuildingKind.TOWNHALL to "Schmiedefeste", BuildingKind.BARRACKS to "Kriegerhalle",
                BuildingKind.RANGE to "Schützengrube", BuildingKind.STABLE to "Keilerpferch",
                BuildingKind.TOWER to "Eisenturm", BuildingKind.WORKSHOP to "Belagerungsschmiede",
                BuildingKind.HOUSE to "Barackenhaus"
            ),
            emptyMap(),
            footHpMul = 1.2f, gatherMul = mapOf(Res.GOLD to 1.2f, Res.STONE to 1.2f),
            special = { f ->
                UnitStats(UnitKind.SPECIAL, f.unitName(UnitKind.SPECIAL), UnitClass.FOOT, 170, 10, 5, 0.9f, 1.15f, 5f,
                    1.7f, Cost(food = 85, gold = 85), 28f, bonusVsMounted = 6, bonusVsBuilding = 6, age = 3,
                    description = "Wandelnde Festung aus Eisen. Langsam, aber kaum aufzuhalten.")
            }
        )

        val all = listOf(MENSCHEN, ELBEN, ORKS, EISENBRUT)
        fun byId(id: FactionId): Faction = all.first { it.id == id }
    }
}
