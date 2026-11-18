package de.goldenunicorn.kronenkrieg.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object Terrain {
    const val GRASS: Byte = 0
    const val WATER: Byte = 1
    const val FOREST: Byte = 2
}

class GameMap(val w: Int, val h: Int) {
    val terrain = ByteArray(w * h)
    val wood = FloatArray(w * h)
    val shade = FloatArray(w * h)
    /** ID des Rohstoffknotens auf dieser Kachel (0 = keiner). */
    val nodeAt = IntArray(w * h)
    /** ID des Gebäudes auf dieser Kachel (0 = keins). */
    val buildingAt = IntArray(w * h)
    /** Gebäude blockiert die Kachel (Felder nicht). */
    val buildingBlocks = BooleanArray(w * h)
    /** Wird erhöht, wenn sich die Begehbarkeit ändert (Render-Cache). */
    var version = 0

    fun idx(x: Int, y: Int) = y * w + x
    fun inside(x: Int, y: Int) = x >= 0 && y >= 0 && x < w && y < h

    fun walkable(x: Int, y: Int): Boolean {
        if (!inside(x, y)) return false
        val i = idx(x, y)
        return terrain[i] == Terrain.GRASS && nodeAt[i] == 0 && !buildingBlocks[i]
    }

    fun walkableIdx(i: Int): Boolean = terrain[i] == Terrain.GRASS && nodeAt[i] == 0 && !buildingBlocks[i]

    /** Kachel frei für einen Bauplatz? */
    fun buildable(x: Int, y: Int): Boolean {
        if (!inside(x, y)) return false
        val i = idx(x, y)
        return terrain[i] == Terrain.GRASS && nodeAt[i] == 0 && buildingAt[i] == 0
    }

    fun isTree(x: Int, y: Int): Boolean = inside(x, y) && terrain[idx(x, y)] == Terrain.FOREST

    fun removeTree(x: Int, y: Int) {
        val i = idx(x, y)
        terrain[i] = Terrain.GRASS
        wood[i] = 0f
        version++
    }
}

/** A*-Wegfindung auf dem Kachelraster (8 Richtungen, keine Eckabkürzung). */
class Pathfinder(private val map: GameMap) {
    private val n = map.w * map.h
    private val gScore = FloatArray(n)
    private val parent = IntArray(n)
    private val stamp = IntArray(n)
    private val closedStamp = IntArray(n)
    private var cur = 0
    private val heap = IntArray(n * 2 + 16)
    private val heapF = FloatArray(n * 2 + 16)
    private var heapSize = 0

    private fun push(i: Int, f: Float) {
        var k = heapSize++
        heap[k] = i
        heapF[k] = f
        while (k > 0) {
            val p = (k - 1) / 2
            if (heapF[p] <= heapF[k]) break
            swap(p, k)
            k = p
        }
    }

    private fun swap(a: Int, b: Int) {
        val ti = heap[a]; heap[a] = heap[b]; heap[b] = ti
        val tf = heapF[a]; heapF[a] = heapF[b]; heapF[b] = tf
    }

    private fun pop(): Int {
        val r = heap[0]
        heapSize--
        if (heapSize > 0) {
            heap[0] = heap[heapSize]
            heapF[0] = heapF[heapSize]
            var k = 0
            while (true) {
                val l = k * 2 + 1
                val rr = l + 1
                var m = k
                if (l < heapSize && heapF[l] < heapF[m]) m = l
                if (rr < heapSize && heapF[rr] < heapF[m]) m = rr
                if (m == k) break
                swap(m, k)
                k = m
            }
        }
        return r
    }

    /**
     * Sucht einen Weg von (sx,sy) bis eine Kachel [isGoal] erfüllt.
     * Liefert Kachel-Indizes (ohne Start). Erreicht die Suche das Ziel nicht,
     * führt der Weg zur nächstgelegenen erreichbaren Kachel.
     */
    fun find(sx: Int, sy: Int, hx: Float, hy: Float, maxNodes: Int = 5000, isGoal: (Int, Int) -> Boolean): IntArray? {
        if (!map.inside(sx, sy)) return null
        cur++
        heapSize = 0
        val w = map.w
        val start = map.idx(sx, sy)
        gScore[start] = 0f
        parent[start] = -1
        stamp[start] = cur
        push(start, heur(sx, sy, hx, hy))
        var best = start
        var bestH = Float.MAX_VALUE
        var expanded = 0
        var found = -1
        while (heapSize > 0) {
            val c = pop()
            if (closedStamp[c] == cur) continue
            closedStamp[c] = cur
            val cx = c % w
            val cy = c / w
            if (isGoal(cx, cy)) { found = c; break }
            val hh = heur(cx, cy, hx, hy)
            if (hh < bestH) { bestH = hh; best = c }
            if (++expanded > maxNodes) break
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = cx + dx
                val ny = cy + dy
                if (!map.inside(nx, ny)) continue
                val ni = map.idx(nx, ny)
                if (!map.walkableIdx(ni)) {
                    // Zielkachel darf blockiert sein, wenn sie Ziel ist (z. B. Baum)
                    if (!isGoal(nx, ny)) continue
                    if (dx != 0 && dy != 0) continue
                }
                if (dx != 0 && dy != 0) {
                    if (!map.walkable(cx + dx, cy) || !map.walkable(cx, cy + dy)) continue
                }
                if (closedStamp[ni] == cur) continue
                val cost = if (dx != 0 && dy != 0) 1.4142f else 1f
                val g = gScore[c] + cost
                if (stamp[ni] != cur || g < gScore[ni]) {
                    stamp[ni] = cur
                    gScore[ni] = g
                    parent[ni] = c
                    push(ni, g + heur(nx, ny, hx, hy))
                }
            }
        }
        val end = if (found >= 0) found else best
        if (end == start) return IntArray(0)
        var len = 0
        var p = end
        while (p != start && p >= 0) { len++; p = parent[p] }
        val out = IntArray(len)
        p = end
        var k = len - 1
        while (p != start && p >= 0) { out[k--] = p; p = parent[p] }
        return out
    }

    private fun heur(x: Int, y: Int, hx: Float, hy: Float): Float {
        val dx = abs(x + 0.5f - hx)
        val dy = abs(y + 0.5f - hy)
        return max(dx, dy) + 0.4142f * min(dx, dy)
    }
}

fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = ax - bx
    val dy = ay - by
    return sqrt(dx * dx + dy * dy)
}
