package de.goldenunicorn.kronenkrieg.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import de.goldenunicorn.kronenkrieg.engine.Building
import de.goldenunicorn.kronenkrieg.engine.BuildingKind
import de.goldenunicorn.kronenkrieg.engine.EffectKind
import de.goldenunicorn.kronenkrieg.engine.FactionId
import de.goldenunicorn.kronenkrieg.engine.GUnit
import de.goldenunicorn.kronenkrieg.engine.Game
import de.goldenunicorn.kronenkrieg.engine.Res
import de.goldenunicorn.kronenkrieg.engine.ResourceNode
import de.goldenunicorn.kronenkrieg.engine.Terrain
import de.goldenunicorn.kronenkrieg.engine.UnitKind
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Zeichnet die Spielwelt in 2D-Draufsicht. */
class WorldRenderer(private val density: Float) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val plain = Paint().apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()
    private val rect = RectF()

    private var minimap: Bitmap? = null
    private var miniPixels = IntArray(0)
    private var miniTimer = 0f

    // Kamera (in Kacheln) und Kachelgröße in Pixel
    var camX = 0f
    var camY = 0f
    var ts = 40f

    fun sx(wx: Float) = (wx - camX) * ts
    fun sy(wy: Float) = (wy - camY) * ts
    fun wx(sx: Float) = sx / ts + camX
    fun wy(sy: Float) = sy / ts + camY

    fun draw(c: Canvas, g: Game, s: Session, w: Int, h: Int) {
        val map = g.map
        val fog = g.fogs[g.humanIndex]
        val x0 = max(0, camX.toInt() - 1)
        val y0 = max(0, camY.toInt() - 1)
        val x1 = min(map.w - 1, (camX + w / ts).toInt() + 1)
        val y1 = min(map.h - 1, (camY + h / ts).toInt() + 1)
        c.drawColor(0xFF0B0B0E.toInt())

        // Boden
        for (y in y0..y1) for (x in x0..x1) {
            val i = map.idx(x, y)
            if (!fog.explored[i]) continue
            val l = sx(x.toFloat())
            val t = sy(y.toFloat())
            val sh = map.shade[i]
            when (map.terrain[i]) {
                Terrain.WATER -> {
                    plain.color = mix(0xFF2B5F8E.toInt(), 0xFF2F6A9A.toInt(), sh)
                    c.drawRect(l, t, l + ts + 1, t + ts + 1, plain)
                    // Wellen
                    val ph = (g.time * 0.8f + x * 0.7f + y * 0.3f) % 1f
                    stroke.color = 0x40FFFFFF
                    stroke.strokeWidth = ts * 0.04f
                    c.drawLine(l + ts * 0.15f, t + ts * (0.3f + ph * 0.4f), l + ts * 0.55f, t + ts * (0.3f + ph * 0.4f), stroke)
                    // Ufer
                    if (map.inside(x, y - 1) && map.terrain[map.idx(x, y - 1)] != Terrain.WATER) {
                        plain.color = 0xFFC9B27A.toInt()
                        c.drawRect(l, t, l + ts + 1, t + ts * 0.12f, plain)
                    }
                }
                else -> {
                    plain.color = mix(0xFF4E7A35.toInt(), 0xFF5C8A3D.toInt(), sh)
                    c.drawRect(l, t, l + ts + 1, t + ts + 1, plain)
                    if (sh > 0.86f) {
                        // Blumen / Grasbüschel
                        fill.color = if (sh > 0.96f) 0xFFE8D85A.toInt() else 0xFF3F6A2A.toInt()
                        c.drawCircle(l + ts * (0.2f + sh * 0.5f), t + ts * (0.7f - sh * 0.3f), ts * 0.05f, fill)
                    }
                }
            }
        }
        // Felder unter allem anderen
        for (b in g.buildings) if (b.kind == BuildingKind.FARM && buildingSeen(g, b)) drawFarm(c, g, b)

        // Effekte am Boden
        for (e in g.effects) {
            if (!fog.visible[map.idx(e.x.toInt().coerceIn(0, map.w - 1), e.y.toInt().coerceIn(0, map.h - 1))]) continue
            val a = 1f - e.t / e.duration
            when (e.kind) {
                EffectKind.CORPSE -> {
                    fill.color = alpha(0xFF3A1A12.toInt(), a * 0.7f)
                    rect.set(sx(e.x) - ts * 0.3f, sy(e.y) - ts * 0.15f, sx(e.x) + ts * 0.3f, sy(e.y) + ts * 0.15f)
                    c.drawOval(rect, fill)
                }
                EffectKind.RUBBLE -> {
                    fill.color = alpha(0xFF55504A.toInt(), a)
                    val r = e.size * ts * 0.5f
                    for (k in 0 until 7) {
                        val ox = cos(k * 2.1f) * r * 0.6f
                        val oy = sin(k * 1.7f) * r * 0.6f
                        c.drawCircle(sx(e.x) + ox, sy(e.y) + oy, ts * 0.22f, fill)
                    }
                }
                else -> {}
            }
        }

        // Bäume und Rohstoffe (zeilenweise, damit sich Kronen überlappen)
        for (y in y0..y1) for (x in x0..x1) {
            val i = map.idx(x, y)
            if (!fog.explored[i]) continue
            if (map.terrain[i] == Terrain.FOREST) drawTree(c, x, y, map.shade[i], map.wood[i])
        }
        for (n in g.nodes) {
            if (n.tx < x0 || n.tx > x1 || n.ty < y0 || n.ty > y1) continue
            if (!fog.explored[map.idx(n.tx, n.ty)]) continue
            drawNode(c, n, s.isSelected(n.id))
        }

        // Gebäude
        for (b in g.buildings) {
            if (b.kind == BuildingKind.FARM) continue
            if (b.tx + b.size < x0 || b.tx > x1 + 1 || b.ty + b.size < y0 || b.ty > y1 + 1) continue
            if (!buildingSeen(g, b)) continue
            drawBuilding(c, g, b, s.isSelected(b.id))
        }

        // Einheiten
        for (u in g.units) {
            if (u.x < x0 - 1 || u.x > x1 + 2 || u.y < y0 - 1 || u.y > y1 + 2) continue
            if (u.owner != g.humanIndex && !fog.visible[map.idx(u.x.toInt(), u.y.toInt())]) continue
            drawUnit(c, g, u, s.isSelected(u.id))
        }

        // Geschosse
        for (p in g.projectiles) {
            val dx = p.tx - p.x
            val dy = p.ty - p.y
            val d = max(0.01f, kotlin.math.sqrt(dx * dx + dy * dy))
            val len = 0.35f
            stroke.color = if (p.heavy) 0xFFF4E3A0.toInt() else 0xFF2A1E12.toInt()
            stroke.strokeWidth = ts * if (p.heavy) 0.07f else 0.05f
            c.drawLine(sx(p.x - dx / d * len), sy(p.y - dy / d * len), sx(p.x), sy(p.y), stroke)
        }
        for (e in g.effects) {
            if (e.kind != EffectKind.HIT) continue
            if (!fog.visible[map.idx(e.x.toInt().coerceIn(0, map.w - 1), e.y.toInt().coerceIn(0, map.h - 1))]) continue
            fill.color = alpha(0xFFFF5533.toInt(), 1f - e.t / e.duration)
            c.drawCircle(sx(e.x), sy(e.y) - ts * 0.1f, ts * 0.12f, fill)
        }

        // Nebel
        for (y in y0..y1) for (x in x0..x1) {
            val i = map.idx(x, y)
            if (!fog.explored[i]) {
                plain.color = 0xFF0B0B0E.toInt()
                c.drawRect(sx(x.toFloat()), sy(y.toFloat()), sx(x + 1f) + 1, sy(y + 1f) + 1, plain)
            } else if (!fog.visible[i]) {
                plain.color = 0x78000000
                c.drawRect(sx(x.toFloat()), sy(y.toFloat()), sx(x + 1f) + 1, sy(y + 1f) + 1, plain)
            }
        }

        // Befehlsmarker
        if (s.markerTime > 0f) {
            val a = s.markerTime
            stroke.color = alpha(if (s.markerAttack) 0xFFFF4040.toInt() else 0xFF7CFF7C.toInt(), a)
            stroke.strokeWidth = 2.5f * density
            c.drawCircle(sx(s.markerX), sy(s.markerY), ts * (0.5f - a * 0.25f), stroke)
        }
        // Sammelpunkt des gewählten Gebäudes
        val sel = s.selectedBuilding(g)
        if (sel != null && sel.owner == g.humanIndex && sel.rallyX >= 0f) {
            stroke.color = 0xAAFFFFFF.toInt()
            stroke.strokeWidth = 1.5f * density
            c.drawLine(sx(sel.x), sy(sel.y), sx(sel.rallyX), sy(sel.rallyY), stroke)
            drawFlag(c, sel.rallyX, sel.rallyY, g.players[sel.owner].color)
        }
    }

    fun buildingSeen(g: Game, b: Building): Boolean {
        if (b.owner == g.humanIndex) return true
        val fog = g.fogs[g.humanIndex]
        for (y in b.ty until b.ty + b.size) for (x in b.tx until b.tx + b.size)
            if (fog.explored[g.map.idx(x, y)]) return true
        return false
    }

    private fun drawTree(c: Canvas, x: Int, y: Int, sh: Float, wood: Float) {
        val cx = sx(x + 0.5f + (sh - 0.5f) * 0.25f)
        val cy = sy(y + 0.45f + (0.5f - sh) * 0.2f)
        val r = ts * (0.42f + sh * 0.12f) * (0.65f + 0.35f * min(1f, wood / 100f))
        fill.color = 0x55000000
        c.drawCircle(cx + ts * 0.08f, cy + ts * 0.12f, r, fill)
        fill.color = 0xFF5A3A1E.toInt()
        c.drawRect(cx - ts * 0.05f, cy, cx + ts * 0.05f, cy + ts * 0.35f, fill)
        val conifer = sh > 0.55f
        if (conifer) {
            fill.color = mix(0xFF1F4A2A.toInt(), 0xFF2A5A30.toInt(), sh)
            path.reset()
            path.moveTo(cx, cy - r * 1.15f)
            path.lineTo(cx + r * 0.9f, cy + r * 0.6f)
            path.lineTo(cx - r * 0.9f, cy + r * 0.6f)
            path.close()
            c.drawPath(path, fill)
            fill.color = 0x30FFFFFF
            path.reset()
            path.moveTo(cx, cy - r * 1.15f)
            path.lineTo(cx - r * 0.9f, cy + r * 0.6f)
            path.lineTo(cx - r * 0.2f, cy + r * 0.6f)
            path.close()
            c.drawPath(path, fill)
        } else {
            fill.color = mix(0xFF2E6B2C.toInt(), 0xFF3B7A34.toInt(), sh)
            c.drawCircle(cx, cy, r, fill)
            fill.color = 0xFF4C8C3E.toInt()
            c.drawCircle(cx - r * 0.3f, cy - r * 0.3f, r * 0.5f, fill)
        }
    }

    private fun drawNode(c: Canvas, n: ResourceNode, selected: Boolean) {
        val cx = sx(n.x)
        val cy = sy(n.y)
        val k = 0.55f + 0.45f * (n.amount / n.maxAmount)
        when (n.res) {
            Res.FOOD -> {
                fill.color = 0xFF2F6B2A.toInt()
                c.drawCircle(cx, cy, ts * 0.42f * k, fill)
                fill.color = 0xFF3E8436.toInt()
                c.drawCircle(cx - ts * 0.12f, cy - ts * 0.1f, ts * 0.25f * k, fill)
                fill.color = 0xFFD8304A.toInt()
                for (j in 0 until 5) {
                    c.drawCircle(cx + cos(j * 1.3f) * ts * 0.22f * k, cy + sin(j * 1.9f) * ts * 0.2f * k, ts * 0.06f, fill)
                }
            }
            Res.GOLD, Res.STONE -> {
                val base = if (n.res == Res.GOLD) 0xFF8B7A5A.toInt() else 0xFF8A8A8C.toInt()
                val hi = if (n.res == Res.GOLD) 0xFFF2CC3A.toInt() else 0xFFC9C9CC.toInt()
                fill.color = 0x55000000
                c.drawCircle(cx + ts * 0.06f, cy + ts * 0.1f, ts * 0.45f * k, fill)
                fill.color = base
                path.reset()
                path.moveTo(cx - ts * 0.45f * k, cy + ts * 0.3f * k)
                path.lineTo(cx - ts * 0.3f * k, cy - ts * 0.3f * k)
                path.lineTo(cx + ts * 0.1f * k, cy - ts * 0.45f * k)
                path.lineTo(cx + ts * 0.45f * k, cy - ts * 0.05f * k)
                path.lineTo(cx + ts * 0.35f * k, cy + ts * 0.38f * k)
                path.close()
                c.drawPath(path, fill)
                fill.color = hi
                c.drawCircle(cx - ts * 0.1f, cy - ts * 0.08f, ts * 0.1f * k, fill)
                c.drawCircle(cx + ts * 0.15f, cy + ts * 0.1f, ts * 0.08f * k, fill)
                c.drawCircle(cx + ts * 0.02f, cy - ts * 0.25f * k, ts * 0.06f * k, fill)
            }
            Res.WOOD -> {}
        }
        if (selected) {
            stroke.color = 0xFFFFFFFF.toInt()
            stroke.strokeWidth = 2f * density
            c.drawCircle(cx, cy, ts * 0.55f, stroke)
        }
    }

    private fun drawFarm(c: Canvas, g: Game, b: Building) {
        val l = sx(b.tx.toFloat())
        val t = sy(b.ty.toFloat())
        val s = b.size * ts
        plain.color = if (b.complete) 0xFF7A5A2E.toInt() else 0xFF6A5A40.toInt()
        c.drawRect(l + ts * 0.08f, t + ts * 0.08f, l + s - ts * 0.08f, t + s - ts * 0.08f, plain)
        stroke.strokeWidth = ts * 0.12f
        stroke.color = if (b.complete) 0xFFC9A93E.toInt() else 0xFF8A7A50.toInt()
        val rows = 5
        val grown = if (b.complete) 1f else b.progress
        for (k in 0 until rows) {
            val yy = t + ts * 0.3f + k * (s - ts * 0.6f) / (rows - 1)
            c.drawLine(l + ts * 0.25f, yy, l + ts * 0.25f + (s - ts * 0.5f) * grown, yy, stroke)
        }
        stroke.color = g.players[b.owner].color
        stroke.strokeWidth = ts * 0.06f
        c.drawRect(l + ts * 0.08f, t + ts * 0.08f, l + s - ts * 0.08f, t + s - ts * 0.08f, stroke)
        if (b.id == sessionSelectedId) {
            stroke.color = 0xFFFFFFFF.toInt()
            stroke.strokeWidth = 2f * density
            c.drawRect(l, t, l + s, t + s, stroke)
        }
    }

    var sessionSelectedId = 0

    private fun drawBuilding(c: Canvas, g: Game, b: Building, selected: Boolean) {
        val p = g.players[b.owner]
        val f = p.faction
        val l = sx(b.tx.toFloat())
        val t = sy(b.ty.toFloat())
        val s = b.size * ts
        val m = ts * 0.12f
        val inner = RectF(l + m, t + m, l + s - m, t + s - m)
        // Schatten
        fill.color = 0x55000000
        rect.set(inner.left + ts * 0.15f, inner.top + ts * 0.2f, inner.right + ts * 0.15f, inner.bottom + ts * 0.2f)
        c.drawRect(rect, fill)
        if (!b.complete) {
            // Baustelle: Gerüst
            fill.color = 0xFF8A6A40.toInt()
            c.drawRect(inner, fill)
            stroke.color = 0xFF5A3E1E.toInt()
            stroke.strokeWidth = ts * 0.06f
            var k = 0f
            while (k < 1f) {
                val xx = inner.left + inner.width() * k
                c.drawLine(xx, inner.top, xx, inner.bottom, stroke)
                k += 0.25f
            }
            c.drawLine(inner.left, inner.top, inner.right, inner.bottom, stroke)
            c.drawLine(inner.right, inner.top, inner.left, inner.bottom, stroke)
            // Fortschritt als wachsende Mauer
            fill.color = f.wallColor
            c.drawRect(inner.left, inner.bottom - inner.height() * b.progress, inner.right, inner.bottom, fill)
            bar(c, inner.left, inner.top - ts * 0.25f, inner.width(), b.progress, 0xFFE0C050.toInt())
            stroke.color = p.color
            stroke.strokeWidth = ts * 0.08f
            c.drawRect(inner, stroke)
            if (selected) selectRect(c, l, t, s)
            return
        }
        val rounded = f.id == FactionId.ELBEN
        val r = if (rounded) ts * 0.35f else ts * 0.06f
        // Mauern
        fill.color = f.wallColor
        c.drawRoundRect(inner, r, r, fill)
        // Spitzen für Orks
        if (f.id == FactionId.ORKS) {
            fill.color = 0xFF4A3420.toInt()
            val n = b.size * 2
            for (k in 0..n) {
                val xx = inner.left + inner.width() * k / n
                spike(c, xx, inner.top, -1f)
                spike(c, xx, inner.bottom, 1f)
            }
        }
        // Dach je Gebäudeart
        val cx = inner.centerX()
        val cy = inner.centerY()
        when (b.kind) {
            BuildingKind.TOWNHALL -> {
                fill.color = f.roofColor
                val k = ts * 0.9f
                c.drawRoundRect(RectF(cx - k, cy - k, cx + k, cy + k), r, r, fill)
                // Ecktürme
                fill.color = shade(f.wallColor, 0.8f)
                val tr = ts * 0.42f
                c.drawCircle(inner.left + tr, inner.top + tr, tr, fill)
                c.drawCircle(inner.right - tr, inner.top + tr, tr, fill)
                c.drawCircle(inner.left + tr, inner.bottom - tr, tr, fill)
                c.drawCircle(inner.right - tr, inner.bottom - tr, tr, fill)
                fill.color = f.roofColor
                for (q in 0 until 4) {
                    val qx = if (q % 2 == 0) inner.left + tr else inner.right - tr
                    val qy = if (q < 2) inner.top + tr else inner.bottom - tr
                    c.drawCircle(qx, qy, tr * 0.6f, fill)
                }
                fill.color = f.accentColor
                c.drawCircle(cx, cy, ts * 0.3f, fill)
                drawFlag(c, b.x + 0.2f, b.y - 0.2f, p.color)
            }
            BuildingKind.HOUSE -> {
                roof(c, inner, f.roofColor)
            }
            BuildingKind.STOREHOUSE -> {
                roof(c, inner, shade(f.roofColor, 0.85f))
                fill.color = 0xFF9A6A3A.toInt()
                val q = ts * 0.22f
                c.drawRect(cx - q * 2.2f, cy + q * 0.3f, cx - q * 0.4f, cy + q * 2f, fill)
                c.drawRect(cx + q * 0.4f, cy + q * 0.3f, cx + q * 2.2f, cy + q * 2f, fill)
            }
            BuildingKind.BARRACKS, BuildingKind.RANGE, BuildingKind.STABLE, BuildingKind.WORKSHOP -> {
                roof(c, inner, f.roofColor)
                // Symbol
                val sym = ts * 0.45f
                fill.color = 0xFFF4EEDC.toInt()
                c.drawCircle(cx, cy, sym * 1.05f, fill)
                stroke.color = 0xFF2A2420.toInt()
                stroke.strokeWidth = ts * 0.08f
                when (b.kind) {
                    BuildingKind.BARRACKS -> {
                        c.drawLine(cx - sym * 0.6f, cy - sym * 0.6f, cx + sym * 0.6f, cy + sym * 0.6f, stroke)
                        c.drawLine(cx + sym * 0.6f, cy - sym * 0.6f, cx - sym * 0.6f, cy + sym * 0.6f, stroke)
                    }
                    BuildingKind.RANGE -> {
                        c.drawCircle(cx, cy, sym * 0.6f, stroke)
                        fill.color = 0xFFC03030.toInt()
                        c.drawCircle(cx, cy, sym * 0.25f, fill)
                    }
                    BuildingKind.STABLE -> {
                        rect.set(cx - sym * 0.5f, cy - sym * 0.6f, cx + sym * 0.5f, cy + sym * 0.5f)
                        c.drawArc(rect, 0f, -180f, false, stroke)
                        c.drawLine(cx - sym * 0.5f, cy - sym * 0.05f, cx - sym * 0.5f, cy + sym * 0.5f, stroke)
                        c.drawLine(cx + sym * 0.5f, cy - sym * 0.05f, cx + sym * 0.5f, cy + sym * 0.5f, stroke)
                    }
                    else -> {
                        c.drawCircle(cx, cy, sym * 0.45f, stroke)
                        for (k in 0 until 6) {
                            val a = k * 1.047f
                            c.drawLine(cx + cos(a) * sym * 0.45f, cy + sin(a) * sym * 0.45f,
                                cx + cos(a) * sym * 0.75f, cy + sin(a) * sym * 0.75f, stroke)
                        }
                    }
                }
            }
            BuildingKind.TOWER -> {
                fill.color = shade(f.wallColor, 0.85f)
                c.drawCircle(cx, cy, ts * 0.75f, fill)
                fill.color = f.roofColor
                c.drawCircle(cx, cy, ts * 0.5f, fill)
                fill.color = f.accentColor
                c.drawCircle(cx, cy, ts * 0.15f, fill)
            }
            BuildingKind.FARM -> {}
        }
        if (f.id == FactionId.EISENBRUT) {
            // glühende Schmiedefenster
            fill.color = 0xCCFF7A2A.toInt()
            c.drawRect(inner.left + ts * 0.15f, inner.bottom - ts * 0.3f, inner.left + ts * 0.35f, inner.bottom - ts * 0.15f, fill)
            c.drawRect(inner.right - ts * 0.35f, inner.bottom - ts * 0.3f, inner.right - ts * 0.15f, inner.bottom - ts * 0.15f, fill)
        }
        // Spielerfarbe als Rand
        stroke.color = p.color
        stroke.strokeWidth = ts * 0.09f
        c.drawRoundRect(inner, r, r, stroke)
        // Produktionsanzeige
        if (b.queue.isNotEmpty() && b.owner == g.humanIndex) {
            val q = b.queue[0]
            bar(c, inner.left, inner.bottom + ts * 0.05f, inner.width(), q.time / q.totalTime, 0xFF6FB0FF.toInt())
        }
        if (selected) {
            selectRect(c, l, t, s)
        }
        if (selected || b.hp < b.maxHp) {
            bar(c, inner.left, inner.top - ts * 0.28f, inner.width(), b.hp / b.maxHp, hpColor(b.hp / b.maxHp))
        }
    }

    private fun spike(c: Canvas, x: Float, y: Float, dir: Float) {
        path.reset()
        path.moveTo(x - ts * 0.1f, y)
        path.lineTo(x, y + dir * ts * 0.22f)
        path.lineTo(x + ts * 0.1f, y)
        path.close()
        c.drawPath(path, fill)
    }

    private fun roof(c: Canvas, r: RectF, color: Int) {
        fill.color = color
        val m = ts * 0.12f
        rect.set(r.left + m, r.top + m, r.right - m, r.bottom - m)
        c.drawRect(rect, fill)
        fill.color = shade(color, 0.75f)
        path.reset()
        path.moveTo(rect.left, rect.top)
        path.lineTo(rect.right, rect.top)
        path.lineTo(rect.right, rect.bottom)
        path.close()
        c.drawPath(path, fill)
        stroke.color = shade(color, 0.6f)
        stroke.strokeWidth = ts * 0.04f
        c.drawLine(rect.left, rect.top, rect.right, rect.bottom, stroke)
    }

    private fun selectRect(c: Canvas, l: Float, t: Float, s: Float) {
        stroke.color = 0xFFFFFFFF.toInt()
        stroke.strokeWidth = 2f * density
        c.drawRect(l, t, l + s, t + s, stroke)
    }

    fun drawFlag(c: Canvas, wx: Float, wy: Float, color: Int) {
        val x = sx(wx)
        val y = sy(wy)
        stroke.color = 0xFF3A2A1A.toInt()
        stroke.strokeWidth = ts * 0.05f
        c.drawLine(x, y, x, y - ts * 0.8f, stroke)
        fill.color = color
        path.reset()
        path.moveTo(x, y - ts * 0.8f)
        path.lineTo(x + ts * 0.45f, y - ts * 0.65f)
        path.lineTo(x, y - ts * 0.5f)
        path.close()
        c.drawPath(path, fill)
    }

    private fun drawUnit(c: Canvas, g: Game, u: GUnit, selected: Boolean) {
        val p = g.players[u.owner]
        val f = p.faction
        val cx = sx(u.x)
        val bob = if (u.working || u.path != null) sin(u.animTime * 12f) * ts * 0.03f else 0f
        val cy = sy(u.y) + bob
        val r = ts * u.radius
        // Schatten
        fill.color = 0x50000000
        rect.set(cx - r * 1.05f, cy + r * 0.3f, cx + r * 1.05f, cy + r * 1.0f)
        c.drawOval(rect, fill)
        if (selected) {
            stroke.color = 0xFF7CFF7C.toInt()
            stroke.strokeWidth = 1.8f * density
            rect.set(cx - r * 1.35f, cy + r * 0.1f, cx + r * 1.35f, cy + r * 1.2f)
            c.drawOval(rect, stroke)
        }
        val fx = cos(u.facing)
        val fy = sin(u.facing)
        when (u.kind) {
            UnitKind.CAVALRY -> {
                // Reittier
                fill.color = when (f.id) {
                    FactionId.ORKS -> 0xFF6A6A70.toInt()
                    FactionId.ELBEN -> 0xFFB08A5A.toInt()
                    FactionId.EISENBRUT -> 0xFF4A3426.toInt()
                    else -> 0xFF7A5232.toInt()
                }
                path.reset()
                path.moveTo(cx + fx * r * 1.4f, cy + fy * r * 1.4f)
                path.lineTo(cx - fy * r * 0.6f, cy + fx * r * 0.6f)
                path.lineTo(cx - fx * r * 1.2f, cy - fy * r * 1.2f)
                path.lineTo(cx + fy * r * 0.6f, cy - fx * r * 0.6f)
                path.close()
                c.drawPath(path, fill)
                body(c, cx, cy, r * 0.62f, p.color, f.accentColor)
                weapon(c, cx, cy, fx, fy, r * 1.5f, 0xFFDDDDDD.toInt(), ts * 0.05f)
            }
            UnitKind.SIEGE -> {
                fill.color = 0xFF6A4424.toInt()
                path.reset()
                val a = r * 1.2f
                val bb = r * 0.75f
                path.moveTo(cx + fx * a - fy * bb, cy + fy * a + fx * bb)
                path.lineTo(cx + fx * a + fy * bb, cy + fy * a - fx * bb)
                path.lineTo(cx - fx * a + fy * bb, cy - fy * a - fx * bb)
                path.lineTo(cx - fx * a - fy * bb, cy - fy * a + fx * bb)
                path.close()
                c.drawPath(path, fill)
                stroke.color = 0xFF3A3A3A.toInt()
                stroke.strokeWidth = ts * 0.14f
                c.drawLine(cx - fx * r * 0.6f, cy - fy * r * 0.6f, cx + fx * r * 1.6f, cy + fy * r * 1.6f, stroke)
                fill.color = p.color
                c.drawCircle(cx, cy, r * 0.35f, fill)
            }
            else -> {
                body(c, cx, cy, r, p.color, f.accentColor)
                when (u.kind) {
                    UnitKind.WORKER -> {
                        weapon(c, cx, cy, fx, fy, r * 1.1f, 0xFF8A6A3A.toInt(), ts * 0.05f)
                        if (u.carry > 0f) {
                            fill.color = when (u.carryRes) {
                                Res.WOOD -> 0xFF8A5A2A.toInt()
                                Res.GOLD -> 0xFFF2CC3A.toInt()
                                Res.STONE -> 0xFFBDBDBD.toInt()
                                else -> 0xFFD8304A.toInt()
                            }
                            c.drawRect(cx - fx * r * 0.9f - r * 0.3f, cy - fy * r * 0.9f - r * 0.3f,
                                cx - fx * r * 0.9f + r * 0.3f, cy - fy * r * 0.9f + r * 0.3f, fill)
                        }
                    }
                    UnitKind.INFANTRY -> {
                        weapon(c, cx, cy, fx, fy, r * 1.4f, 0xFFE8E8E8.toInt(), ts * 0.06f)
                        shield(c, cx, cy, fx, fy, r, f.accentColor)
                    }
                    UnitKind.SPEAR -> weapon(c, cx, cy, fx, fy, r * 2.2f, 0xFFB89A6A.toInt(), ts * 0.045f)
                    UnitKind.ARCHER -> bow(c, cx, cy, fx, fy, r)
                    UnitKind.SPECIAL -> {
                        if (u.stats.isRanged) bow(c, cx, cy, fx, fy, r * 1.2f)
                        else {
                            weapon(c, cx, cy, fx, fy, r * 1.6f, 0xFFF4E3A0.toInt(), ts * 0.08f)
                            shield(c, cx, cy, fx, fy, r * 1.15f, f.accentColor)
                        }
                        // Krone als Elite-Zeichen
                        fill.color = 0xFFF2CC3A.toInt()
                        c.drawCircle(cx, cy - r * 0.15f, r * 0.25f, fill)
                    }
                    else -> {}
                }
            }
        }
        if (selected || u.hp < u.maxHp) {
            bar(c, cx - ts * 0.35f, cy - r - ts * 0.22f, ts * 0.7f, u.hp / u.maxHp, hpColor(u.hp / u.maxHp))
        }
    }

    private fun body(c: Canvas, cx: Float, cy: Float, r: Float, color: Int, accent: Int) {
        fill.color = shade(color, 0.7f)
        c.drawCircle(cx, cy, r, fill)
        fill.color = color
        c.drawCircle(cx - r * 0.12f, cy - r * 0.12f, r * 0.8f, fill)
        fill.color = accent
        c.drawCircle(cx, cy, r * 0.32f, fill)
    }

    private fun weapon(c: Canvas, cx: Float, cy: Float, fx: Float, fy: Float, len: Float, color: Int, w: Float) {
        stroke.color = color
        stroke.strokeWidth = w
        val ox = -fy * ts * 0.18f
        val oy = fx * ts * 0.18f
        c.drawLine(cx + ox, cy + oy, cx + ox + fx * len, cy + oy + fy * len, stroke)
    }

    private fun shield(c: Canvas, cx: Float, cy: Float, fx: Float, fy: Float, r: Float, color: Int) {
        fill.color = color
        c.drawCircle(cx + fy * r * 0.7f + fx * r * 0.3f, cy - fx * r * 0.7f + fy * r * 0.3f, r * 0.42f, fill)
    }

    private fun bow(c: Canvas, cx: Float, cy: Float, fx: Float, fy: Float, r: Float) {
        stroke.color = 0xFF6A4424.toInt()
        stroke.strokeWidth = ts * 0.05f
        val bx = cx + fx * r * 0.9f
        val by = cy + fy * r * 0.9f
        rect.set(bx - r * 0.7f, by - r * 0.7f, bx + r * 0.7f, by + r * 0.7f)
        val deg = Math.toDegrees(kotlin.math.atan2(fy, fx).toDouble()).toFloat()
        c.drawArc(rect, deg - 70f, 140f, false, stroke)
    }

    private fun bar(c: Canvas, x: Float, y: Float, w: Float, frac: Float, color: Int) {
        val h = max(3f * density, ts * 0.09f)
        plain.color = 0xC0000000.toInt()
        c.drawRect(x, y, x + w, y + h, plain)
        plain.color = color
        c.drawRect(x + 1, y + 1, x + 1 + (w - 2) * frac.coerceIn(0f, 1f), y + h - 1, plain)
    }

    fun drawGhost(c: Canvas, g: Game, kind: BuildingKind, tx: Int, ty: Int, ok: Boolean) {
        val st = g.players[g.humanIndex].faction.building(kind)
        val l = sx(tx.toFloat())
        val t = sy(ty.toFloat())
        val s = st.size * ts
        fill.color = if (ok) 0x6640FF40 else 0x66FF3030
        c.drawRect(l, t, l + s, t + s, fill)
        stroke.color = if (ok) 0xFF60FF60.toInt() else 0xFFFF5050.toInt()
        stroke.strokeWidth = 2f * density
        c.drawRect(l, t, l + s, t + s, stroke)
        text.color = 0xFFFFFFFF.toInt()
        text.textSize = 13f * density
        c.drawText(st.name, l + s / 2, t - 6f * density, text)
    }

    // ------------------------------------------------------------------ Minikarte

    fun drawMinimap(c: Canvas, g: Game, area: RectF, dt: Float, viewW: Int, viewH: Int) {
        val map = g.map
        var bmp = minimap
        if (bmp == null || bmp.width != map.w) {
            bmp = Bitmap.createBitmap(map.w, map.h, Bitmap.Config.ARGB_8888)
            minimap = bmp
            miniPixels = IntArray(map.w * map.h)
            miniTimer = 0f
        }
        miniTimer -= dt
        if (miniTimer <= 0f) {
            miniTimer = 0.4f
            val fog = g.fogs[g.humanIndex]
            for (i in 0 until map.w * map.h) {
                if (!fog.explored[i]) { miniPixels[i] = 0xFF101014.toInt(); continue }
                var col = when (map.terrain[i]) {
                    Terrain.WATER -> 0xFF2B5F8E.toInt()
                    Terrain.FOREST -> 0xFF234F25.toInt()
                    else -> 0xFF4E7A35.toInt()
                }
                if (map.nodeAt[i] != 0) {
                    val n = g.entity(map.nodeAt[i]) as? ResourceNode
                    col = when (n?.res) {
                        Res.GOLD -> 0xFFF2CC3A.toInt()
                        Res.STONE -> 0xFFC0C0C4.toInt()
                        Res.FOOD -> 0xFFD8304A.toInt()
                        else -> col
                    }
                }
                val bid = map.buildingAt[i]
                if (bid != 0) {
                    val b = g.buildingById(bid)
                    if (b != null) col = g.players[b.owner].color
                }
                if (!fog.visible[i]) col = shade(col, 0.6f)
                miniPixels[i] = col
            }
            for (u in g.units) {
                val tx = u.x.toInt()
                val ty = u.y.toInt()
                if (!map.inside(tx, ty)) continue
                val i = map.idx(tx, ty)
                if (u.owner != g.humanIndex && !fog.visible[i]) continue
                miniPixels[i] = shade(g.players[u.owner].color, 1.25f)
            }
            bmp.setPixels(miniPixels, 0, map.w, 0, 0, map.w, map.h)
        }
        plain.color = 0xFF000000.toInt()
        c.drawRect(area.left - 2 * density, area.top - 2 * density, area.right + 2 * density, area.bottom + 2 * density, plain)
        c.drawBitmap(bmp, null as Rect?, area, plain)
        // Sichtfenster
        val kx = area.width() / map.w
        val ky = area.height() / map.h
        stroke.color = 0xFFFFFFFF.toInt()
        stroke.strokeWidth = 1.2f * density
        c.drawRect(
            area.left + camX * kx, area.top + camY * ky,
            area.left + (camX + viewW / ts) * kx, area.top + (camY + viewH / ts) * ky, stroke
        )
    }

    companion object {
        fun mix(a: Int, b: Int, t: Float): Int {
            val tt = t.coerceIn(0f, 1f)
            val ar = (a shr 16) and 0xFF; val ag = (a shr 8) and 0xFF; val ab = a and 0xFF
            val br = (b shr 16) and 0xFF; val bg = (b shr 8) and 0xFF; val bb = b and 0xFF
            val r = (ar + (br - ar) * tt).toInt()
            val gg = (ag + (bg - ag) * tt).toInt()
            val bl = (ab + (bb - ab) * tt).toInt()
            return (0xFF shl 24) or (r shl 16) or (gg shl 8) or bl
        }

        fun shade(c: Int, k: Float): Int {
            val r = (((c shr 16) and 0xFF) * k).toInt().coerceIn(0, 255)
            val g = (((c shr 8) and 0xFF) * k).toInt().coerceIn(0, 255)
            val b = ((c and 0xFF) * k).toInt().coerceIn(0, 255)
            return (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }

        fun alpha(c: Int, a: Float): Int = (((a.coerceIn(0f, 1f) * 255).toInt()) shl 24) or (c and 0xFFFFFF)

        fun hpColor(f: Float): Int = when {
            f > 0.6f -> 0xFF4CD04C.toInt()
            f > 0.3f -> 0xFFE0C030.toInt()
            else -> 0xFFE04030.toInt()
        }
    }
}
