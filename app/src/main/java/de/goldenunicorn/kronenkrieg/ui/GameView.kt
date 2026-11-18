package de.goldenunicorn.kronenkrieg.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import de.goldenunicorn.kronenkrieg.engine.Building
import de.goldenunicorn.kronenkrieg.engine.BuildingKind
import de.goldenunicorn.kronenkrieg.engine.Faction
import de.goldenunicorn.kronenkrieg.engine.FactionId
import de.goldenunicorn.kronenkrieg.engine.GUnit
import de.goldenunicorn.kronenkrieg.engine.Game
import de.goldenunicorn.kronenkrieg.engine.GameConfig
import de.goldenunicorn.kronenkrieg.engine.Order
import de.goldenunicorn.kronenkrieg.engine.Res
import de.goldenunicorn.kronenkrieg.engine.ResourceNode
import de.goldenunicorn.kronenkrieg.engine.Rules
import de.goldenunicorn.kronenkrieg.engine.UnitKind
import de.goldenunicorn.kronenkrieg.engine.dist
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class UiButton(
    val rect: RectF,
    val label: String,
    val sub: String = "",
    val enabled: Boolean = true,
    val active: Boolean = false,
    val action: () -> Unit
)

enum class Screen { MENU, HELP, GAME, END }

class GameView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val renderer = WorldRenderer(d)
    private val session = Session()
    private var game: Game? = null
    private var screen = Screen.MENU
    private var helpReturn = Screen.MENU

    // Menü-Auswahl
    private var pickFaction = FactionId.MENSCHEN
    private var pickOpponents = 1
    private var pickDifficulty = 1
    private var pickEnemy: FactionId? = null

    private val buttons = ArrayList<UiButton>()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val minimapRect = RectF()
    private val panelRect = RectF()
    private val topRect = RectF()

    private var lastNanos = 0L
    private var acc = 0f

    init {
        isFocusable = true
        keepScreenOn = true
    }

    // ------------------------------------------------------------------ Schleife

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0f else min(0.1f, (now - lastNanos) / 1e9f)
        lastNanos = now
        buttons.clear()
        when (screen) {
            Screen.MENU -> drawMenu(canvas)
            Screen.HELP -> drawHelp(canvas)
            Screen.GAME -> {
                val g = game
                if (g != null) {
                    if (!session.paused) {
                        acc += dt * session.speed
                        var steps = 0
                        while (acc >= Rules.TICK && steps < 8) {
                            g.tick()
                            acc -= Rules.TICK
                            steps++
                        }
                        if (steps == 8) acc = 0f
                    }
                    session.markerTime = max(0f, session.markerTime - dt * 1.5f)
                    session.toastTime = max(0f, session.toastTime - dt)
                    drawGame(canvas, g, dt)
                    if (g.over && !session.paused) screen = Screen.END
                }
            }
            Screen.END -> drawEnd(canvas)
        }
        postInvalidateOnAnimation()
    }

    fun onAppPause() {
        if (screen == Screen.GAME) session.paused = true
        lastNanos = 0L
    }

    /** Zurück-Taste: liefert false, wenn die App geschlossen werden darf. */
    fun onBack(): Boolean {
        return when (screen) {
            Screen.GAME -> {
                when {
                    session.placing != null -> session.placing = null
                    session.buildMenu -> session.buildMenu = false
                    session.units.isNotEmpty() || session.other != 0 -> session.clear()
                    else -> session.paused = !session.paused
                }
                true
            }
            Screen.HELP -> { screen = helpReturn; true }
            Screen.END -> { screen = Screen.MENU; true }
            Screen.MENU -> false
        }
    }

    private fun startGame() {
        val others = FactionId.values().filter { it != pickFaction }.shuffled()
        val ais = (0 until pickOpponents).map { i -> if (i == 0 && pickEnemy != null) pickEnemy!! else others[i % others.size] }
        val g = Game(GameConfig(pickFaction, ais, pickDifficulty))
        game = g
        session.clear()
        session.paused = false
        session.speed = 1
        renderer.ts = 40f * d
        val p = g.players[g.humanIndex]
        centerOn(p.startX + 0.5f, p.startY + 0.5f)
        screen = Screen.GAME
        lastNanos = 0L
        acc = 0f
        session.showToast("Tippe auf einen Arbeiter, dann auf Wald, Beeren oder Gold.")
    }

    private fun centerOn(wx: Float, wy: Float) {
        renderer.camX = wx - width / renderer.ts / 2f
        renderer.camY = wy - (height - 120 * d) / renderer.ts / 2f
        clampCam()
    }

    private fun clampCam() {
        val g = game ?: return
        val vw = width / renderer.ts
        val vh = height / renderer.ts
        renderer.camX = renderer.camX.coerceIn(-2f, max(-2f, g.map.w - vw + 2f))
        renderer.camY = renderer.camY.coerceIn(-3f, max(-3f, g.map.h - vh + 6f))
    }

    // ------------------------------------------------------------------ Zeichenhilfen

    private fun t(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, align: Paint.Align = Paint.Align.LEFT, bold: Boolean = false) {
        text.textSize = size * d
        text.color = color
        text.textAlign = align
        text.isFakeBoldText = bold
        c.drawText(s, x, y, text)
    }

    private fun wrap(s: String, width: Float, size: Float): List<String> {
        text.textSize = size * d
        val out = ArrayList<String>()
        for (para in s.split("\n")) {
            var line = ""
            for (word in para.split(" ")) {
                val cand = if (line.isEmpty()) word else "$line $word"
                if (text.measureText(cand) > width && line.isNotEmpty()) {
                    out.add(line)
                    line = word
                } else line = cand
            }
            out.add(line)
        }
        return out
    }

    private fun panel(c: Canvas, r: RectF, color: Int = 0xE61C1A17.toInt(), border: Int = 0xFF6B5A3A.toInt()) {
        fill.color = color
        c.drawRoundRect(r, 8 * d, 8 * d, fill)
        stroke.color = border
        stroke.strokeWidth = 1.5f * d
        c.drawRoundRect(r, 8 * d, 8 * d, stroke)
    }

    private fun button(c: Canvas, b: UiButton, size: Float = 13f) {
        buttons.add(b)
        val bg = when {
            !b.enabled -> 0xE62A2724.toInt()
            b.active -> 0xFF8A6A2A.toInt()
            else -> 0xF03A3226.toInt()
        }
        fill.color = bg
        c.drawRoundRect(b.rect, 7 * d, 7 * d, fill)
        stroke.color = if (b.enabled) 0xFFC9A55A.toInt() else 0xFF55504A.toInt()
        stroke.strokeWidth = 1.4f * d
        c.drawRoundRect(b.rect, 7 * d, 7 * d, stroke)
        val col = if (b.enabled) 0xFFF4EEDC.toInt() else 0xFF8A857A.toInt()
        if (b.sub.isEmpty()) {
            t(c, b.label, b.rect.centerX(), b.rect.centerY() + size * d * 0.35f, size, col, Paint.Align.CENTER, true)
        } else {
            t(c, b.label, b.rect.centerX(), b.rect.centerY() - 2 * d, size, col, Paint.Align.CENTER, true)
            t(c, b.sub, b.rect.centerX(), b.rect.centerY() + size * d * 0.95f, size * 0.78f,
                if (b.enabled) 0xFFD8C58A.toInt() else 0xFF7A7568.toInt(), Paint.Align.CENTER)
        }
    }

    private fun fitLabel(s: String, maxW: Float, size: Float): String {
        text.textSize = size * d
        if (text.measureText(s) <= maxW) return s
        var k = s.length
        while (k > 3 && text.measureText(s.substring(0, k) + ".") > maxW) k--
        return s.substring(0, k) + "."
    }

    // ------------------------------------------------------------------ Menü

    private fun drawBackdrop(c: Canvas) {
        c.drawColor(0xFF15130F.toInt())
        // Dezente Hügel im Hintergrund
        fill.color = 0xFF1E2A1A.toInt()
        val w = width.toFloat()
        val h = height.toFloat()
        for (k in 0 until 6) {
            c.drawCircle(w * (k / 5f), h * 1.05f, h * (0.35f + (k % 3) * 0.08f), fill)
        }
    }

    private fun drawMenu(c: Canvas) {
        drawBackdrop(c)
        val w = width.toFloat()
        val h = height.toFloat()
        t(c, "KRONENKRIEG", w / 2, 44 * d, 30f, 0xFFE8C46A.toInt(), Paint.Align.CENTER, true)
        t(c, "Die Reiche von Eldmar", w / 2, 64 * d, 14f, 0xFFBFB49A.toInt(), Paint.Align.CENTER)

        val margin = 14 * d
        val gap = 10 * d
        val cardW = (w - margin * 2 - gap * 3) / 4
        val cardTop = 78 * d
        val cardH = min(h * 0.48f, 190 * d)
        for ((i, f) in Faction.all.withIndex()) {
            val r = RectF(margin + i * (cardW + gap), cardTop, margin + i * (cardW + gap) + cardW, cardTop + cardH)
            val sel = f.id == pickFaction
            panel(c, r, if (sel) 0xF0322A1C.toInt() else 0xE61C1A17.toInt(), if (sel) 0xFFE8C46A.toInt() else 0xFF4A4030.toInt())
            buttons.add(UiButton(r, "") { pickFaction = f.id; if (pickEnemy == f.id) pickEnemy = null })
            // Wappen
            fill.color = f.roofColor
            c.drawCircle(r.left + 22 * d, r.top + 24 * d, 13 * d, fill)
            fill.color = f.accentColor
            c.drawCircle(r.left + 22 * d, r.top + 24 * d, 6 * d, fill)
            t(c, f.name, r.left + 42 * d, r.top + 22 * d, 15f, 0xFFF4EEDC.toInt(), bold = true)
            t(c, fitLabel(f.realm, r.width() - 48 * d, 10.5f), r.left + 42 * d, r.top + 36 * d, 10.5f, 0xFFBFB49A.toInt())
            var y = r.top + 56 * d
            for (b in f.bonusText) {
                for (line in wrap("• $b", r.width() - 18 * d, 11f)) {
                    if (y > r.bottom - 6 * d) break
                    t(c, line, r.left + 9 * d, y, 11f, 0xFFDCD2B8.toInt())
                    y += 14 * d
                }
            }
            y += 4 * d
            for (line in wrap(f.lore, r.width() - 18 * d, 10f)) {
                if (y > r.bottom - 6 * d) break
                t(c, line, r.left + 9 * d, y, 10f, 0xFF9A9078.toInt())
                y += 12.5f * d
            }
        }

        // Optionen
        var y = cardTop + cardH + 14 * d
        val bh = 34 * d
        val labelW = 110 * d
        t(c, "Gegner:", margin, y + bh * 0.65f, 14f, 0xFFE8DCC0.toInt(), bold = true)
        var x = margin + labelW
        for (n in 1..3) {
            button(c, UiButton(RectF(x, y, x + 44 * d, y + bh), "$n", active = pickOpponents == n) { pickOpponents = n })
            x += 50 * d
        }
        x += 16 * d
        t(c, "Erster Gegner:", x, y + bh * 0.65f, 13f, 0xFFE8DCC0.toInt())
        x += 100 * d
        val opts = listOf<FactionId?>(null) + FactionId.values().filter { it != pickFaction }
        for (o in opts) {
            val name = if (o == null) "Zufall" else Faction.byId(o).name
            val bw = 74 * d
            if (x + bw > w - margin - 180 * d) break
            button(c, UiButton(RectF(x, y, x + bw, y + bh), name, active = pickEnemy == o) { pickEnemy = o }, 12f)
            x += bw + 6 * d
        }
        y += bh + 10 * d
        t(c, "Schwierigkeit:", margin, y + bh * 0.65f, 14f, 0xFFE8DCC0.toInt(), bold = true)
        x = margin + labelW
        for ((i, n) in listOf("Leicht", "Normal", "Schwer").withIndex()) {
            button(c, UiButton(RectF(x, y, x + 80 * d, y + bh), n, active = pickDifficulty == i) { pickDifficulty = i })
            x += 86 * d
        }
        val sw = 170 * d
        val startR = RectF(w - margin - sw, y - bh - 10 * d, w - margin, y + bh)
        button(c, UiButton(startR, "Schlacht beginnen") { startGame() }, 16f)
        val helpR = RectF(startR.left - 120 * d, y, startR.left - 10 * d, y + bh)
        button(c, UiButton(helpR, "Anleitung") { helpReturn = Screen.MENU; screen = Screen.HELP })
    }

    private val helpText = listOf(
        "Ziel" to "Zerstört alle Gebäude eurer Gegner (außer Feldern). Verliert ihr selbst alle Gebäude, ist die Schlacht verloren.",
        "Auswählen" to "Tippt auf eine eigene Einheit oder ein Gebäude. Doppeltipp wählt alle Einheiten dieser Art auf dem Bildschirm. Mit „Rahmen“ zieht ihr ein Auswahlrechteck. „Arbeiter“ springt zum nächsten untätigen Arbeiter, „Armee“ wählt alle Soldaten.",
        "Befehle" to "Mit ausgewählten Einheiten tippt ihr auf die Karte: Boden = hinlaufen (Soldaten greifen unterwegs an), Feind = angreifen, Baum/Beeren/Gold/Stein = sammeln, eigene Baustelle = weiterbauen, Feld = bewirtschaften.",
        "Kamera" to "Mit einem Finger ziehen verschiebt die Karte, mit zwei Fingern zoomen. Ein Tipp auf die Minikarte springt dorthin.",
        "Wirtschaft" to "Arbeiter bringen Rohstoffe zur Burg oder zu einem Lager. Baut Lager nahe an Wald und Minen. Häuser erhöhen die Bevölkerung um 5. Felder liefern unbegrenzt Nahrung.",
        "Zeitalter" to "In der Burg steigt ihr in die Zeit der Festen (Schützen, Reiter, Türme) und die Zeit der Kronen (Elite-Einheit, Belagerung, stärkere Armee) auf.",
        "Kampf" to "Speerträger schlagen Reiter, Reiter jagen Schützen, Schützen dezimieren Fußvolk. Belagerungsgerät zerstört Gebäude. Türme und Burgen schießen auf Feinde.",
        "Sammelpunkt" to "Gebäude auswählen, „Sammelpunkt“ tippen und dann eine Stelle auf der Karte – neue Einheiten laufen dorthin (auch zu Rohstoffen)."
    )

    private fun drawHelp(c: Canvas) {
        drawBackdrop(c)
        val w = width.toFloat()
        val h = height.toFloat()
        t(c, "Anleitung", w / 2, 36 * d, 22f, 0xFFE8C46A.toInt(), Paint.Align.CENTER, true)
        val colW = (w - 48 * d) / 2
        var col = 0
        var y = 60 * d
        val startY = y
        for ((title, body) in helpText) {
            val lines = wrap(body, colW - 10 * d, 12f)
            val need = 18 * d + lines.size * 15 * d + 8 * d
            if (y + need > h - 56 * d && col == 0) { col = 1; y = startY }
            val x = 16 * d + col * (colW + 16 * d)
            t(c, title, x, y + 13 * d, 14f, 0xFFF4EEDC.toInt(), bold = true)
            y += 18 * d
            for (l in lines) {
                t(c, l, x, y + 12 * d, 12f, 0xFFCFC5AA.toInt())
                y += 15 * d
            }
            y += 8 * d
        }
        button(c, UiButton(RectF(w / 2 - 70 * d, h - 46 * d, w / 2 + 70 * d, h - 10 * d), "Zurück") { screen = helpReturn })
    }

    private fun drawEnd(c: Canvas) {
        val g = game ?: run { screen = Screen.MENU; return }
        drawBackdrop(c)
        val w = width.toFloat()
        val h = height.toFloat()
        val won = g.winner == g.humanIndex
        t(c, if (won) "SIEG!" else "NIEDERLAGE", w / 2, 50 * d, 32f, if (won) 0xFFE8C46A.toInt() else 0xFFD05040.toInt(), Paint.Align.CENTER, true)
        t(c, if (won) "Eldmar gehört euch." else "Euer Reich ist gefallen.", w / 2, 74 * d, 14f, 0xFFBFB49A.toInt(), Paint.Align.CENTER)
        val mins = (g.time / 60).toInt()
        val secs = (g.time % 60).toInt()
        t(c, "Spielzeit: %d:%02d".format(mins, secs), w / 2, 94 * d, 12f, 0xFF9A9078.toInt(), Paint.Align.CENTER)
        val cols = listOf("Spieler", "Volk", "Ausgebildet", "Besiegt", "Verloren", "Gebäude zerst.", "Gesammelt")
        val colX = floatArrayOf(0.06f, 0.24f, 0.40f, 0.53f, 0.64f, 0.75f, 0.90f)
        var y = 124 * d
        for ((i, s) in cols.withIndex()) t(c, s, w * colX[i], y, 12f, 0xFFE8DCC0.toInt(), if (i < 2) Paint.Align.LEFT else Paint.Align.CENTER, true)
        y += 22 * d
        for (p in g.players) {
            fill.color = p.color
            c.drawCircle(w * colX[0] - 10 * d, y - 4 * d, 5 * d, fill)
            val st = p.stats
            val vals = listOf(p.name, p.faction.name, "${st.unitsTrained}", "${st.kills}", "${st.unitsLost}", "${st.buildingsRazed}",
                "${st.gathered.sum().toInt()}")
            for ((i, s) in vals.withIndex()) t(c, s, w * colX[i], y, 13f, 0xFFF4EEDC.toInt(), if (i < 2) Paint.Align.LEFT else Paint.Align.CENTER)
            y += 22 * d
        }
        button(c, UiButton(RectF(w / 2 - 190 * d, h - 56 * d, w / 2 - 10 * d, h - 16 * d), "Hauptmenü") { screen = Screen.MENU; game = null })
        button(c, UiButton(RectF(w / 2 + 10 * d, h - 56 * d, w / 2 + 190 * d, h - 16 * d), "Neue Schlacht") { startGame() })
    }

    // ------------------------------------------------------------------ Spiel-Oberfläche

    private fun drawGame(c: Canvas, g: Game, dt: Float) {
        renderer.sessionSelectedId = session.other
        renderer.draw(c, g, session, width, height)
        val w = width.toFloat()
        val h = height.toFloat()

        // Bauvorschau
        session.placing?.let { kind ->
            val ok = g.canPlace(g.humanIndex, kind, session.placeTx, session.placeTy)
            renderer.drawGhost(c, g, kind, session.placeTx, session.placeTy, ok)
        }
        // Auswahlrahmen
        if (dragBox) {
            fill.color = 0x3340FF40
            val l = min(downX, curX); val r = max(downX, curX)
            val tp = min(downY, curY); val b = max(downY, curY)
            c.drawRect(l, tp, r, b, fill)
            stroke.color = 0xFF60FF60.toInt()
            stroke.strokeWidth = 1.5f * d
            c.drawRect(l, tp, r, b, stroke)
        }

        drawTopBar(c, g, w)
        // Minikarte
        val ms = min(128 * d, h * 0.34f)
        minimapRect.set(8 * d, h - ms - 8 * d, 8 * d + ms, h - 8 * d)
        renderer.drawMinimap(c, g, minimapRect, dt, width, height)
        // Auswahlfeld
        panelRect.set(minimapRect.right + 8 * d, h - 118 * d, w - 8 * d, h - 8 * d)
        drawSelectionPanel(c, g)
        drawQuickButtons(c, g, w)
        drawMessages(c, g, w)

        if (session.paused) drawPause(c, g)
    }

    private fun drawTopBar(c: Canvas, g: Game, w: Float) {
        val p = g.players[g.humanIndex]
        topRect.set(0f, 0f, w, 32 * d)
        fill.color = 0xE6141210.toInt()
        c.drawRect(topRect, fill)
        stroke.color = 0xFF6B5A3A.toInt()
        stroke.strokeWidth = 1f * d
        c.drawLine(0f, topRect.bottom, w, topRect.bottom, stroke)
        var x = 10 * d
        val colors = intArrayOf(0xFFD8304A.toInt(), 0xFF9A6A3A.toInt(), 0xFFF2CC3A.toInt(), 0xFFBDBDBD.toInt())
        for (r in Res.values()) {
            fill.color = colors[r.ordinal]
            c.drawCircle(x + 7 * d, 16 * d, 7 * d, fill)
            t(c, "${p.res[r.ordinal].toInt()}", x + 18 * d, 21 * d, 14f, 0xFFF4EEDC.toInt(), bold = true)
            x += 74 * d
        }
        // Bevölkerung
        fill.color = 0xFF6FB0FF.toInt()
        c.drawCircle(x + 7 * d, 12 * d, 4 * d, fill)
        c.drawRect(x + 3 * d, 17 * d, x + 11 * d, 24 * d, fill)
        val popCol = if (p.pop >= p.popCap) 0xFFFF7060.toInt() else 0xFFF4EEDC.toInt()
        t(c, "${p.pop}/${p.popCap}", x + 18 * d, 21 * d, 14f, popCol, bold = true)
        x += 80 * d
        t(c, Rules.ages[p.age - 1].name, x, 21 * d, 13f, 0xFFE8C46A.toInt())
        // rechts: Zeit, Tempo, Menü
        val mb = RectF(w - 46 * d, 3 * d, w - 6 * d, 29 * d)
        button(c, UiButton(mb, "II") { session.paused = true })
        val sb = RectF(mb.left - 48 * d, 3 * d, mb.left - 6 * d, 29 * d)
        button(c, UiButton(sb, "${session.speed}x", active = session.speed > 1) { session.speed = if (session.speed == 1) 2 else 1 }, 12f)
        val mins = (g.time / 60).toInt()
        val secs = (g.time % 60).toInt()
        t(c, "%d:%02d".format(mins, secs), sb.left - 10 * d, 21 * d, 13f, 0xFFBFB49A.toInt(), Paint.Align.RIGHT)
    }

    private fun drawQuickButtons(c: Canvas, g: Game, w: Float) {
        val bw = 76 * d
        val bh = 34 * d
        val x = w - bw - 8 * d
        var y = panelRect.top - bh - 8 * d
        val idle = g.units.count { it.owner == g.humanIndex && it.kind == UnitKind.WORKER && it.order == Order.IDLE }
        button(c, UiButton(RectF(x, y, x + bw, y + bh), "Arbeiter", if (idle > 0) "$idle untätig" else "alle beschäftigt", idle > 0) {
            selectNextIdleWorker(g)
        }, 12f)
        y -= bh + 6 * d
        val army = g.units.count { it.owner == g.humanIndex && it.kind.isMilitary }
        button(c, UiButton(RectF(x, y, x + bw, y + bh), "Armee", "$army Soldaten", army > 0) {
            session.selectUnits(g.units.filter { it.owner == g.humanIndex && it.kind.isMilitary }.map { it.id })
        }, 12f)
        y -= bh + 6 * d
        button(c, UiButton(RectF(x, y, x + bw, y + bh), "Rahmen", active = session.boxMode) { session.boxMode = !session.boxMode }, 12f)
        y -= bh + 6 * d
        button(c, UiButton(RectF(x, y, x + bw, y + bh), "Burg") {
            val th = g.townhallOf(g.humanIndex)
            if (th != null) { centerOn(th.x, th.y); session.selectOther(th.id) }
        }, 12f)
    }

    private var idleCursor = 0

    private fun selectNextIdleWorker(g: Game) {
        val idle = g.units.filter { it.owner == g.humanIndex && it.kind == UnitKind.WORKER && it.order == Order.IDLE }
        if (idle.isEmpty()) return
        idleCursor = (idleCursor + 1) % idle.size
        val u = idle[idleCursor]
        session.selectUnits(listOf(u.id))
        centerOn(u.x, u.y)
    }

    private fun drawMessages(c: Canvas, g: Game, w: Float) {
        var y = 52 * d
        for (m in g.messages) {
            val age = g.time - m.time
            if (age > 8f) continue
            val a = if (age > 6f) (8f - age) / 2f else 1f
            text.textSize = 13f * d
            val tw = text.measureText(m.text) + 20 * d
            fill.color = WorldRenderer.alpha(0xFF141210.toInt(), 0.8f * a)
            c.drawRoundRect(RectF(w / 2 - tw / 2, y - 16 * d, w / 2 + tw / 2, y + 6 * d), 6 * d, 6 * d, fill)
            t(c, m.text, w / 2, y, 13f, WorldRenderer.alpha(0xFFF4E3A0.toInt(), a), Paint.Align.CENTER)
            y += 26 * d
        }
        if (session.toastTime > 0f) {
            val a = min(1f, session.toastTime)
            text.textSize = 13f * d
            val tw = text.measureText(session.toast) + 24 * d
            val ty = panelRect.top - 54 * d
            fill.color = WorldRenderer.alpha(0xFF2A1E12.toInt(), 0.9f * a)
            val r = RectF(w / 2 - tw / 2, ty - 18 * d, w / 2 + tw / 2, ty + 8 * d)
            c.drawRoundRect(r, 6 * d, 6 * d, fill)
            t(c, session.toast, w / 2, ty, 13f, WorldRenderer.alpha(0xFFFFFFFF.toInt(), a), Paint.Align.CENTER)
        }
    }

    private fun drawPause(c: Canvas, g: Game) {
        fill.color = 0xB0000000.toInt()
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        val w = width.toFloat()
        val h = height.toFloat()
        t(c, "Pause", w / 2, h * 0.25f, 26f, 0xFFE8C46A.toInt(), Paint.Align.CENTER, true)
        val bw = 220 * d
        val bh = 40 * d
        var y = h * 0.32f
        val x = w / 2 - bw / 2
        button(c, UiButton(RectF(x, y, x + bw, y + bh), "Weiter") { session.paused = false; lastNanos = 0L }, 15f)
        y += bh + 10 * d
        button(c, UiButton(RectF(x, y, x + bw, y + bh), "Anleitung") { helpReturn = Screen.GAME; screen = Screen.HELP }, 15f)
        y += bh + 10 * d
        button(c, UiButton(RectF(x, y, x + bw, y + bh), "Aufgeben") {
            game = null
            screen = Screen.MENU
        }, 15f)
    }

    // ------------------------------------------------------------------ Auswahl-Panel

    private fun drawSelectionPanel(c: Canvas, g: Game) {
        panel(c, panelRect)
        val sel = session.selectedUnits(g)
        val other = session.selectedOther(g)
        val infoW = min(panelRect.width() * 0.36f, 220 * d)
        val ix = panelRect.left + 10 * d
        var iy = panelRect.top + 20 * d
        val ax = panelRect.left + infoW + 10 * d
        val actions = ArrayList<UiButton>()
        val p = g.players[g.humanIndex]
        val bw = 78 * d
        val bh = 46 * d
        val cols = max(1, ((panelRect.right - ax - 6 * d) / (bw + 6 * d)).toInt())
        fun slot(i: Int): RectF {
            val cx = ax + (i % cols) * (bw + 6 * d)
            val cy = panelRect.top + 8 * d + (i / cols) * (bh + 6 * d)
            return RectF(cx, cy, cx + bw, cy + bh)
        }

        if (session.placing != null) {
            val st = p.faction.building(session.placing!!)
            t(c, "${st.name} platzieren", ix, iy, 14f, 0xFFF4EEDC.toInt(), bold = true)
            iy += 18 * d
            for (l in wrap("Tippe auf die Karte, wo gebaut werden soll. Grün = möglich.", infoW - 14 * d, 11.5f)) {
                t(c, l, ix, iy, 11.5f, 0xFFCFC5AA.toInt()); iy += 14 * d
            }
            actions.add(UiButton(slot(0), "Abbrechen") { session.placing = null })
        } else if (sel.isNotEmpty()) {
            if (sel.size == 1) {
                val u = sel[0]
                t(c, u.stats.name, ix, iy, 14f, 0xFFF4EEDC.toInt(), bold = true)
                iy += 17 * d
                t(c, "LP ${u.hp.toInt()}/${u.maxHp.toInt()}   Angriff ${u.stats.attack}   Rüstung ${u.stats.armor}", ix, iy, 11.5f, 0xFFCFC5AA.toInt())
                iy += 15 * d
                val state = when (u.order) {
                    Order.IDLE -> "Untätig"
                    Order.MOVE -> "Unterwegs"
                    Order.ATTACK -> "Kämpft"
                    Order.GATHER -> "Sammelt ${u.gatherRes?.label ?: ""}"
                    Order.RETURN -> "Bringt ${u.carryRes?.label ?: ""} zurück"
                    Order.BUILD -> "Baut"
                }
                val carry = if (u.carry > 0f) " (trägt ${u.carry.toInt()})" else ""
                t(c, state + carry, ix, iy, 11.5f, 0xFFD8C58A.toInt())
                iy += 15 * d
                for (l in wrap(u.stats.description, infoW - 14 * d, 10.5f).take(3)) {
                    t(c, l, ix, iy, 10.5f, 0xFF9A9078.toInt()); iy += 13 * d
                }
            } else {
                t(c, "${sel.size} Einheiten", ix, iy, 14f, 0xFFF4EEDC.toInt(), bold = true)
                iy += 17 * d
                val groups = sel.groupBy { it.stats.name }.entries.sortedByDescending { it.value.size }
                for (e in groups.take(4)) {
                    t(c, "${e.value.size}× ${e.key}", ix, iy, 11.5f, 0xFFCFC5AA.toInt()); iy += 15 * d
                }
            }
            val workers = sel.filter { it.kind == UnitKind.WORKER }
            if (workers.isNotEmpty()) {
                if (session.buildMenu) {
                    var i = 0
                    for (k in BuildingKind.values()) {
                        val st = p.faction.building(k)
                        val ageOk = p.age >= st.age
                        val can = ageOk && p.canAfford(st.cost)
                        val sub = if (ageOk) st.cost.label() else "Zeitalter ${st.age}"
                        actions.add(UiButton(slot(i++), fitLabel(st.name, bw - 6 * d, 11.5f), sub, can) {
                            session.placing = k
                            session.buildMenu = false
                            val cx = renderer.wx(width / 2f)
                            val cy = renderer.wy(height / 2f - 40 * d)
                            session.placeTx = (cx - st.size / 2f).toInt()
                            session.placeTy = (cy - st.size / 2f).toInt()
                            session.showToast("Tippe auf die Karte, um ${st.name} zu platzieren.")
                        })
                    }
                    actions.add(UiButton(slot(i), "Zurück") { session.buildMenu = false })
                } else {
                    actions.add(UiButton(slot(0), "Bauen") { session.buildMenu = true })
                    actions.add(UiButton(slot(1), "Stopp") { g.stop(sel) })
                    val carrying = workers.any { it.carry > 0f }
                    actions.add(UiButton(slot(2), "Abliefern", "", carrying) { for (wk in workers) g.issueReturn(wk) })
                    actions.add(UiButton(slot(3), "Abwählen") { session.clear() })
                }
            } else {
                actions.add(UiButton(slot(0), "Stopp") { g.stop(sel) })
                actions.add(UiButton(slot(1), "Zur Burg") {
                    val th = g.townhallOf(g.humanIndex)
                    if (th != null) g.issueMove(sel, th.x, th.y + th.size, false)
                })
                actions.add(UiButton(slot(2), "Abwählen") { session.clear() })
                if (sel.size == 1) {
                    actions.add(UiButton(slot(3), if (session.confirmDelete) "Sicher?" else "Entlassen", active = session.confirmDelete) {
                        if (session.confirmDelete) { g.deleteOwn(sel[0]); session.clear() } else session.confirmDelete = true
                    })
                }
            }
        } else if (other != null) {
            when (other) {
                is Building -> drawBuildingPanel(c, g, other, ix, iy, infoW, actions, ::slot)
                is GUnit -> {
                    val op = g.players[other.owner]
                    t(c, other.stats.name, ix, iy, 14f, 0xFFF4EEDC.toInt(), bold = true)
                    iy += 17 * d
                    t(c, "${op.name} (${op.faction.name})", ix, iy, 11.5f, op.color)
                    iy += 15 * d
                    t(c, "LP ${other.hp.toInt()}/${other.maxHp.toInt()}   Angriff ${other.stats.attack}   Rüstung ${other.stats.armor}", ix, iy, 11.5f, 0xFFCFC5AA.toInt())
                }
                is ResourceNode -> {
                    t(c, when (other.res) { Res.FOOD -> "Beerenstrauch"; Res.GOLD -> "Goldader"; Res.STONE -> "Steinbruch"; else -> "Rohstoff" },
                        ix, iy, 14f, 0xFFF4EEDC.toInt(), bold = true)
                    iy += 17 * d
                    t(c, "${other.amount.toInt()} ${other.res.label} übrig", ix, iy, 11.5f, 0xFFCFC5AA.toInt())
                }
                else -> {}
            }
        } else {
            t(c, p.faction.realm, ix, iy, 14f, 0xFFF4EEDC.toInt(), bold = true)
            iy += 17 * d
            for (l in wrap("Nichts ausgewählt. Tippe auf eine Einheit oder ein Gebäude. Ziehen verschiebt die Karte, zwei Finger zoomen.", panelRect.width() - 20 * d, 11.5f)) {
                t(c, l, ix, iy, 11.5f, 0xFFCFC5AA.toInt()); iy += 14 * d
            }
        }
        for (b in actions) button(c, b, 12f)
    }

    private fun drawBuildingPanel(
        c: Canvas, g: Game, b: Building, ix: Float, iy0: Float, infoW: Float,
        actions: ArrayList<UiButton>, slot: (Int) -> RectF
    ) {
        var iy = iy0
        val p = g.players[g.humanIndex]
        val own = b.owner == g.humanIndex
        val op = g.players[b.owner]
        t(c, b.stats.name, ix, iy, 14f, 0xFFF4EEDC.toInt(), bold = true)
        iy += 17 * d
        if (!own) {
            t(c, "${op.name} (${op.faction.name})", ix, iy, 11.5f, op.color); iy += 15 * d
        }
        t(c, "LP ${b.hp.toInt()}/${b.maxHp.toInt()}" + if (!b.complete) "   Bau ${(b.progress * 100).toInt()} %" else "", ix, iy, 11.5f, 0xFFCFC5AA.toInt())
        iy += 15 * d
        if (own && b.kind == BuildingKind.FARM && b.complete) {
            t(c, if (b.farmer != 0 && g.unitById(b.farmer) != null) "Wird bewirtschaftet" else "Kein Bauer – Arbeiter herschicken", ix, iy, 11.5f, 0xFFD8C58A.toInt())
            iy += 15 * d
        }
        if (!own || !b.complete) {
            for (l in wrap(b.stats.description, infoW - 14 * d, 10.5f).take(3)) {
                t(c, l, ix, iy, 10.5f, 0xFF9A9078.toInt()); iy += 13 * d
            }
            if (own) actions.add(UiButton(slot(0), if (session.confirmDelete) "Sicher?" else "Abreißen", active = session.confirmDelete) {
                if (session.confirmDelete) { g.deleteOwn(b); session.clear() } else session.confirmDelete = true
            })
            return
        }
        // Warteschlange
        if (b.queue.isNotEmpty()) {
            var qx = ix
            for ((qi, q) in b.queue.withIndex()) {
                val r = RectF(qx, iy - 2 * d, qx + 30 * d, iy + 26 * d)
                val name = if (q.isAgeUp) "Z${p.age + 1}" else shortName(p.faction.unitName(q.kind!!))
                fill.color = 0xFF2A2420.toInt()
                c.drawRoundRect(r, 4 * d, 4 * d, fill)
                if (qi == 0) {
                    fill.color = 0xFF3A6A9A.toInt()
                    c.drawRect(r.left, r.bottom - 4 * d, r.left + r.width() * (q.time / q.totalTime), r.bottom, fill)
                }
                t(c, name, r.centerX(), r.centerY() + 4 * d, 10f, 0xFFF4EEDC.toInt(), Paint.Align.CENTER, true)
                buttons.add(UiButton(r, "") { g.cancelQueue(b, qi) })
                qx += 34 * d
            }
            iy += 32 * d
            if (b.queue[0].kind != null && p.pop >= p.popCap) {
                t(c, "Bevölkerungsgrenze! Häuser bauen.", ix, iy, 11f, 0xFFFF7060.toInt()); iy += 14 * d
            } else {
                t(c, "Tippen zum Abbrechen", ix, iy, 10f, 0xFF7A7568.toInt()); iy += 14 * d
            }
        } else {
            for (l in wrap(b.stats.description, infoW - 14 * d, 10.5f).take(3)) {
                t(c, l, ix, iy, 10.5f, 0xFF9A9078.toInt()); iy += 13 * d
            }
        }
        var i = 0
        for (k in b.stats.trains) {
            val st = p.faction.unit(k)
            val ageOk = p.age >= st.age
            val sub = if (ageOk) st.cost.label() else "Zeitalter ${st.age}"
            actions.add(UiButton(slot(i++), fitLabel(st.name, 72 * d, 11.5f), sub, ageOk && p.canAfford(st.cost)) {
                val err = g.train(b, k)
                if (err != null) session.showToast(err)
            })
        }
        if (b.kind == BuildingKind.TOWNHALL && p.age < 3) {
            val info = Rules.ages[p.age]
            val researching = p.ageResearching(g)
            actions.add(UiButton(slot(i++), "Zeitalter ${p.age + 1}", if (researching) "läuft …" else info.cost.label(),
                !researching && p.canAfford(info.cost)) {
                val err = g.ageUp(b)
                if (err != null) session.showToast(err) else session.showToast("${info.name}: ${info.description}")
            })
        }
        if (b.stats.trains.isNotEmpty()) {
            actions.add(UiButton(slot(i++), "Sammel-", "punkt", active = session.rallyMode) { session.rallyMode = !session.rallyMode })
        }
        actions.add(UiButton(slot(i), if (session.confirmDelete) "Sicher?" else "Abreißen", active = session.confirmDelete) {
            if (session.confirmDelete) { g.deleteOwn(b); session.clear() } else session.confirmDelete = true
        })
    }

    private fun shortName(s: String): String = if (s.length <= 4) s else s.substring(0, 3) + "."

    // ------------------------------------------------------------------ Eingabe

    private var downX = 0f
    private var downY = 0f
    private var curX = 0f
    private var curY = 0f
    private var moved = false
    private var pinching = false
    private var pinchDist = 0f
    private var pinchMidX = 0f
    private var pinchMidY = 0f
    private var uiTouch = false
    private var miniTouch = false
    private var dragBox = false
    private var lastTapTime = 0L
    private var lastTapId = 0

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; curX = e.x; curY = e.y
                moved = false
                pinching = false
                dragBox = false
                uiTouch = screen != Screen.GAME || hitUi(e.x, e.y)
                miniTouch = screen == Screen.GAME && !session.paused && minimapRect.contains(e.x, e.y)
                if (miniTouch) jumpMinimap(e.x, e.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount == 2 && screen == Screen.GAME && !uiTouch) {
                    pinching = true
                    dragBox = false
                    pinchDist = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                    pinchMidX = (e.getX(0) + e.getX(1)) / 2
                    pinchMidY = (e.getY(0) + e.getY(1)) / 2
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (screen != Screen.GAME || session.paused) return true
                if (miniTouch) { jumpMinimap(e.x, e.y); return true }
                if (uiTouch) return true
                if (pinching && e.pointerCount >= 2) {
                    val nd = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                    val mx = (e.getX(0) + e.getX(1)) / 2
                    val my = (e.getY(0) + e.getY(1)) / 2
                    if (pinchDist > 10f) {
                        val wxBefore = renderer.wx(pinchMidX)
                        val wyBefore = renderer.wy(pinchMidY)
                        renderer.ts = (renderer.ts * nd / pinchDist).coerceIn(16f * d, 72f * d)
                        renderer.camX = wxBefore - mx / renderer.ts
                        renderer.camY = wyBefore - my / renderer.ts
                        clampCam()
                    }
                    pinchDist = nd
                    pinchMidX = mx
                    pinchMidY = my
                    moved = true
                    return true
                }
                val dx = e.x - curX
                val dy = e.y - curY
                if (!moved && hypot(e.x - downX, e.y - downY) > 10 * d) {
                    moved = true
                    dragBox = session.boxMode && session.placing == null
                }
                if (moved) {
                    if (dragBox) {
                        // Rahmen aufziehen
                    } else if (session.placing != null) {
                        updatePlacement(e.x, e.y)
                    } else {
                        renderer.camX -= dx / renderer.ts
                        renderer.camY -= dy / renderer.ts
                        clampCam()
                    }
                }
                curX = e.x; curY = e.y
            }
            MotionEvent.ACTION_UP -> {
                if (uiTouch) {
                    if (!moved) clickUi(e.x, e.y)
                } else if (screen == Screen.GAME && !session.paused && !miniTouch) {
                    if (dragBox) {
                        finishBox()
                    } else if (!moved && !pinching) {
                        tapWorld(e.x, e.y)
                    }
                }
                dragBox = false
                pinching = false
                miniTouch = false
            }
            MotionEvent.ACTION_CANCEL -> {
                dragBox = false; pinching = false; miniTouch = false
            }
        }
        return true
    }

    private fun hitUi(x: Float, y: Float): Boolean {
        if (buttons.any { it.rect.contains(x, y) }) return true
        if (screen == Screen.GAME) {
            if (session.paused) return true
            if (panelRect.contains(x, y) || topRect.contains(x, y)) return true
        }
        return false
    }

    private fun clickUi(x: Float, y: Float) {
        // Letzte (oberste) Schaltfläche zuerst
        for (i in buttons.indices.reversed()) {
            val b = buttons[i]
            if (b.rect.contains(x, y)) {
                if (b.enabled) {
                    if (!b.label.startsWith("Sicher") && !b.label.startsWith("Abreißen") && !b.label.startsWith("Entlassen")) session.confirmDelete = false
                    b.action()
                } else if (b.sub.isNotEmpty()) {
                    session.showToast(if (b.sub.startsWith("Zeitalter")) "Benötigt ${b.sub}." else "Nicht genug Rohstoffe (${b.sub}).")
                }
                return
            }
        }
    }

    private fun jumpMinimap(x: Float, y: Float) {
        val g = game ?: return
        val wx = (x - minimapRect.left) / minimapRect.width() * g.map.w
        val wy = (y - minimapRect.top) / minimapRect.height() * g.map.h
        centerOn(wx, wy)
    }

    private fun updatePlacement(x: Float, y: Float) {
        val g = game ?: return
        val kind = session.placing ?: return
        val st = g.players[g.humanIndex].faction.building(kind)
        // Vorschau leicht über dem Finger, damit sie sichtbar bleibt
        val wx = renderer.wx(x)
        val wy = renderer.wy(y - 50 * d)
        session.placeTx = (wx - st.size / 2f + 0.5f).toInt()
        session.placeTy = (wy - st.size / 2f + 0.5f).toInt()
    }

    private fun finishBox() {
        val g = game ?: return
        val l = renderer.wx(min(downX, curX)); val r = renderer.wx(max(downX, curX))
        val tp = renderer.wy(min(downY, curY)); val b = renderer.wy(max(downY, curY))
        val inBox = g.units.filter { it.owner == g.humanIndex && it.x in l..r && it.y in tp..b }
        if (inBox.isEmpty()) return
        // Soldaten bevorzugen, wenn gemischt
        val mil = inBox.filter { it.kind.isMilitary }
        session.selectUnits((if (mil.isNotEmpty() && mil.size != inBox.size) mil else inBox).map { it.id })
        session.boxMode = false
    }

    private fun tapWorld(x: Float, y: Float) {
        val g = game ?: return
        val wx = renderer.wx(x)
        val wy = renderer.wy(y)
        val me = g.humanIndex

        // Bauplatzierung
        session.placing?.let { kind ->
            val st = g.players[me].faction.building(kind)
            val tx = (wx - st.size / 2f + 0.5f).toInt()
            val ty = (wy - st.size / 2f + 0.5f).toInt()
            // Erster Tipp verschiebt die Vorschau, zweiter Tipp an gleicher Stelle baut
            if (tx != session.placeTx || ty != session.placeTy) {
                session.placeTx = tx
                session.placeTy = ty
                if (!g.canPlace(me, kind, tx, ty)) {
                    session.showToast("Hier nicht möglich – andere Stelle wählen.")
                } else session.showToast("Nochmal tippen zum Bauen.")
                return
            }
            val builders = session.selectedUnits(g).filter { it.kind == UnitKind.WORKER }
            val err = g.startConstruction(me, kind, tx, ty, builders)
            if (err != null) session.showToast(err)
            else {
                session.placing = null
                marker(tx + st.size / 2f, ty + st.size / 2f, false)
            }
            return
        }

        // Sammelpunkt setzen
        if (session.rallyMode) {
            val b = session.selectedBuilding(g)
            if (b != null && b.owner == me) {
                b.rallyX = wx
                b.rallyY = wy
                marker(wx, wy, false)
                session.showToast("Sammelpunkt gesetzt.")
            }
            session.rallyMode = false
            return
        }

        val tol = max(0.55f, 18 * d / renderer.ts)
        // Eigene Einheit angetippt?
        var hit: GUnit? = null
        var bd = tol
        for (u in g.units) {
            if (u.owner != me) continue
            val dd = dist(u.x, u.y, wx, wy)
            if (dd < bd) { bd = dd; hit = u }
        }
        val selected = session.selectedUnits(g)
        if (hit != null) {
            val now = System.currentTimeMillis()
            if (now - lastTapTime < 350 && lastTapId == hit.id) {
                // Doppeltipp: alle dieser Art auf dem Bildschirm
                val vx0 = renderer.wx(0f); val vx1 = renderer.wx(width.toFloat())
                val vy0 = renderer.wy(0f); val vy1 = renderer.wy(height.toFloat())
                val k = hit.kind
                session.selectUnits(g.units.filter { it.owner == me && it.kind == k && it.x in vx0..vx1 && it.y in vy0..vy1 }.map { it.id })
            } else {
                session.selectUnits(listOf(hit.id))
            }
            lastTapTime = now
            lastTapId = hit.id
            return
        }
        val tx = wx.toInt()
        val ty = wy.toInt()
        val inMap = g.map.inside(tx, ty)
        val explored = inMap && g.fogs[me].explored[g.map.idx(tx, ty)]
        val bAt = if (explored) g.buildingById(g.map.buildingAt[g.map.idx(tx, ty)]) else null

        if (selected.isNotEmpty()) {
            // Eigenes Gebäude antippen: Arbeiter nutzen es, sonst Gebäude wählen
            if (bAt != null && bAt.owner == me) {
                val workers = selected.filter { it.kind == UnitKind.WORKER }
                val useful = workers.isNotEmpty() && (!bAt.complete || bAt.hp < bAt.maxHp || bAt.kind == BuildingKind.FARM ||
                        (bAt.stats.dropoff && workers.any { it.carry > 0f }))
                if (!useful) {
                    session.selectOther(bAt.id)
                    return
                }
            }
            val enemyHit = g.units.any { it.owner != me && dist(it.x, it.y, wx, wy) < 0.9f && g.isVisibleTo(me, it) } ||
                    (bAt != null && bAt.owner != me)
            g.smartCommand(me, selected, wx, wy)
            marker(wx, wy, enemyHit)
            return
        }

        // Nichts ausgewählt: Info-Auswahl
        if (bAt != null) { session.selectOther(bAt.id); return }
        var ou: GUnit? = null
        bd = tol
        for (u in g.units) {
            if (u.owner == me || !g.isVisibleTo(me, u)) continue
            val dd = dist(u.x, u.y, wx, wy)
            if (dd < bd) { bd = dd; ou = u }
        }
        if (ou != null) { session.selectOther(ou.id); return }
        if (explored) {
            val nid = g.map.nodeAt[g.map.idx(tx, ty)]
            if (nid != 0) { session.selectOther(nid); return }
        }
        session.clear()
    }

    private fun marker(x: Float, y: Float, attack: Boolean) {
        session.markerX = x
        session.markerY = y
        session.markerTime = 1f
        session.markerAttack = attack
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampCam()
    }

    @Suppress("unused")
    private fun nearlyEqual(a: Float, b: Float) = abs(a - b) < 0.001f
}
