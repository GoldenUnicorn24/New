package de.goldenunicorn.kronenkrieg.ui

import de.goldenunicorn.kronenkrieg.engine.Building
import de.goldenunicorn.kronenkrieg.engine.BuildingKind
import de.goldenunicorn.kronenkrieg.engine.Entity
import de.goldenunicorn.kronenkrieg.engine.GUnit
import de.goldenunicorn.kronenkrieg.engine.Game

/** Zustand der Bedienung während einer Partie. */
class Session {
    /** Ausgewählte eigene Einheiten. */
    val units = LinkedHashSet<Int>()
    /** Einzelauswahl: Gebäude, Rohstoff oder fremde Einheit. */
    var other = 0

    var placing: BuildingKind? = null
    var placeTx = 0
    var placeTy = 0
    var rallyMode = false
    var buildMenu = false
    var boxMode = false
    var confirmDelete = false
    var speed = 1
    var paused = false

    var markerX = 0f
    var markerY = 0f
    var markerTime = 0f
    var markerAttack = false

    var toast = ""
    var toastTime = 0f

    fun isSelected(id: Int) = id == other || units.contains(id)

    fun clear() {
        units.clear()
        other = 0
        placing = null
        rallyMode = false
        buildMenu = false
        confirmDelete = false
    }

    fun selectUnits(ids: Collection<Int>) {
        clear()
        units.addAll(ids)
    }

    fun selectOther(id: Int) {
        clear()
        other = id
    }

    fun selectedUnits(g: Game): List<GUnit> {
        val out = ArrayList<GUnit>()
        val it = units.iterator()
        while (it.hasNext()) {
            val u = g.unitById(it.next())
            if (u == null || !u.alive) it.remove() else out.add(u)
        }
        return out
    }

    fun selectedBuilding(g: Game): Building? = g.buildingById(other)

    fun selectedOther(g: Game): Entity? {
        val e = g.entity(other)
        if (e == null) other = 0
        return e
    }

    fun showToast(msg: String) {
        toast = msg
        toastTime = 2.8f
    }
}
