package de.goldenunicorn.kronenkrieg.engine

import kotlin.math.max
import kotlin.math.min

/**
 * Computergegner. Baut eine Wirtschaft auf, steigt in Zeitalter auf und greift in Wellen an.
 */
class AIController(private val game: Game, private val player: Player, private val difficulty: Int) {
    val gatherBonus = when (difficulty) { 0 -> 0.8f; 1 -> 1.0f; else -> 1.2f }
    private val thinkInterval = when (difficulty) { 0 -> 1.0f; 1 -> 0.6f; else -> 0.4f }
    private val workerTarget = when (difficulty) { 0 -> 20; 1 -> 28; else -> 36 }
    private var firstAttack = when (difficulty) { 0 -> 840f; 1 -> 560f; else -> 400f }
    private var attackThreshold = when (difficulty) { 0 -> 7; 1 -> 9; else -> 12 }
    private var timer = 0f
    private var lastStorehouse = -100f
    private var lastFarm = -100f
    private var lastAttackOrder = -100f
    private var lastHouse = -100f
    private var waves = 0
    private var attacking = false
    private var underAttack = false
    private val me = player.index

    fun update(dt: Float) {
        timer -= dt
        if (timer > 0f) return
        timer = thinkInterval
        think()
    }

    private fun think() {
        val units = game.units.filter { it.owner == me }
        val workers = units.filter { it.kind == UnitKind.WORKER }
        val army = units.filter { it.kind.isMilitary }
        val mine = game.buildings.filter { it.owner == me }
        val th = mine.firstOrNull { it.kind == BuildingKind.TOWNHALL }
        val baseX = th?.x ?: player.startX.toFloat()
        val baseY = th?.y ?: player.startY.toFloat()
        fun count(k: BuildingKind) = mine.count { it.kind == k }
        val wantsAge = wantsAgeUp(workers.size, mine)

        // Burg verloren? Neu errichten
        if (th == null && workers.isNotEmpty()) {
            if (player.canAfford(player.faction.building(BuildingKind.TOWNHALL).cost)) {
                build(BuildingKind.TOWNHALL, baseX, baseY, workers)
            }
        }

        // Unbetreute Baustellen
        for (b in mine) {
            if (b.complete) continue
            val has = workers.any { it.order == Order.BUILD && it.targetId == b.id }
            if (!has) {
                val w = pickBuilder(workers, b.x, b.y)
                if (w != null) game.issueBuild(listOf(w), b)
            }
        }

        // Arbeiter ausbilden
        if (th != null && th.complete) {
            val queued = th.queue.count { it.kind == UnitKind.WORKER }
            val ageQ = th.queue.any { it.isAgeUp }
            if (!ageQ && workers.size + queued < workerTarget && queued < 2 && player.pop < player.popCap + 1) {
                val reserveOk = !wantsAge || player.res[Res.FOOD.ordinal] >= Rules.ages[player.age].cost.food + 50
                if (reserveOk || workers.size < 12) game.train(th, UnitKind.WORKER)
            }
        }

        // Häuser
        val producers = mine.count { it.complete && it.stats.trains.isNotEmpty() }
        val housesBuilding = mine.count { it.kind == BuildingKind.HOUSE && !it.complete }
        val maxHousesBuilding = if (player.pop > 40) 2 else 1
        if (player.popCap < Rules.MAX_POP && player.popCap - player.pop <= 2 + producers * 2 &&
            maxHousesBuilding > housesBuilding && game.time - lastHouse > 4f
        ) {
            if (build(BuildingKind.HOUSE, baseX, baseY, workers)) lastHouse = game.time
        }

        assignIdle(workers, baseX, baseY)
        rebalance(workers, baseX, baseY)
        storehouses(workers)

        // Militärgebäude
        val ws = workers.size
        if (count(BuildingKind.BARRACKS) == 0 && ws >= (if (difficulty == 2) 8 else 11)) {
            build(BuildingKind.BARRACKS, baseX, baseY, workers)
        }
        if (player.age >= 2) {
            if (count(BuildingKind.RANGE) == 0 && ws >= 14) build(BuildingKind.RANGE, baseX, baseY, workers)
            else if (count(BuildingKind.STABLE) == 0 && ws >= 16) build(BuildingKind.STABLE, baseX, baseY, workers)
            else if (difficulty >= 1 && count(BuildingKind.TOWER) == 0 && ws >= 18 && player.res[Res.STONE.ordinal] >= 125) {
                val ex = enemyDirection()
                build(BuildingKind.TOWER, baseX + ex.first * 7f, baseY + ex.second * 7f, workers)
            }
            else if (difficulty == 2 && count(BuildingKind.BARRACKS) < 2 && ws >= 26) build(BuildingKind.BARRACKS, baseX, baseY, workers)
        }
        if (player.age >= 3) {
            if (count(BuildingKind.WORKSHOP) == 0 && ws >= 22) build(BuildingKind.WORKSHOP, baseX, baseY, workers)
            else if (count(BuildingKind.STABLE) < 2 && ws >= 30 && difficulty >= 1) build(BuildingKind.STABLE, baseX, baseY, workers)
        }

        // Zeitalter
        if (wantsAge && th != null && th.complete && !player.ageResearching(game) && th.queue.isEmpty()) {
            game.ageUp(th)
        }

        trainArmy(mine, army, wantsAge, workers.size)
        defend(army, mine)
        attack(army, baseX, baseY)
    }

    private fun wantsAgeUp(workers: Int, mine: List<Building>): Boolean {
        if (player.age >= 3) return false
        return if (player.age == 1) {
            workers >= (when (difficulty) { 0 -> 17; 1 -> 15; else -> 13 }) && mine.any { it.kind == BuildingKind.BARRACKS }
        } else {
            workers >= (when (difficulty) { 0 -> 24; 1 -> 22; else -> 20 }) &&
                    mine.any { (it.kind == BuildingKind.RANGE || it.kind == BuildingKind.STABLE) && it.complete }
        }
    }

    private fun enemyDirection(): Pair<Float, Float> {
        val e = game.players.filter { it.index != me && !it.defeated }.minByOrNull {
            dist(it.startX.toFloat(), it.startY.toFloat(), player.startX.toFloat(), player.startY.toFloat())
        } ?: return Pair(0f, 0f)
        val dx = (e.startX - player.startX).toFloat()
        val dy = (e.startY - player.startY).toFloat()
        val d = max(1f, dist(0f, 0f, dx, dy))
        return Pair(dx / d, dy / d)
    }

    private fun pickBuilder(workers: List<GUnit>, x: Float, y: Float): GUnit? {
        val idle = workers.filter { it.order == Order.IDLE }
        if (idle.isNotEmpty()) return idle.minByOrNull { dist(it.x, it.y, x, y) }
        return workers.filter {
            (it.order == Order.GATHER || it.order == Order.RETURN) && it.gatherRes != Res.FOOD
        }.minByOrNull { dist(it.x, it.y, x, y) }
            ?: workers.filter { it.order != Order.BUILD }.minByOrNull { dist(it.x, it.y, x, y) }
    }

    private fun build(kind: BuildingKind, nx: Float, ny: Float, workers: List<GUnit>, avoidX: Float = -1f, avoidY: Float = -1f): Boolean {
        val st = player.faction.building(kind)
        if (player.age < st.age) return false
        if (!player.canAfford(st.cost)) return false
        val maxR = if (kind == BuildingKind.STOREHOUSE) 6 else 20
        val spot = game.findBuildSpot(me, kind, nx, ny, maxR, avoidX, avoidY) ?: return false
        val w = pickBuilder(workers, spot.first + st.size / 2f, spot.second + st.size / 2f) ?: return false
        return game.startConstruction(me, kind, spot.first, spot.second, listOf(w)) == null
    }

    private fun desiredShares(): FloatArray = when (player.age) {
        1 -> floatArrayOf(0.55f, 0.33f, 0.12f, 0.0f)
        2 -> floatArrayOf(0.46f, 0.26f, 0.22f, 0.06f)
        else -> floatArrayOf(0.42f, 0.26f, 0.26f, 0.06f)
    }

    private var lastRebalance = 0f

    /** Verschiebt gelegentlich einen Arbeiter vom größten Überschuss zum größten Mangel. */
    private fun rebalance(workers: List<GUnit>, bx: Float, by: Float) {
        if (game.time - lastRebalance < 6f || workers.size < 6) return
        lastRebalance = game.time
        val counts = IntArray(4)
        for (w in workers) {
            val r = w.gatherRes
            if (r != null && (w.order == Order.GATHER || w.order == Order.RETURN)) counts[r.ordinal]++
        }
        val shares = desiredShares()
        val total = workers.size
        val deficit = Res.values().maxByOrNull { shares[it.ordinal] * total - counts[it.ordinal] }!!
        val surplus = Res.values().minByOrNull { shares[it.ordinal] * total - counts[it.ordinal] }!!
        if (shares[deficit.ordinal] * total - counts[deficit.ordinal] < 1.5f) return
        if (counts[surplus.ordinal] - shares[surplus.ordinal] * total < 1f) return
        val w = workers.filter { it.gatherRes == surplus && it.order == Order.GATHER && it.carry < 3f }
            .minByOrNull { dist(it.x, it.y, bx, by) } ?: return
        if (assign(w, deficit, bx, by)) return
    }

    private fun assignIdle(workers: List<GUnit>, bx: Float, by: Float) {
        val idle = workers.filter { it.order == Order.IDLE }
        if (idle.isEmpty()) return
        val counts = IntArray(4)
        for (w in workers) {
            val r = w.gatherRes
            if (r != null && (w.order == Order.GATHER || w.order == Order.RETURN)) counts[r.ordinal]++
        }
        val shares = desiredShares()
        val total = workers.size
        for (w in idle) {
            // Rohstoff mit dem größten Defizit
            val order = Res.values().sortedByDescending { shares[it.ordinal] * total - counts[it.ordinal] }
            var done = false
            for (r in order) {
                if (assign(w, r, bx, by)) { counts[r.ordinal]++; done = true; break }
            }
            if (!done) game.issueMove(listOf(w), bx + 2f, by + 3f, false)
        }
    }

    private fun assign(w: GUnit, r: Res, bx: Float, by: Float): Boolean {
        when (r) {
            Res.WOOD -> {
                val near = game.buildings.filter { it.owner == me && it.complete && it.stats.dropoff }
                var t: Pair<Int, Int>? = null
                for (d in near.sortedBy { dist(it.x, it.y, w.x, w.y) }) {
                    t = game.nearestTree(d.x, d.y, 14f, w.id)
                    if (t != null) break
                }
                if (t == null) t = game.nearestTree(bx, by, 30f, w.id)
                if (t == null) return false
                game.issueGatherTree(listOf(w), t.first, t.second)
                return true
            }
            Res.FOOD -> {
                val berries = game.leastBusyNode(Res.FOOD, bx, by, 16f, w.id)
                if (berries != null && game.gatherersOn(berries.id, w.id) < 2) {
                    game.issueGatherNode(listOf(w), berries)
                    return true
                }
                val farm = game.buildings.firstOrNull {
                    it.owner == me && it.kind == BuildingKind.FARM && it.complete && it.farmer == 0
                }
                if (farm != null) return game.issueFarm(w, farm)
                val pending = game.buildings.any { it.owner == me && it.kind == BuildingKind.FARM && !it.complete }
                if (!pending && game.time - lastFarm > 2f && player.canAfford(player.faction.building(BuildingKind.FARM).cost)) {
                    val drop = game.nearestDropoff(me, w.x, w.y)
                    val fx = drop?.x ?: bx
                    val fy = drop?.y ?: by
                    val spot = game.findBuildSpot(me, BuildingKind.FARM, fx, fy, 12)
                    if (spot != null && game.startConstruction(me, BuildingKind.FARM, spot.first, spot.second, listOf(w)) == null) {
                        lastFarm = game.time
                        return true
                    }
                }
                return false
            }
            Res.GOLD, Res.STONE -> {
                val n = game.leastBusyNode(r, bx, by, 40f, w.id) ?: return false
                game.issueGatherNode(listOf(w), n)
                return true
            }
        }
    }

    private fun storehouses(workers: List<GUnit>) {
        if (game.time - lastStorehouse < 25f) return
        for (r in listOf(Res.WOOD, Res.GOLD, Res.STONE)) {
            val gs = workers.filter { it.gatherRes == r && (it.order == Order.GATHER || it.order == Order.RETURN) }
            if (gs.size < 3) continue
            var sx = 0f
            var sy = 0f
            var far = 0
            for (g in gs) {
                val px = if (g.treeX >= 0) g.treeX + 0.5f else game.entity(g.targetId)?.x ?: g.x
                val py = if (g.treeY >= 0) g.treeY + 0.5f else game.entity(g.targetId)?.y ?: g.y
                sx += px
                sy += py
                val d = game.nearestDropoff(me, px, py)
                if (d == null || d.edgeDistance(px, py) > 7f) far++
            }
            if (far * 2 >= gs.size) {
                val cx = sx / gs.size
                val cy = sy / gs.size
                if (build(BuildingKind.STOREHOUSE, cx, cy, workers)) {
                    lastStorehouse = game.time
                    return
                }
            }
        }
    }

    private fun trainArmy(mine: List<Building>, army: List<GUnit>, wantsAge: Boolean, workerCount: Int) {
        // Wirtschaft zuerst: Armee nur, wenn genug Arbeiter vorhanden
        val minWorkers = when (player.age) { 1 -> 10; 2 -> 18; else -> 24 }.coerceAtMost(workerTarget)
        val behindEconomy = workerCount < minWorkers
        if (behindEconomy && army.size >= 4 && !underAttack) return
        val ageCost = if (wantsAge && army.size >= 5 && !underAttack) Rules.ages[player.age].cost else Cost()
        val workerReserve = if (workerCount < workerTarget) 50 else 0
        val reserve = Cost(ageCost.food + workerReserve, ageCost.wood, ageCost.gold, ageCost.stone)
        val enemyMounted = game.units.count { it.owner != me && it.stats.unitClass == UnitClass.MOUNTED }
        val enemyFoot = game.units.count { it.owner != me && it.stats.unitClass == UnitClass.FOOT }
        val armyCap = when (difficulty) { 0 -> 30; 1 -> 50; else -> 80 }
        if (army.size >= armyCap) return
        for (b in mine) {
            if (!b.complete || b.queue.size >= 2) continue
            val kind: UnitKind = when (b.kind) {
                BuildingKind.BARRACKS -> if (enemyMounted > army.count { it.kind == UnitKind.SPEAR } / 2 + 1 || game.rnd.nextFloat() < 0.3f) UnitKind.SPEAR else UnitKind.INFANTRY
                BuildingKind.RANGE -> UnitKind.ARCHER
                BuildingKind.STABLE -> UnitKind.CAVALRY
                BuildingKind.WORKSHOP -> if (army.count { it.kind == UnitKind.SIEGE } < 3) UnitKind.SIEGE else continue
                BuildingKind.TOWNHALL -> if (player.age >= 3 && b.queue.isEmpty() && game.units.count { it.owner == me && it.kind == UnitKind.WORKER } >= workerTarget - 4) UnitKind.SPECIAL else continue
                else -> continue
            }
            if (kind == UnitKind.INFANTRY && enemyFoot == 0 && game.rnd.nextFloat() < 0.3f) continue
            val st = player.faction.unit(kind)
            if (player.age < st.age) continue
            // Rücklage beachten
            var ok = true
            for (r in Res.values()) if (player.res[r.ordinal] - st.cost[r] < reserve[r]) ok = false
            if (ok) game.train(b, kind)
        }
    }

    private fun defend(army: List<GUnit>, mine: List<Building>) {
        // Feinde nahe eigener Gebäude
        var threatX = -1f
        var threatY = -1f
        var best = Float.MAX_VALUE
        for (b in mine) {
            val e = game.nearestEnemyUnit(me, b.x, b.y, 9f + b.size, false) ?: continue
            val d = dist(e.x, e.y, b.x, b.y)
            if (d < best) { best = d; threatX = e.x; threatY = e.y }
        }
        underAttack = threatX >= 0f
        if (threatX < 0f) return
        val responders = army.filter {
            it.order == Order.IDLE || (it.order == Order.MOVE && !attacking) ||
                    (dist(it.x, it.y, threatX, threatY) < 20f && it.order != Order.ATTACK)
        }
        if (responders.isNotEmpty()) game.issueMove(responders, threatX, threatY, true)
        // Arbeiter in Not: wenn keine Armee da, flüchten Sammler nicht – die Burg schießt.
    }

    private fun attack(army: List<GUnit>, bx: Float, by: Float) {
        if (game.time < firstAttack) return
        val idleArmy = army.filter { it.order == Order.IDLE && dist(it.x, it.y, bx, by) < 30f }
        if (idleArmy.size >= attackThreshold) {
            val target = pickTarget(bx, by) ?: return
            game.issueMove(idleArmy, target.x, target.y, true)
            waves++
            attackThreshold = min(attackThreshold + 3, 40)
            attacking = true
            lastAttackOrder = game.time
            return
        }
        // Gestrandete Angreifer weiterschicken
        if (game.time - lastAttackOrder > 15f) {
            val stray = army.filter { it.order == Order.IDLE && dist(it.x, it.y, bx, by) >= 30f }
            if (stray.isNotEmpty()) {
                val c = stray.first()
                val target = pickTarget(c.x, c.y)
                if (target != null) game.issueMove(stray, target.x, target.y, true)
                lastAttackOrder = game.time
            }
        }
    }

    private fun pickTarget(x: Float, y: Float): Building? {
        // Schwächere Gegner zuerst: nächstes Gebäude eines aktiven Feindes
        return game.buildings.filter { it.owner != me && !game.players[it.owner].defeated }
            .minByOrNull { dist(it.x, it.y, x, y) + if (it.kind == BuildingKind.TOWER) 8f else 0f }
    }
}
