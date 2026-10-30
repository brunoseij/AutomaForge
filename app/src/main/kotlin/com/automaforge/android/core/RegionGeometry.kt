package com.automaforge.android.core

import kotlin.contracts.*
import org.json.JSONArray

object RegionGeometry {
    @OptIn(ExperimentalContracts::class)
    fun validRegion(region: JSONArray?): Boolean {
        contract { returns(true) implies (region != null) }
        if (region == null || region.length() != 4) return false
        val x = region.optDouble(0, -1.0)
        val y = region.optDouble(1, -1.0)
        val w = region.optDouble(2, -1.0)
        val h = region.optDouble(3, -1.0)
        return listOf(x, y, w, h).all { it.isFinite() } &&
            x >= 0 &&
            y >= 0 &&
            w >= .02 &&
            h >= .02 &&
            x + w <= 1.001 &&
            y + h <= 1.001
    }
}
