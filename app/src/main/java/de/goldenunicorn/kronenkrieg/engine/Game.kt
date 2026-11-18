package de.goldenunicorn.kronenkrieg.engine

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

class GameConfig(
    val playerFaction: FactionId,
    val aiFactions: List<FactionId>,
    /** 0 = Leicht, 1 = Normal, 2 = Schwer */
    val difficulty: Int,
    val seed: Long = System.currentTimeMillis(),
    /** Alle Spieler werden von der KI gesteuert (Testmodus). */
    val allAi: Boolean = false
)

class PlayerStats {
    var unitsTrained = 0
    var unitsLost = 0
    var kills = 0
    var buildingsBuilt = 0
    var buildingsLost = 0
    var buildingsRazed = 0
    val gathered = FloatArray(4)
}

class Player(val index: Int, val faction: Faction, val color: Int, val isHuman: Boolean, val name: String) {
    val res = FloatArray(4)
    var age = 1
    var defeated = false
    var pop = 0
    var popCap = 0
    val stats = PlayerStats()
    var ai: AIController? = null
    var startX = 0
    var startY = 0

    fun canAfford(c: Cost): Boolean = Res.values().all { res[it.ordinal] >= c[it] }
    fun pay(c: Cost) { for (r in Res.values()) res[r.ordinal] -= c[r].toFloat() }
    fun refund(c: Cost) { for (r in Res.values()) res[r.ordinal] += c[r].toFloat() }
    fun missing(c: Cost): Res? = Res.values().firstOrNull { res[it.ordinal] < c[it] }
    fun ageResearching(game: Game): Boolean =
        game.buildings.any { it.owner == index && it.queue.any { q -> q.isAgeUp } }
}

class Fog(w: Int, h: Int) {
    val visible = BooleanArray(w * h)
    val explored = BooleanArray(w * h)
}

class GameMessage(val text: String, val time: Float, val x: Float = -1f, val y: Float = -1f)

class Game(val config: GameConfig) {
    val rnd = Random(config.seed)
    var nextId = 1
    val units = ArrayList<GUnit>()
    val buildings = ArrayList<Building>()
    val nodes = ArrayList<ResourceNode>()
    private val byId = HashMap<Int, Entity>()
    val projectiles = ArrayList<Projectile>()
    val effects = ArrayList<Effect>()
    val players = ArrayList<Player>()
    lateinit var map: GameMap
    lateinit var pf: Pathfinder
    lateinit var fogs: Array<Fog>
    var time = 0f
    var over = false
    var winner = -1
    val messages = ArrayList<GameMessage>()
    private var fogTimer = 0f
    private var checkTimer = 0f
    private var lastAttackMsg = -100f
    private var bucketHead = IntArray(0)
    private var bucketNext = IntArray(0)
    private var bucketUnits = arrayOfNulls<GUnit>(0)

    /** Spieler, dessen Sicht gerendert wird. */
    val humanIndex = 0

    init {
        val colors = intArrayOf(0xFF2F6FD6.toInt(), 0xFFD0342C.toInt(), 0xFF3BA344.toInt(), 0xFFE0B020.toInt())
        val names = listOf("Ihr", "Fürst Harrok", "Herrin Ysmira", "Kriegsherr Druun")
        val all = listOf(config.playerFaction) + config.aiFactions
        for ((i, f) in all.withIndex()) {
            val human = i == 0 && !config.allAi
            players.add(Player(i, Faction.byId(f), colors[i], human, if (i == 0 && human) "Ihr" else names[i]))
        }
        val gen = MapGen.generate(this, players.size, rnd)
        map = gen.map
        pf = Pathfinder(map)
        fogs = Array(players.size) { Fog(map.w, map.h) }
        for ((i, p) in players.withIndex()) {
            val s = gen.starts[i]
            p.startX = s.first
            p.startY = s.second
            for (r in Res.values()) p.res[r.ordinal] = Rules.startRes[r].toFloat()
            val th = placeBuilding(i, BuildingKind.TOWNHALL, s.first - 2, s.second - 2, true)!!
            th.rallyX = -1f
            for (k in 0 until 3) {
                val pos = freeSpotNear(th, s.first.toFloat(), s.second.toFloat() + 3f)
                spawnUnit(i, UnitKind.WORKER, pos.first, pos.second)
            }
            if (!p.isHuman) p.ai = AIController(this, p, config.difficulty)
        }
        recomputePop()
        updateFog()
    }

    // ------------------------------------------------------------------ Verwaltung

    fun entity(id: Int): Entity? = byId[id]
    fun unitById(id: Int): GUnit? = byId[id] as? GUnit
    fun buildingById(id: Int): Building? = byId[id] as? Building

    fun addNode(m: GameMap, res: Res, x: Int, y: Int, amount: Float): ResourceNode {
        val n = ResourceNode(nextId++, res, x, y, amount)
        n.hp = amount
        n.maxHp = amount
        nodes.add(n)
        byId[n.id] = n
        m.nodeAt[m.idx(x, y)] = n.id
        return n
    }

    fun spawnUnit(owner: Int, kind: UnitKind, x: Float, y: Float): GUnit {
        val st = players[owner].faction.unit(kind)
        val u = GUnit(nextId++, owner, kind, st)
        u.x = x
        u.y = y
        u.hp = st.hp.toFloat()
        u.maxHp = st.hp.toFloat()
        u.homeX = x
        u.homeY = y
        u.facing = rnd.nextFloat() * 6.28f
        units.add(u)
        byId[u.id] = u
        return u
    }

    fun canPlace(owner: Int, kind: BuildingKind, tx: Int, ty: Int, gap: Boolean = false): Boolean {
        val st = players[owner].faction.building(kind)
        val s = st.size
        val g = if (gap) 1 else 0
        for (y in ty - g until ty + s + g) for (x in tx - g until tx + s + g) {
            val inner = x >= tx && y >= ty && x < tx + s && y < ty + s
            if (inner) {
                if (!map.buildable(x, y)) return false
                if (players[owner].isHuman && !fogs[owner].explored[map.idx(x, y)]) return false
            } else {
                if (!map.inside(x, y)) return false
                val i = map.idx(x, y)
                if (map.buildingAt[i] != 0) return false
            }
        }
        // Keine fremden Einheiten auf dem Bauplatz
        for (u in units) {
            if (u.owner != owner && u.x >= tx - 0.3f && u.y >= ty - 0.3f && u.x <= tx + s + 0.3f && u.y <= ty + s + 0.3f) return false
        }
        return true
    }

    fun placeBuilding(owner: Int, kind: BuildingKind, tx: Int, ty: Int, complete: Boolean): Building? {
        val st = players[owner].faction.building(kind)
        val b = Building(nextId++, owner, kind, st, tx, ty)
        b.maxHp = st.hp.toFloat()
        if (complete) {
            b.complete = true
            b.progress = 1f
            b.hp = b.maxHp
        } else {
            b.hp = max(1f, b.maxHp * 0.05f)
        }
        buildings.add(b)
        byId[b.id] = b
        for (y in ty until ty + st.size) for (x in tx until tx + st.size) {
            val i = map.idx(x, y)
            map.buildingAt[i] = b.id
            map.buildingBlocks[i] = !st.walkable
        }
        map.version++
        if (!st.walkable) pushUnitsOut(b)
        return b
    }

    private fun pushUnitsOut(b: Building) {
        for (u in units) {
            if (b.contains(u.x, u.y)) {
                val p = freeSpotNear(b, u.x, u.y)
                u.x = p.first
                u.y = p.second
                u.clearPath()
            }
        }
    }

    /** Freie Kachel am Rand eines Gebäudes, möglichst nahe am Wunschpunkt. */
    fun freeSpotNear(b: Building, wx: Float, wy: Float): Pair<Float, Float> {
        var best: Pair<Float, Float>? = null
        var bestD = Float.MAX_VALUE
        for (ring in 0..4) {
            val x0 = b.tx - 1 - ring
            val y0 = b.ty - 1 - ring
            val x1 = b.tx + b.size + ring
            val y1 = b.ty + b.size + ring
            for (y in y0..y1) for (x in x0..x1) {
                if (x != x0 && x != x1 && y != y0 && y != y1) continue
                if (!map.walkable(x, y)) continue
                val cx = x + 0.5f
                val cy = y + 0.5f
                var crowd = 0f
                for (u in units) if (abs(u.x - cx) < 0.5f && abs(u.y - cy) < 0.5f) crowd += 3f
                val d = dist(cx, cy, wx, wy) + crowd
                if (d < bestD) { bestD = d; best = Pair(cx + (rnd.nextFloat() - 0.5f) * 0.3f, cy + (rnd.nextFloat() - 0.5f) * 0.3f) }
            }
            if (best != null && bestD < 6f) return best
        }
        return best ?: Pair(b.x, b.y + b.size)
    }

    private fun removeEntity(e: Entity) {
        e.alive = false
        byId.remove(e.id)
        when (e) {
            is GUnit -> {
                units.remove(e)
                if (e.order == Order.GATHER) buildingById(e.targetId)?.let { if (it.farmer == e.id) it.farmer = 0 }
            }
            is Building -> {
                buildings.remove(e)
                for (y in e.ty until e.ty + e.size) for (x in e.tx until e.tx + e.size) {
                    val i = map.idx(x, y)
                    if (map.buildingAt[i] == e.id) {
                        map.buildingAt[i] = 0
                        map.buildingBlocks[i] = false
                    }
                }
                map.version++
                // Rückerstattung offener Aufträge nicht nötig – Gebäude verloren
            }
            is ResourceNode -> {
                nodes.remove(e)
                map.nodeAt[map.idx(e.tx, e.ty)] = 0
                map.version++
            }
        }
    }

    fun notify(player: Int, text: String, x: Float = -1f, y: Float = -1f) {
        if (player != humanIndex || !players[player].isHuman) return
        messages.add(GameMessage(text, time, x, y))
        if (messages.size > 6) messages.removeAt(0)
    }

    fun recomputePop() {
        for (p in players) { p.pop = 0; p.popCap = 0 }
        for (u in units) players[u.owner].pop++
        for (b in buildings) if (b.complete) players[b.owner].popCap += b.stats.pop
        for (p in players) p.popCap = min(p.popCap, Rules.MAX_POP)
    }

    fun gatherRate(p: Player, r: Res, farm: Boolean): Float {
        var rate = Rules.baseGatherRate(r, farm) * p.faction.gatherMultiplier(r)
        if (p.age >= 2) rate *= 1.1f
        if (p.age >= 3) rate *= 1.05f
        p.ai?.let { rate *= it.gatherBonus }
        return rate
    }

    // ------------------------------------------------------------------ Befehle

    fun issueMove(list: List<GUnit>, wx: Float, wy: Float, attackMove: Boolean) {
        val spots = formationSpots(wx, wy, list.size)
        // Einheiten nach Entfernung sortieren, damit sich Wege weniger kreuzen
        val sorted = list.sortedBy { dist(it.x, it.y, wx, wy) }
        for ((i, u) in sorted.withIndex()) {
            val s = spots[min(i, spots.size - 1)]
            u.order = Order.MOVE
            u.goalX = s.first
            u.goalY = s.second
            u.attackMove = attackMove && u.kind.isMilitary
            u.targetId = 0
            u.working = false
            releaseFarm(u)
            computePathToPoint(u, s.first, s.second)
        }
    }

    private fun formationSpots(wx: Float, wy: Float, n: Int): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        val cx = wx.toInt()
        val cy = wy.toInt()
        if (n == 1) {
            if (map.walkable(cx, cy)) return listOf(Pair(wx, wy))
        }
        var r = 0
        while (out.size < n && r < 12) {
            for (dy in -r..r) for (dx in -r..r) {
                if (max(abs(dx), abs(dy)) != r) continue
                val x = cx + dx
                val y = cy + dy
                if (map.walkable(x, y)) out.add(Pair(x + 0.5f, y + 0.5f))
            }
            r++
        }
        if (out.isEmpty()) out.add(Pair(wx, wy))
        return out
    }

    fun issueAttack(list: List<GUnit>, target: Entity) {
        for (u in list) {
            if (target is GUnit && !u.stats.canAttackUnits) continue
            releaseFarm(u)
            u.order = Order.ATTACK
            u.targetId = target.id
            u.attackMove = false
            u.working = false
            u.clearPath()
            u.repathTimer = 0f
        }
    }

    fun issueGatherNode(list: List<GUnit>, node: ResourceNode) {
        for (u in list) {
            if (u.kind != UnitKind.WORKER) continue
            releaseFarm(u)
            // Auf benachbarte Vorkommen derselben Art verteilen
            val n = leastBusyNode(node.res, node.x, node.y, 3.5f, u.id) ?: node
            startGather(u, n.id, -1, -1, n.res)
        }
    }

    fun gatherersOn(nodeId: Int, selfId: Int): Int =
        units.count { it.id != selfId && it.targetId == nodeId && (it.order == Order.GATHER || it.order == Order.RETURN) && it.treeX < 0 }

    /** Vorkommen in der Nähe mit möglichst wenigen Sammlern. */
    fun leastBusyNode(res: Res, x: Float, y: Float, range: Float, selfId: Int, exclude: Int = 0): ResourceNode? {
        var best: ResourceNode? = null
        var bs = Float.MAX_VALUE
        for (n in nodes) {
            if (n.res != res || n.id == exclude) continue
            val d = dist(n.x, n.y, x, y)
            if (d > range) continue
            val score = d + gatherersOn(n.id, selfId) * 3.5f
            if (score < bs) { bs = score; best = n }
        }
        return best
    }

    fun issueGatherTree(list: List<GUnit>, tx: Int, ty: Int) {
        for (u in list) {
            if (u.kind != UnitKind.WORKER) continue
            releaseFarm(u)
            // Arbeiter auf benachbarte Bäume verteilen
            val t = nearestTree(tx + 0.5f, ty + 0.5f, 3f, u.id) ?: Pair(tx, ty)
            startGather(u, 0, t.first, t.second, Res.WOOD)
        }
    }

    fun issueFarm(u: GUnit, farm: Building): Boolean {
        if (farm.farmer != 0 && farm.farmer != u.id && unitById(farm.farmer)?.let {
                it.order == Order.GATHER && it.targetId == farm.id || it.order == Order.RETURN && it.targetId == farm.id
            } == true
        ) return false
        releaseFarm(u)
        farm.farmer = u.id
        startGather(u, farm.id, -1, -1, Res.FOOD)
        return true
    }

    private fun startGather(u: GUnit, targetId: Int, tx: Int, ty: Int, res: Res) {
        u.order = Order.GATHER
        u.targetId = targetId
        u.treeX = tx
        u.treeY = ty
        u.gatherRes = res
        u.attackMove = false
        u.working = false
        u.clearPath()
        u.repathTimer = 0f
    }

    fun issueBuild(list: List<GUnit>, b: Building) {
        for (u in list) {
            if (u.kind != UnitKind.WORKER) continue
            releaseFarm(u)
            u.order = Order.BUILD
            u.targetId = b.id
            u.attackMove = false
            u.working = false
            u.clearPath()
            u.repathTimer = 0f
        }
    }

    fun issueReturn(u: GUnit) {
        if (u.carry <= 0f) return
        u.order = Order.RETURN
        u.clearPath()
        u.repathTimer = 0f
    }

    private fun releaseFarm(u: GUnit) {
        if (u.order == Order.GATHER || u.order == Order.RETURN) {
            val b = buildingById(u.targetId)
            if (b != null && b.kind == BuildingKind.FARM && b.farmer == u.id) b.farmer = 0
        }
    }

    fun stop(list: List<GUnit>) {
        for (u in list) {
            releaseFarm(u)
            u.order = Order.IDLE
            u.clearPath()
            u.working = false
            u.attackMove = false
        }
    }

    /** Kontextbefehl wie bei einem Tipp auf die Karte. */
    fun smartCommand(owner: Int, list: List<GUnit>, wx: Float, wy: Float) {
        if (list.isEmpty()) return
        val tx = wx.toInt()
        val ty = wy.toInt()
        val fog = fogs[owner]
        val visible = map.inside(tx, ty) && fog.visible[map.idx(tx, ty)]
        // Feindliche Einheit in der Nähe des Tipps?
        if (visible) {
            var bestU: GUnit? = null
            var bd = 0.9f
            for (u in units) {
                if (u.owner == owner) continue
                val d = dist(u.x, u.y, wx, wy)
                if (d < bd) { bd = d; bestU = u }
            }
            if (bestU != null) {
                issueAttack(list.filter { it.kind != UnitKind.WORKER || list.all { w -> w.kind == UnitKind.WORKER } }, bestU)
                return
            }
        }
        val explored = map.inside(tx, ty) && fog.explored[map.idx(tx, ty)]
        if (map.inside(tx, ty) && explored) {
            val bid = map.buildingAt[map.idx(tx, ty)]
            val b = buildingById(bid)
            if (b != null) {
                if (b.owner != owner) {
                    issueAttack(list, b)
                    return
                }
                val workers = list.filter { it.kind == UnitKind.WORKER }
                val others = list.filter { it.kind != UnitKind.WORKER }
                if (workers.isNotEmpty()) {
                    if (!b.complete || b.hp < b.maxHp) {
                        issueBuild(workers, b)
                    } else if (b.kind == BuildingKind.FARM) {
                        val first = workers.first()
                        if (!issueFarm(first, b)) issueBuild(listOf(first), b)
                        val rest = workers.drop(1)
                        if (rest.isNotEmpty()) issueMove(rest, wx, wy + b.size, false)
                    } else if (b.stats.dropoff) {
                        for (w in workers) if (w.carry > 0) issueReturn(w) else issueMove(listOf(w), wx, wy, false)
                    } else {
                        issueMove(workers, wx, wy, false)
                    }
                }
                if (others.isNotEmpty()) issueMove(others, wx, wy, false)
                return
            }
            val nid = map.nodeAt[map.idx(tx, ty)]
            val node = entity(nid) as? ResourceNode
            val workers = list.filter { it.kind == UnitKind.WORKER }
            val others = list.filter { it.kind != UnitKind.WORKER }
            if (node != null && workers.isNotEmpty()) {
                issueGatherNode(workers, node)
                if (others.isNotEmpty()) issueMove(others, wx, wy, true)
                return
            }
            if (map.isTree(tx, ty) && workers.isNotEmpty()) {
                issueGatherTree(workers, tx, ty)
                if (others.isNotEmpty()) issueMove(others, wx, wy, true)
                return
            }
        }
        issueMove(list, wx, wy, true)
    }

    fun train(b: Building, kind: UnitKind): String? {
        val p = players[b.owner]
        if (!b.complete) return "Gebäude ist noch im Bau."
        if (kind !in b.stats.trains) return "Hier nicht möglich."
        val st = p.faction.unit(kind)
        if (p.age < st.age) return "Benötigt ${Rules.ages[st.age - 1].name}."
        if (b.queue.size >= Rules.QUEUE_MAX) return "Warteschlange voll."
        if (!p.canAfford(st.cost)) return "Nicht genug ${p.missing(st.cost)!!.label}."
        p.pay(st.cost)
        b.queue.add(QueueItem(kind, false, 0f, st.trainTime, st.cost))
        return null
    }

    fun ageUp(b: Building): String? {
        val p = players[b.owner]
        if (b.kind != BuildingKind.TOWNHALL || !b.complete) return "Nur in der Burg."
        if (p.age >= 3) return "Höchstes Zeitalter erreicht."
        if (p.ageResearching(this)) return "Wird bereits erforscht."
        val info = Rules.ages[p.age]
        if (!p.canAfford(info.cost)) return "Nicht genug ${p.missing(info.cost)!!.label}."
        p.pay(info.cost)
        b.queue.add(QueueItem(null, true, 0f, info.time, info.cost))
        return null
    }

    fun cancelQueue(b: Building, index: Int) {
        if (index < 0 || index >= b.queue.size) return
        val q = b.queue.removeAt(index)
        players[b.owner].refund(q.cost)
    }

    fun startConstruction(owner: Int, kind: BuildingKind, tx: Int, ty: Int, builders: List<GUnit>): String? {
        val p = players[owner]
        val st = p.faction.building(kind)
        if (p.age < st.age) return "Benötigt ${Rules.ages[st.age - 1].name}."
        if (!canPlace(owner, kind, tx, ty)) return "Hier kann nicht gebaut werden."
        if (!p.canAfford(st.cost)) return "Nicht genug ${p.missing(st.cost)!!.label}."
        p.pay(st.cost)
        val b = placeBuilding(owner, kind, tx, ty, false) ?: return "Fehler"
        issueBuild(builders, b)
        return null
    }

    fun deleteOwn(e: Entity) {
        when (e) {
            is GUnit -> killUnit(e, 0)
            is Building -> destroyBuilding(e, 0)
            else -> {}
        }
    }

    // ------------------------------------------------------------------ Simulation

    fun tick() {
        if (over) return
        val dt = Rules.TICK
        time += dt
        for (b in ArrayList(buildings)) updateBuilding(b, dt)
        for (u in ArrayList(units)) if (u.alive) updateUnit(u, dt)
        separate()
        updateProjectiles(dt)
        val it = effects.iterator()
        while (it.hasNext()) {
            val e = it.next()
            e.t += dt
            if (e.t >= e.duration) it.remove()
        }
        fogTimer -= dt
        if (fogTimer <= 0f) {
            fogTimer = 0.25f
            updateFog()
        }
        recomputePop()
        for (p in players) if (!p.defeated) p.ai?.update(dt)
        checkTimer -= dt
        if (checkTimer <= 0f) {
            checkTimer = 1f
            checkVictory()
        }
    }

    private fun checkVictory() {
        for (p in players) {
            if (p.defeated) continue
            val has = buildings.any { it.owner == p.index && it.kind != BuildingKind.FARM }
            if (!has) {
                p.defeated = true
                for (u in units.filter { it.owner == p.index }) killUnit(u, 0)
                for (b in buildings.filter { it.owner == p.index }) destroyBuilding(b, 0)
                if (p.index != humanIndex) notify(humanIndex, "${p.name} (${p.faction.name}) wurde besiegt!")
            }
        }
        val alive = players.filter { !it.defeated }
        if (players[humanIndex].isHuman && players[humanIndex].defeated) {
            over = true
            winner = alive.firstOrNull()?.index ?: -1
        } else if (alive.size <= 1) {
            over = true
            winner = alive.firstOrNull()?.index ?: -1
        }
    }

    fun updateFog() {
        for ((pi, f) in fogs.withIndex()) {
            java.util.Arrays.fill(f.visible, false)
            for (u in units) if (u.owner == pi) reveal(f, u.x, u.y, u.stats.sight)
            for (b in buildings) if (b.owner == pi) reveal(f, b.x, b.y, b.stats.sight + b.size / 2f)
        }
    }

    private fun reveal(f: Fog, cx: Float, cy: Float, r: Float) {
        val x0 = max(0, (cx - r).toInt())
        val x1 = min(map.w - 1, (cx + r).toInt())
        val y0 = max(0, (cy - r).toInt())
        val y1 = min(map.h - 1, (cy + r).toInt())
        val r2 = r * r
        for (y in y0..y1) {
            val dy = y + 0.5f - cy
            for (x in x0..x1) {
                val dx = x + 0.5f - cx
                if (dx * dx + dy * dy <= r2) {
                    val i = y * map.w + x
                    f.visible[i] = true
                    f.explored[i] = true
                }
            }
        }
    }

    fun isVisibleTo(player: Int, e: Entity): Boolean {
        val tx = e.x.toInt()
        val ty = e.y.toInt()
        if (!map.inside(tx, ty)) return false
        if (e is Building) {
            for (y in e.ty until e.ty + e.size) for (x in e.tx until e.tx + e.size)
                if (fogs[player].visible[map.idx(x, y)]) return true
            return false
        }
        return fogs[player].visible[map.idx(tx, ty)]
    }

    private fun updateBuilding(b: Building, dt: Float) {
        if (!b.complete) return
        val p = players[b.owner]
        // Produktion
        if (b.queue.isNotEmpty()) {
            val q = b.queue[0]
            val blocked = q.kind != null && p.pop >= p.popCap
            if (!blocked) {
                q.time += dt
                if (q.time >= q.totalTime) {
                    b.queue.removeAt(0)
                    if (q.isAgeUp) {
                        p.age++
                        // Werte für neues Zeitalter bleiben gleich, Boni greifen dynamisch
                        notify(b.owner, "${Rules.ages[p.age - 1].name} erreicht!")
                    } else {
                        val kind = q.kind!!
                        val want = if (b.rallyX >= 0f) Pair(b.rallyX, b.rallyY) else Pair(b.x, b.y + b.size)
                        val pos = freeSpotNear(b, want.first, want.second)
                        val u = spawnUnit(b.owner, kind, pos.first, pos.second)
                        p.stats.unitsTrained++
                        p.pop++
                        if (b.rallyX >= 0f) smartCommand(b.owner, listOf(u), b.rallyX, b.rallyY)
                    }
                }
            } else if (q.time < 0.05f && p.isHuman && (time - lastPopMsg) > 12f) {
                lastPopMsg = time
                notify(b.owner, "Bevölkerungsgrenze erreicht – baut Häuser!")
            }
        }
        // Verteidigung
        if (b.stats.attack > 0) {
            b.cooldown -= dt
            if (b.cooldown <= 0f) {
                val t = nearestEnemyUnit(b.owner, b.x, b.y, b.stats.range + b.size / 2f, true)
                if (t != null) {
                    b.cooldown = b.stats.cooldown
                    var dmg = b.stats.attack - t.stats.armor - ageArmor(t)
                    if (p.age >= 3) dmg += 1
                    projectiles.add(Projectile(b.x, b.y - 0.6f, b.x, b.y - 0.6f, t.id, max(1, dmg), b.owner, b.id, 11f, false))
                }
            }
        }
    }

    private var lastPopMsg = -100f

    private fun ageArmor(u: GUnit): Int = if (u.kind.isMilitary && players[u.owner].age >= 3) 1 else 0

    fun nearestEnemyUnit(owner: Int, x: Float, y: Float, range: Float, needVisible: Boolean): GUnit? {
        var best: GUnit? = null
        var bd = range
        for (u in units) {
            if (u.owner == owner) continue
            val d = dist(u.x, u.y, x, y)
            if (d < bd) {
                if (needVisible && !fogs[owner].visible[map.idx(u.x.toInt(), u.y.toInt())]) continue
                bd = d
                best = u
            }
        }
        return best
    }

    private fun nearestEnemyBuilding(owner: Int, x: Float, y: Float, range: Float): Building? {
        var best: Building? = null
        var bd = range
        for (b in buildings) {
            if (b.owner == owner) continue
            val d = b.edgeDistance(x, y)
            if (d < bd) { bd = d; best = b }
        }
        return best
    }

    private fun updateUnit(u: GUnit, dt: Float) {
        u.cooldown -= dt
        u.repathTimer -= dt
        u.working = false
        when (u.order) {
            Order.IDLE -> {
                u.idleTime += dt
                if (u.kind.isMilitary && u.cooldown <= 0f && ((time * 20).toInt() + u.id) % 8 == 0) {
                    autoAcquire(u, u.stats.sight)
                }
            }
            Order.MOVE -> {
                u.idleTime = 0f
                if (u.attackMove && ((time * 20).toInt() + u.id) % 8 == 0) {
                    if (autoAcquire(u, u.stats.sight)) return
                }
                if (followPath(u, dt, u.goalX, u.goalY)) {
                    if (dist(u.x, u.y, u.goalX, u.goalY) < 0.6f || u.path == null) {
                        u.order = Order.IDLE
                        u.attackMove = false
                        u.homeX = u.x
                        u.homeY = u.y
                    }
                }
            }
            Order.ATTACK -> { u.idleTime = 0f; updateAttack(u, dt) }
            Order.GATHER -> { u.idleTime = 0f; updateGather(u, dt) }
            Order.RETURN -> { u.idleTime = 0f; updateReturn(u, dt) }
            Order.BUILD -> { u.idleTime = 0f; updateBuild(u, dt) }
        }
    }

    /** Sucht automatisch Ziele; liefert true, wenn ein Angriff gestartet wurde. */
    private fun autoAcquire(u: GUnit, range: Float): Boolean {
        var target: Entity? = null
        if (u.stats.canAttackUnits) {
            // Bevorzugt Militär
            var best: GUnit? = null
            var bd = Float.MAX_VALUE
            for (o in units) {
                if (o.owner == u.owner) continue
                val d = dist(o.x, o.y, u.x, u.y)
                if (d > range) continue
                val score = d + if (o.kind.isMilitary) 0f else 3f
                if (score < bd) { bd = score; best = o }
            }
            target = best
        }
        if (target == null) {
            val b = nearestEnemyBuilding(u.owner, u.x, u.y, if (u.stats.canAttackUnits) range * 0.6f else range)
            if (b != null && (u.attackMove || !u.stats.canAttackUnits || u.order == Order.IDLE)) target = b
        }
        if (target == null) return false
        val resume = u.order == Order.MOVE
        u.order = Order.ATTACK
        u.targetId = target.id
        if (!resume) u.attackMove = false
        u.clearPath()
        u.repathTimer = 0f
        return true
    }

    private fun updateAttack(u: GUnit, dt: Float) {
        val t = entity(u.targetId)
        if (t == null || !t.alive || t.owner == u.owner || t is ResourceNode) {
            u.targetId = 0
            if (u.attackMove) {
                if (autoAcquire(u, u.stats.sight)) return
                u.order = Order.MOVE
                computePathToPoint(u, u.goalX, u.goalY)
            } else {
                u.order = Order.IDLE
                if (u.kind.isMilitary) autoAcquire(u, u.stats.sight)
            }
            return
        }
        if (t is GUnit && !u.stats.canAttackUnits) {
            u.order = Order.IDLE
            return
        }
        val reach = u.stats.range + u.radius
        val d = t.edgeDistance(u.x, u.y)
        if (d <= reach) {
            u.clearPath()
            u.facing = atan2(t.y - u.y, t.x - u.x)
            u.working = true
            if (u.cooldown <= 0f) {
                u.cooldown = u.stats.cooldown
                performAttack(u, t)
            }
            return
        }
        // Verfolgen: Weg neu berechnen, wenn sich das Ziel bewegt hat
        if (u.path == null || u.repathTimer <= 0f || dist(t.x, t.y, u.lastTargetX, u.lastTargetY) > 1.5f) {
            u.lastTargetX = t.x
            u.lastTargetY = t.y
            u.repathTimer = 1.0f
            computePathToEntity(u, t, reach)
        }
        val done = followPath(u, dt, t.x, t.y)
        if (done) moveDirect(u, t.x, t.y, dt)
        // Aufgabe, wenn Ziel zu weit weg und unsichtbar
        if (t is GUnit && !fogs[u.owner].visible[map.idx(t.x.toInt(), t.y.toInt())] && !u.attackMove && d > u.stats.sight + 3) {
            u.order = Order.IDLE
        }
    }

    private fun performAttack(u: GUnit, t: Entity) {
        val p = players[u.owner]
        var dmg = u.stats.attack
        if (u.kind.isMilitary && p.age >= 3) dmg += 1
        when (t) {
            is GUnit -> {
                when (t.stats.unitClass) {
                    UnitClass.MOUNTED -> dmg += u.stats.bonusVsMounted
                    UnitClass.FOOT -> dmg += u.stats.bonusVsFoot
                    UnitClass.RANGED -> dmg += u.stats.bonusVsRanged
                    UnitClass.SIEGE -> dmg += u.stats.bonusVsBuilding / 8
                    UnitClass.CIVIL -> {}
                }
                dmg -= t.stats.armor + ageArmor(t)
            }
            is Building -> {
                dmg += u.stats.bonusVsBuilding
                dmg -= t.stats.armor
                if (u.stats.isRanged) dmg = max(1, dmg / 3)
            }
        }
        dmg = max(1, dmg)
        if (u.stats.isRanged) {
            projectiles.add(Projectile(u.x, u.y, u.x, u.y, t.id, dmg, u.owner, u.id, 12f, u.kind == UnitKind.SPECIAL))
        } else {
            applyDamage(t, dmg, u.owner, u.id)
        }
    }

    fun applyDamage(t: Entity, dmg: Int, srcOwner: Int, srcId: Int) {
        if (!t.alive) return
        t.hp -= dmg
        t.lastHitTime = time
        t.lastHitBy = srcId
        if (t.owner == humanIndex && players[humanIndex].isHuman && time - lastAttackMsg > 20f && srcOwner != t.owner && srcOwner >= 0) {
            lastAttackMsg = time
            notify(humanIndex, "Ihr werdet angegriffen!", t.x, t.y)
        }
        if (t is GUnit) {
            effects.add(Effect(EffectKind.HIT, t.x, t.y, t.owner, 0.25f, 0.4f))
            // Gegenwehr
            val src = unitById(srcId)
            if (src != null && t.kind.isMilitary && t.stats.canAttackUnits &&
                (t.order == Order.IDLE || (t.order == Order.MOVE && t.attackMove))
            ) {
                val resume = t.order == Order.MOVE
                t.order = Order.ATTACK
                t.targetId = src.id
                if (!resume) t.attackMove = false
                t.clearPath()
            }
            if (t.hp <= 0f) killUnit(t, srcOwner)
        } else if (t is Building) {
            if (t.hp <= 0f) destroyBuilding(t, srcOwner)
        }
    }

    fun killUnit(u: GUnit, by: Int) {
        if (!u.alive) return
        effects.add(Effect(EffectKind.CORPSE, u.x, u.y, u.owner, 6f, u.radius))
        players[u.owner].stats.unitsLost++
        if (by >= 0 && by != u.owner) players[by].stats.kills++
        removeEntity(u)
    }

    fun destroyBuilding(b: Building, by: Int) {
        if (!b.alive) return
        effects.add(Effect(EffectKind.RUBBLE, b.x, b.y, b.owner, 12f, b.size.toFloat()))
        players[b.owner].stats.buildingsLost++
        if (by >= 0 && by != b.owner) players[by].stats.buildingsRazed++
        removeEntity(b)
    }

    private fun updateProjectiles(dt: Float) {
        val it = projectiles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            val t = entity(p.targetId)
            if (t == null || !t.alive) { it.remove(); continue }
            p.tx = t.x
            p.ty = t.y
            val d = dist(p.x, p.y, t.x, t.y)
            val step = p.speed * dt
            if (d <= step + 0.2f) {
                applyDamage(t, p.damage, p.sourceOwner, p.sourceId)
                it.remove()
            } else {
                p.x += (t.x - p.x) / d * step
                p.y += (t.y - p.y) / d * step
            }
        }
    }

    // ------------------------------------------------------------------ Sammeln & Bauen

    private fun updateGather(u: GUnit, dt: Float) {
        val res = u.gatherRes ?: run { u.order = Order.IDLE; return }
        if (u.carry >= Rules.CARRY || (u.carry > 0f && u.carryRes != res)) {
            if (u.carryRes != res && u.carry > 0f && u.carry < Rules.CARRY) {
                // Anderer Rohstoff in der Hand: zuerst abliefern
            }
            u.order = Order.RETURN
            u.clearPath()
            return
        }
        // Ziel prüfen
        var targetEntity: Entity? = null
        var valid = false
        if (u.treeX >= 0) {
            valid = map.isTree(u.treeX, u.treeY)
        } else {
            targetEntity = entity(u.targetId)
            valid = when (targetEntity) {
                is ResourceNode -> targetEntity.alive
                is Building -> targetEntity.alive && targetEntity.kind == BuildingKind.FARM && targetEntity.complete &&
                        targetEntity.owner == u.owner && (targetEntity.farmer == 0 || targetEntity.farmer == u.id)
                else -> false
            }
            if (valid && targetEntity is Building) targetEntity.farmer = u.id
        }
        if (!valid) {
            if (!findNewResource(u, res)) {
                if (u.carry > 0f) { u.order = Order.RETURN; u.clearPath() } else u.order = Order.IDLE
            }
            return
        }
        // Reichweite
        val inReach: Boolean
        val tgtX: Float
        val tgtY: Float
        if (u.treeX >= 0) {
            tgtX = u.treeX + 0.5f
            tgtY = u.treeY + 0.5f
            val dx = max(max(u.treeX - u.x, 0f), u.x - (u.treeX + 1))
            val dy = max(max(u.treeY - u.y, 0f), u.y - (u.treeY + 1))
            inReach = sqrt(dx * dx + dy * dy) <= 1.0f
        } else {
            val te = targetEntity!!
            tgtX = te.x
            tgtY = te.y
            inReach = if (te is Building) te.contains(u.x, u.y) || te.edgeDistance(u.x, u.y) < 0.1f
            else te.edgeDistance(u.x, u.y) <= 1.0f
        }
        if (inReach) {
            u.clearPath()
            u.working = true
            u.facing = atan2(tgtY - u.y, tgtX - u.x)
            val p = players[u.owner]
            val farm = targetEntity is Building
            var amount = gatherRate(p, res, farm) * dt
            if (u.treeX >= 0) {
                val i = map.idx(u.treeX, u.treeY)
                amount = min(amount, map.wood[i])
                map.wood[i] -= amount
                if (map.wood[i] <= 0.01f) {
                    map.removeTree(u.treeX, u.treeY)
                }
            } else if (targetEntity is ResourceNode) {
                amount = min(amount, targetEntity.amount)
                targetEntity.amount -= amount
                targetEntity.hp = targetEntity.amount
                if (targetEntity.amount <= 0.01f) removeEntity(targetEntity)
            } else if (farm) {
                // Feld: Arbeiter wandert über das Feld
                val b = targetEntity as Building
                if (((time * 2).toInt() + u.id) % 7 == 0) {
                    val ox = b.tx + 0.5f + ((time * 0.37f + u.id) % (b.size - 1).toFloat())
                    u.x += (ox - u.x) * 0.02f
                }
            }
            u.carry += amount
            u.carryRes = res
            return
        }
        // Hinlaufen
        if (u.path == null || u.repathTimer <= 0f) {
            u.repathTimer = 3f
            if (u.treeX >= 0) {
                val txx = u.treeX
                val tyy = u.treeY
                u.path = pf.find(u.x.toInt(), u.y.toInt(), tgtX, tgtY, 4000) { x, y ->
                    map.walkable(x, y) && abs(x - txx) <= 1 && abs(y - tyy) <= 1
                }
                u.pathIdx = 0
            } else {
                val te = targetEntity!!
                if (te is Building) {
                    u.path = pf.find(u.x.toInt(), u.y.toInt(), tgtX, tgtY, 4000) { x, y -> te.contains(x + 0.5f, y + 0.5f) }
                    u.pathIdx = 0
                } else computePathToEntity(u, te, 0.6f)
            }
        }
        if (followPath(u, dt, tgtX, tgtY)) {
            moveDirect(u, tgtX, tgtY, dt)
            u.stuckTimer += dt
            if (u.stuckTimer > 4f) {
                u.stuckTimer = 0f
                // Zielbaum unerreichbar: anderen suchen
                if (u.treeX >= 0) {
                    val alt = nearestTree(u.x, u.y, 10f, u.id, exclude = map.idx(u.treeX, u.treeY))
                    if (alt != null) { u.treeX = alt.first; u.treeY = alt.second }
                } else if (targetEntity is ResourceNode) {
                    val alt = leastBusyNode(res, u.x, u.y, 8f, u.id, exclude = targetEntity.id)
                    if (alt != null) u.targetId = alt.id
                }
                u.clearPath()
                u.repathTimer = 0f
            }
        } else u.stuckTimer = 0f
    }

    private fun findNewResource(u: GUnit, res: Res): Boolean {
        if (res == Res.WOOD) {
            val t = nearestTree(u.x, u.y, 12f, u.id) ?: return false
            u.treeX = t.first
            u.treeY = t.second
            u.targetId = 0
            u.clearPath()
            u.repathTimer = 0f
            return true
        }
        if (res == Res.FOOD) {
            // freies eigenes Feld bevorzugen, wenn die alte Quelle ein Feld war
            val oldFarm = buildingById(u.targetId)
            if (oldFarm == null || oldFarm.kind != BuildingKind.FARM) {
                val n = leastBusyNode(res, u.x, u.y, 12f, u.id)
                if (n != null) {
                    u.targetId = n.id; u.treeX = -1; u.clearPath(); u.repathTimer = 0f
                    return true
                }
            }
            val farm = buildings.filter {
                it.owner == u.owner && it.kind == BuildingKind.FARM && it.complete && (it.farmer == 0 || it.farmer == u.id)
            }.minByOrNull { dist(it.x, it.y, u.x, u.y) }
            if (farm != null && dist(farm.x, farm.y, u.x, u.y) < 25f) {
                farm.farmer = u.id
                u.targetId = farm.id; u.treeX = -1; u.clearPath(); u.repathTimer = 0f
                return true
            }
            return false
        }
        val n = leastBusyNode(res, u.x, u.y, 12f, u.id) ?: return false
        u.targetId = n.id
        u.treeX = -1
        u.clearPath()
        u.repathTimer = 0f
        return true
    }

    fun nearestNode(x: Float, y: Float, res: Res, range: Float): ResourceNode? {
        var best: ResourceNode? = null
        var bd = range
        for (n in nodes) {
            if (n.res != res) continue
            val d = dist(n.x, n.y, x, y)
            if (d < bd) { bd = d; best = n }
        }
        return best
    }

    /** Nächster Baum mit einem begehbaren Nachbarfeld. Vermeidet Bäume, an denen schon viele arbeiten. */
    fun nearestTree(x: Float, y: Float, range: Float, selfId: Int, exclude: Int = -1): Pair<Int, Int>? {
        val r = range.toInt()
        val cx = x.toInt()
        val cy = y.toInt()
        var best: Pair<Int, Int>? = null
        var bd = Float.MAX_VALUE
        for (yy in max(0, cy - r)..min(map.h - 1, cy + r)) for (xx in max(0, cx - r)..min(map.w - 1, cx + r)) {
            val i = map.idx(xx, yy)
            if (i == exclude || map.terrain[i] != Terrain.FOREST) continue
            var open = false
            for (dy in -1..1) for (dx in -1..1) if ((dx != 0 || dy != 0) && map.walkable(xx + dx, yy + dy)) open = true
            if (!open) continue
            var d = dist(xx + 0.5f, yy + 0.5f, x, y)
            if (d > range) continue
            for (o in units) if (o.id != selfId && o.treeX == xx && o.treeY == yy && o.order == Order.GATHER) d += 2.5f
            if (d < bd) { bd = d; best = Pair(xx, yy) }
        }
        return best
    }

    fun nearestDropoff(owner: Int, x: Float, y: Float): Building? {
        var best: Building? = null
        var bd = Float.MAX_VALUE
        for (b in buildings) {
            if (b.owner != owner || !b.complete || !b.stats.dropoff) continue
            val d = b.edgeDistance(x, y)
            if (d < bd) { bd = d; best = b }
        }
        return best
    }

    private fun updateReturn(u: GUnit, dt: Float) {
        if (u.carry <= 0f) {
            resumeAfterReturn(u)
            return
        }
        val d = nearestDropoff(u.owner, u.x, u.y)
        if (d == null) { u.order = Order.IDLE; return }
        if (d.edgeDistance(u.x, u.y) <= 0.8f) {
            val p = players[u.owner]
            val r = u.carryRes ?: Res.FOOD
            p.res[r.ordinal] += u.carry
            p.stats.gathered[r.ordinal] += u.carry
            u.carry = 0f
            u.carryRes = null
            resumeAfterReturn(u)
            return
        }
        if (u.path == null || u.repathTimer <= 0f || u.lastTargetX != d.x || u.lastTargetY != d.y) {
            u.repathTimer = 4f
            u.lastTargetX = d.x
            u.lastTargetY = d.y
            computePathToEntity(u, d, 0.8f)
        }
        if (followPath(u, dt, d.x, d.y)) moveDirect(u, d.x, d.y, dt)
    }

    private fun resumeAfterReturn(u: GUnit) {
        if (u.gatherRes != null) {
            u.order = Order.GATHER
            u.clearPath()
            u.repathTimer = 0f
        } else u.order = Order.IDLE
    }

    private fun updateBuild(u: GUnit, dt: Float) {
        val b = buildingById(u.targetId)
        if (b == null || b.owner != u.owner) {
            afterBuild(u, null)
            return
        }
        if (b.complete && b.hp >= b.maxHp) {
            afterBuild(u, b)
            return
        }
        if (b.edgeDistance(u.x, u.y) <= 0.85f) {
            u.clearPath()
            u.working = true
            u.facing = atan2(b.y - u.y, b.x - u.x)
            val p = players[u.owner]
            if (!b.complete) {
                val rate = dt * p.faction.buildSpeedMul / b.stats.buildTime
                b.progress = min(1f, b.progress + rate)
                b.hp = min(b.maxHp, b.hp + b.maxHp * rate * 0.95f)
                if (b.progress >= 1f) {
                    b.complete = true
                    b.hp = max(b.hp, b.maxHp * 0.9f)
                    p.stats.buildingsBuilt++
                    notify(u.owner, "${b.stats.name} fertiggestellt.", b.x, b.y)
                    recomputePop()
                }
            } else {
                b.hp = min(b.maxHp, b.hp + b.maxHp * dt / b.stats.buildTime * 0.5f)
            }
            return
        }
        if (u.path == null || u.repathTimer <= 0f) {
            u.repathTimer = 3f
            computePathToEntity(u, b, 0.85f)
        }
        if (followPath(u, dt, b.x, b.y)) {
            moveDirect(u, b.x, b.y, dt)
            u.stuckTimer += dt
            if (u.stuckTimer > 5f) { u.stuckTimer = 0f; u.clearPath(); u.repathTimer = 0f }
        } else u.stuckTimer = 0f
    }

    private fun afterBuild(u: GUnit, b: Building?) {
        // Weitere Baustelle in der Nähe?
        val other = buildings.filter { it.owner == u.owner && !it.complete && dist(it.x, it.y, u.x, u.y) < 10f }
            .minByOrNull { dist(it.x, it.y, u.x, u.y) }
        if (other != null) {
            u.targetId = other.id
            u.clearPath()
            u.repathTimer = 0f
            return
        }
        if (b != null) {
            if (b.kind == BuildingKind.FARM && b.farmer == 0) {
                issueFarm(u, b)
                return
            }
            if (b.kind == BuildingKind.STOREHOUSE || b.kind == BuildingKind.TOWNHALL) {
                // nächster Rohstoff in der Nähe
                var bestRes: Res? = null
                var bestD = 9f
                val tree = nearestTree(b.x, b.y, 8f, u.id)
                if (tree != null) {
                    bestD = dist(tree.first + 0.5f, tree.second + 0.5f, b.x, b.y)
                    bestRes = Res.WOOD
                }
                var bestNode: ResourceNode? = null
                for (n in nodes) {
                    val d = dist(n.x, n.y, b.x, b.y)
                    if (d < bestD) { bestD = d; bestNode = n; bestRes = n.res }
                }
                if (bestNode != null) { startGather(u, bestNode.id, -1, -1, bestNode.res); return }
                if (bestRes == Res.WOOD && tree != null) { startGather(u, 0, tree.first, tree.second, Res.WOOD); return }
            }
        }
        u.order = Order.IDLE
        u.clearPath()
    }

    // ------------------------------------------------------------------ Bewegung

    fun computePathToPoint(u: GUnit, gx: Float, gy: Float) {
        val gtx = gx.toInt()
        val gty = gy.toInt()
        u.path = pf.find(u.x.toInt(), u.y.toInt(), gx, gy, 6000) { x, y -> x == gtx && y == gty }
        u.pathIdx = 0
        u.stuckTimer = 0f
    }

    private fun computePathToEntity(u: GUnit, t: Entity, reach: Float) {
        val r = reach + 0.45f
        u.path = pf.find(u.x.toInt(), u.y.toInt(), t.x, t.y, 5000) { x, y ->
            map.walkable(x, y) && t.edgeDistance(x + 0.5f, y + 0.5f) <= r
        }
        u.pathIdx = 0
    }

    /** Folgt dem Weg; liefert true, wenn der Weg zu Ende ist. */
    private fun followPath(u: GUnit, dt: Float, fx: Float, fy: Float): Boolean {
        val p = u.path ?: return true
        if (u.pathIdx >= p.size) {
            if (dist(u.x, u.y, fx, fy) > 0.15f && u.order == Order.MOVE) {
                // letztes Stück direkt
                return moveDirect(u, fx, fy, dt)
            }
            return true
        }
        val ni = p[u.pathIdx]
        val nx = ni % map.w
        val ny = ni / map.w
        if (!map.walkableIdx(ni) && !(u.order == Order.GATHER && u.treeX == nx && u.treeY == ny)) {
            // Blockiert (neues Gebäude o. ä.)
            u.clearPath()
            u.repathTimer = 0f
            if (u.order == Order.MOVE) computePathToPoint(u, u.goalX, u.goalY)
            return false
        }
        val last = u.pathIdx == p.size - 1
        val wx = if (last && u.order == Order.MOVE) fx else nx + 0.5f
        val wy = if (last && u.order == Order.MOVE) fy else ny + 0.5f
        val reached = moveDirect(u, wx, wy, dt)
        if (reached || dist(u.x, u.y, wx, wy) < 0.22f) u.pathIdx++
        return u.pathIdx >= p.size && (u.order != Order.MOVE || dist(u.x, u.y, fx, fy) < 0.25f)
    }

    /** Bewegt direkt auf den Punkt zu; true, wenn angekommen. */
    private fun moveDirect(u: GUnit, tx: Float, ty: Float, dt: Float): Boolean {
        val dx = tx - u.x
        val dy = ty - u.y
        val d = sqrt(dx * dx + dy * dy)
        val step = u.stats.speed * dt
        u.animTime += dt
        if (d <= step) {
            tryMove(u, dx, dy)
            return true
        }
        u.facing = atan2(dy, dx)
        tryMove(u, dx / d * step, dy / d * step)
        return false
    }

    private fun tryMove(u: GUnit, dx: Float, dy: Float) {
        val nx = u.x + dx
        val ny = u.y + dy
        if (passable(nx, ny)) { u.x = nx; u.y = ny; return }
        if (passable(nx, u.y)) { u.x = nx; return }
        if (passable(u.x, ny)) { u.y = ny }
    }

    private fun passable(x: Float, y: Float): Boolean {
        val tx = x.toInt()
        val ty = y.toInt()
        if (x < 0f || y < 0f) return false
        return map.walkable(tx, ty)
    }

    /** Einfache Trennung, damit Einheiten nicht übereinander stehen. */
    private fun separate() {
        val w = map.w
        val n = map.w * map.h
        if (bucketHead.size != n) bucketHead = IntArray(n)
        java.util.Arrays.fill(bucketHead, -1)
        if (bucketNext.size < units.size) {
            bucketNext = IntArray(units.size * 2)
            bucketUnits = arrayOfNulls(units.size * 2)
        }
        for ((k, u) in units.withIndex()) {
            val tx = u.x.toInt().coerceIn(0, map.w - 1)
            val ty = u.y.toInt().coerceIn(0, map.h - 1)
            val i = ty * w + tx
            bucketUnits[k] = u
            bucketNext[k] = bucketHead[i]
            bucketHead[i] = k
        }
        for ((k, a) in units.withIndex()) {
            val tx = a.x.toInt()
            val ty = a.y.toInt()
            for (yy in ty - 1..ty + 1) for (xx in tx - 1..tx + 1) {
                if (!map.inside(xx, yy)) continue
                var j = bucketHead[yy * w + xx]
                while (j >= 0) {
                    if (j > k) {
                        val b = bucketUnits[j]!!
                        val dx = b.x - a.x
                        val dy = b.y - a.y
                        val min = (a.radius + b.radius) * 0.85f
                        val d2 = dx * dx + dy * dy
                        if (d2 < min * min) {
                            val d = sqrt(d2).coerceAtLeast(0.01f)
                            val push = (min - d) * 0.5f
                            val ux = if (d2 < 0.0001f) (rnd.nextFloat() - 0.5f) else dx / d
                            val uy = if (d2 < 0.0001f) (rnd.nextFloat() - 0.5f) else dy / d
                            val wa = if (a.working) 0.15f else 1f
                            val wb = if (b.working) 0.15f else 1f
                            val tot = wa + wb
                            tryMove(a, -ux * push * 2 * wa / tot, -uy * push * 2 * wa / tot)
                            tryMove(b, ux * push * 2 * wb / tot, uy * push * 2 * wb / tot)
                        }
                    }
                    j = bucketNext[j]
                }
            }
        }
    }

    // ------------------------------------------------------------------ Hilfen für KI und Oberfläche

    fun findBuildSpot(owner: Int, kind: BuildingKind, nearX: Float, nearY: Float, maxR: Int = 18, avoidX: Float = -1f, avoidY: Float = -1f): Pair<Int, Int>? {
        val st = players[owner].faction.building(kind)
        val s = st.size
        val cx = (nearX - s / 2f).toInt()
        val cy = (nearY - s / 2f).toInt()
        for (r in 0..maxR) {
            val cand = ArrayList<Pair<Int, Int>>()
            for (dy in -r..r) for (dx in -r..r) {
                if (max(abs(dx), abs(dy)) != r) continue
                cand.add(Pair(cx + dx, cy + dy))
            }
            cand.shuffle(rnd)
            for (c in cand) {
                if (avoidX >= 0f && dist(c.first + s / 2f, c.second + s / 2f, avoidX, avoidY) < 3.5f + s / 2f) continue
                if (canPlace(owner, kind, c.first, c.second, gap = true) && !blocksResources(c.first, c.second, s)) return c
            }
        }
        return null
    }

    /** Vermeidet Gebäude direkt an Rohstoffen, damit Arbeiter Platz haben. */
    private fun blocksResources(tx: Int, ty: Int, s: Int): Boolean {
        for (y in ty - 1..ty + s) for (x in tx - 1..tx + s) {
            if (!map.inside(x, y)) continue
            if (map.nodeAt[map.idx(x, y)] != 0) return true
        }
        return false
    }

    fun townhallOf(owner: Int): Building? = buildings.firstOrNull { it.owner == owner && it.kind == BuildingKind.TOWNHALL }
}
