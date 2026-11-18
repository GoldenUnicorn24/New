package de.goldenunicorn.kronenkrieg.engine

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Erzeugt die Karte „Tal von Eldmar“ mit Startplätzen für 2–4 Spieler. */
object MapGen {

    class Result(val map: GameMap, val starts: List<Pair<Int, Int>>)

    fun generate(game: Game, players: Int, rnd: Random): Result {
        val size = if (players <= 2) 80 else 96
        val map = GameMap(size, size)
        val inset = 13
        val corners = mutableListOf(
            Pair(inset, inset), Pair(size - 1 - inset, size - 1 - inset),
            Pair(size - 1 - inset, inset), Pair(inset, size - 1 - inset)
        )
        val starts: List<Pair<Int, Int>> = if (players == 2) {
            if (rnd.nextBoolean()) listOf(corners[0], corners[1]) else listOf(corners[2], corners[3])
        } else {
            corners.shuffled(rnd).take(players)
        }

        // 1) Rauschen für Wälder
        val noiseA = valueNoise(size, 9, rnd)
        val noiseB = valueNoise(size, 4, rnd)
        for (y in 0 until size) for (x in 0 until size) {
            val i = map.idx(x, y)
            val n = noiseA[i] * 0.7f + noiseB[i] * 0.3f
            map.shade[i] = rnd.nextFloat()
            if (n > 0.63f) {
                map.terrain[i] = Terrain.FOREST
                map.wood[i] = 100f
            }
            // Waldrand am Kartenrand
            val edge = min(min(x, y), min(size - 1 - x, size - 1 - y))
            if (edge < 2 && rnd.nextFloat() < 0.75f) {
                map.terrain[i] = Terrain.FOREST
                map.wood[i] = 100f
            }
        }

        // 2) Seen
        val lakes = 2 + rnd.nextInt(3)
        var tries = 0
        var made = 0
        while (made < lakes && tries < 80) {
            tries++
            val cx = 10 + rnd.nextInt(size - 20)
            val cy = 10 + rnd.nextInt(size - 20)
            if (starts.any { dist(it.first.toFloat(), it.second.toFloat(), cx.toFloat(), cy.toFloat()) < 20f }) continue
            val r = 2.5f + rnd.nextFloat() * 3.5f
            blob(map, cx, cy, r, rnd) { i ->
                map.terrain[i] = Terrain.WATER
                map.wood[i] = 0f
            }
            made++
        }

        // 3) Wege zwischen allen Startplätzen und zur Mitte freiräumen
        val mid = Pair(size / 2, size / 2)
        for (a in starts.indices) {
            carve(map, starts[a], mid, rnd)
            for (b in a + 1 until starts.size) carve(map, starts[a], starts[b], rnd)
        }
        for (s in starts) clearCircle(map, s.first, s.second, 9f)
        clearCircle(map, mid.first, mid.second, 4f)

        // 4) Rohstoffe pro Startplatz
        for (s in starts) {
            val base = rnd.nextFloat() * 6.283f
            // Eigener Wald
            forestPatch(map, s, base, 11.5f, 3.6f, rnd)
            forestPatch(map, s, base + 2.4f, 13f, 3.0f, rnd)
            // Beeren
            cluster(game, map, s, base + 1.2f, 6.5f, Res.FOOD, 6, 125f, rnd)
            // Gold
            cluster(game, map, s, base + 3.3f, 8.5f, Res.GOLD, 4, 450f, rnd)
            // Stein
            cluster(game, map, s, base + 4.6f, 9f, Res.STONE, 4, 300f, rnd)
            // Zweites Gold weiter weg
            cluster(game, map, s, base + 5.6f, 15f, Res.GOLD, 4, 450f, rnd)
            // Weitere Beeren etwas weiter
            cluster(game, map, s, base + 0.2f, 14f, Res.FOOD, 5, 125f, rnd)
        }
        // Mitte: wertvolle Vorkommen
        cluster(game, map, mid, 0f, 0f, Res.GOLD, 6, 600f, rnd)
        var extra = 0
        tries = 0
        while (extra < 4 + players && tries < 200) {
            tries++
            val cx = 8 + rnd.nextInt(size - 16)
            val cy = 8 + rnd.nextInt(size - 16)
            if (starts.any { dist(it.first.toFloat(), it.second.toFloat(), cx.toFloat(), cy.toFloat()) < 18f }) continue
            if (dist(cx.toFloat(), cy.toFloat(), mid.first.toFloat(), mid.second.toFloat()) < 8f) continue
            val res = if (extra % 2 == 0) Res.GOLD else Res.STONE
            cluster(game, map, Pair(cx, cy), 0f, 0f, res, 4, if (res == Res.GOLD) 450f else 350f, rnd)
            extra++
        }
        ensureReachable(game, map, starts, rnd)
        return Result(map, starts)
    }

    /** Stellt sicher, dass jedes Vorkommen von einem Startplatz aus erreichbar ist. */
    private fun ensureReachable(game: Game, map: GameMap, starts: List<Pair<Int, Int>>, rnd: Random) {
        for (pass in 0 until 3) {
            val reach = BooleanArray(map.w * map.h)
            val stack = ArrayList<Int>()
            for (s in starts) { val i = map.idx(s.first, s.second); reach[i] = true; stack.add(i) }
            while (stack.isNotEmpty()) {
                val c = stack.removeAt(stack.size - 1)
                val x = c % map.w
                val y = c / map.w
                for (d in 0 until 4) {
                    val nx = x + intArrayOf(1, -1, 0, 0)[d]
                    val ny = y + intArrayOf(0, 0, 1, -1)[d]
                    if (!map.walkable(nx, ny)) continue
                    val ni = map.idx(nx, ny)
                    if (reach[ni]) continue
                    reach[ni] = true
                    stack.add(ni)
                }
            }
            var fixed = 0
            for (n in game.nodes) {
                var ok = false
                for (dy in -1..1) for (dx in -1..1) {
                    val x = n.tx + dx
                    val y = n.ty + dy
                    if (map.inside(x, y) && reach[map.idx(x, y)]) ok = true
                }
                if (!ok) {
                    val s = starts.minByOrNull { dist(it.first.toFloat(), it.second.toFloat(), n.x, n.y) }!!
                    carveNarrow(map, n.tx, n.ty, s.first, s.second)
                    fixed++
                }
            }
            if (fixed == 0) return
        }
    }

    private fun carveNarrow(map: GameMap, ax: Int, ay: Int, bx: Int, by: Int) {
        val steps = max(kotlin.math.abs(bx - ax), kotlin.math.abs(by - ay)) * 2
        for (s in 0..steps) {
            val t = s.toFloat() / max(1, steps)
            val x = (ax + (bx - ax) * t).roundToInt()
            val y = (ay + (by - ay) * t).roundToInt()
            for (dy in 0..1) for (dx in 0..1) {
                val xx = x + dx
                val yy = y + dy
                if (!map.inside(xx, yy)) continue
                val i = map.idx(xx, yy)
                if (map.nodeAt[i] != 0) continue
                if (map.terrain[i] != Terrain.GRASS) {
                    map.terrain[i] = Terrain.GRASS
                    map.wood[i] = 0f
                }
            }
        }
    }

    private fun valueNoise(size: Int, cell: Int, rnd: Random): FloatArray {
        val gw = size / cell + 2
        val grid = FloatArray(gw * gw) { rnd.nextFloat() }
        val out = FloatArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            val fx = x.toFloat() / cell
            val fy = y.toFloat() / cell
            val ix = floor(fx).toInt()
            val iy = floor(fy).toInt()
            val tx = smooth(fx - ix)
            val ty = smooth(fy - iy)
            val a = grid[iy * gw + ix]
            val b = grid[iy * gw + ix + 1]
            val c = grid[(iy + 1) * gw + ix]
            val d = grid[(iy + 1) * gw + ix + 1]
            out[y * size + x] = lerp(lerp(a, b, tx), lerp(c, d, tx), ty)
        }
        return out
    }

    private fun smooth(t: Float) = t * t * (3 - 2 * t)
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private inline fun blob(map: GameMap, cx: Int, cy: Int, r: Float, rnd: Random, set: (Int) -> Unit) {
        val ri = r.toInt() + 2
        val wob = FloatArray(8) { 0.75f + rnd.nextFloat() * 0.5f }
        for (y in cy - ri..cy + ri) for (x in cx - ri..cx + ri) {
            if (!map.inside(x, y)) continue
            val dx = (x - cx).toFloat()
            val dy = (y - cy).toFloat()
            val ang = kotlin.math.atan2(dy, dx)
            val k = (((ang + 3.1416f) / 6.2832f) * 8).toInt().coerceIn(0, 7)
            if (dx * dx + dy * dy <= r * r * wob[k] * wob[k]) set(map.idx(x, y))
        }
    }

    private fun clearCircle(map: GameMap, cx: Int, cy: Int, r: Float) {
        val ri = r.toInt() + 1
        for (y in cy - ri..cy + ri) for (x in cx - ri..cx + ri) {
            if (!map.inside(x, y)) continue
            val dx = (x - cx).toFloat()
            val dy = (y - cy).toFloat()
            if (dx * dx + dy * dy <= r * r) {
                val i = map.idx(x, y)
                map.terrain[i] = Terrain.GRASS
                map.wood[i] = 0f
            }
        }
    }

    private fun carve(map: GameMap, a: Pair<Int, Int>, b: Pair<Int, Int>, rnd: Random) {
        val steps = (dist(a.first.toFloat(), a.second.toFloat(), b.first.toFloat(), b.second.toFloat()) * 2).toInt()
        var wobble = 0f
        for (s in 0..steps) {
            val t = s.toFloat() / max(1, steps)
            wobble += (rnd.nextFloat() - 0.5f) * 0.6f
            wobble = clampF(wobble, -4f, 4f)
            val px = a.first + (b.first - a.first) * t + wobble * (b.second - a.second) / max(1f, steps / 2f)
            val py = a.second + (b.second - a.second) * t - wobble * (b.first - a.first) / max(1f, steps / 2f)
            val ix = px.roundToInt()
            val iy = py.roundToInt()
            for (dy in -1..1) for (dx in -1..1) {
                val x = ix + dx
                val y = iy + dy
                if (map.inside(x, y) && x > 1 && y > 1 && x < map.w - 2 && y < map.h - 2) {
                    val i = map.idx(x, y)
                    map.terrain[i] = Terrain.GRASS
                    map.wood[i] = 0f
                }
            }
        }
    }

    private fun forestPatch(map: GameMap, s: Pair<Int, Int>, ang: Float, d: Float, r: Float, rnd: Random) {
        val cx = (s.first + cos(ang) * d).roundToInt()
        val cy = (s.second + sin(ang) * d).roundToInt()
        blob(map, cx, cy, r, rnd) { i ->
            val x = i % map.w
            val y = i / map.w
            if (dist(x.toFloat(), y.toFloat(), s.first.toFloat(), s.second.toFloat()) > 7.5f &&
                map.terrain[i] == Terrain.GRASS && map.nodeAt[i] == 0
            ) {
                map.terrain[i] = Terrain.FOREST
                map.wood[i] = 100f
            }
        }
    }

    private fun cluster(
        game: Game, map: GameMap, s: Pair<Int, Int>, ang: Float, d: Float, res: Res, count: Int,
        amount: Float, rnd: Random
    ) {
        var cx = (s.first + cos(ang) * d).roundToInt()
        var cy = (s.second + sin(ang) * d).roundToInt()
        cx = cx.coerceIn(4, map.w - 5)
        cy = cy.coerceIn(4, map.h - 5)
        // Platz rund um das Vorkommen freiräumen (Bäume entfernen)
        val clearR = if (count > 4) 3.6f else 3.0f
        for (y in cy - 4..cy + 4) for (x in cx - 4..cx + 4) {
            if (!map.inside(x, y)) continue
            val i = map.idx(x, y)
            if (map.terrain[i] == Terrain.FOREST && dist(x.toFloat(), y.toFloat(), cx.toFloat(), cy.toFloat()) <= clearR) {
                map.terrain[i] = Terrain.GRASS
                map.wood[i] = 0f
            }
        }
        var placed = 0
        var r = 0
        // Spirale um den Mittelpunkt, kompakter Haufen
        while (placed < count && r < 6) {
            for (dy in -r..r) for (dx in -r..r) {
                if (placed >= count) break
                if (max(kotlin.math.abs(dx), kotlin.math.abs(dy)) != r) continue
                val x = cx + dx
                val y = cy + dy
                if (!map.inside(x, y) || x < 2 || y < 2 || x > map.w - 3 || y > map.h - 3) continue
                val i = map.idx(x, y)
                if (map.terrain[i] == Terrain.WATER || map.nodeAt[i] != 0) continue
                if (res == Res.FOOD && (dx + dy) % 2 != 0 && r > 0) continue
                map.terrain[i] = Terrain.GRASS
                map.wood[i] = 0f
                game.addNode(map, res, x, y, amount)
                placed++
            }
            r++
        }
    }
}
