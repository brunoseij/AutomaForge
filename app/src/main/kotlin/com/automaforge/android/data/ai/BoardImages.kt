package com.automaforge.android.data.ai

import android.graphics.*
import com.automaforge.android.core.ApiException
import java.io.ByteArrayOutputStream
import org.json.*

object BoardImages {
    @JvmStatic
    fun validRegion(region: JSONArray?): Boolean =
        com.automaforge.android.core.RegionGeometry.validRegion(region)

    @JvmStatic
    @Throws(Exception::class)
    fun crops(photo: ByteArray, areas: JSONArray): MutableList<AiClient.RuleFile> {
        val files = mutableListOf<AiClient.RuleFile>()
        val bitmap =
            BitmapFactory.decodeByteArray(photo, 0, photo.size)
                ?: throw ApiException(
                    422,
                    "Não foi possível recortar a foto. Envie uma imagem legível.",
                )
        try {
            for (i in 0 until areas.length()) {
                if (files.size >= 5) break
                val area = areas.getJSONObject(i)
                val region = area.optJSONArray("region") ?: continue
                if (!validRegion(region)) continue
                val x = (region.getDouble(0) * bitmap.width).toInt()
                val y = (region.getDouble(1) * bitmap.height).toInt()
                val w =
                    (region.getDouble(2) * bitmap.width)
                        .toInt()
                        .coerceAtLeast(1)
                        .coerceAtMost(bitmap.width - x)
                val h =
                    (region.getDouble(3) * bitmap.height)
                        .toInt()
                        .coerceAtLeast(1)
                        .coerceAtMost(bitmap.height - y)
                val crop = Bitmap.createBitmap(bitmap, x, y, w, h)
                try {
                    val out = ByteArrayOutputStream()
                    if (!crop.compress(Bitmap.CompressFormat.PNG, 100, out))
                        throw ApiException(422, "Falha ao preparar recorte.")
                    files.add(
                        AiClient.RuleFile(
                            out.toByteArray(),
                            "image/png",
                            "crop:" + area.optString("id"),
                        )
                    )
                } finally {
                    if (crop !== bitmap) crop.recycle()
                }
            }
        } finally {
            bitmap.recycle()
        }
        return files
    }
}
