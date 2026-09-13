package com.devtool.gpsmocker.utils

import android.content.Context
import com.devtool.gpsmocker.R
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 一個抓取用的城市種子點。
 */
data class CityDef(
    val name: String,
    val lat: Double,
    val lon: Double,
    val population: Long
)

/**
 * 讀取 res/raw/cities_50k.csv（無 header，格式：name,lat,lon,population）
 * 只保留人口 >= 50,000 的城市，作為 WikiFetcher 抓取地標時的搜尋中心點。
 *
 * CSV 產生方式：見專案根目錄 tools/generate_cities_csv.py
 * （資料來源建議使用 GeoNames 公開資料集 cities15000.txt）
 */
object CityLoader {
    private const val MIN_POPULATION = 50_000L
    private const val TAG = "CityLoader"

    @Volatile private var cache: List<CityDef>? = null

    fun load(context: Context): List<CityDef> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val result = mutableListOf<CityDef>()
            try {
                context.resources.openRawResource(R.raw.cities_50k).use { input ->
                    BufferedReader(InputStreamReader(input)).useLines { lines ->
                        for (line in lines) {
                            if (line.isBlank()) continue
                            val c = line.split(",")
                            if (c.size < 4) continue
                            val name = c[0].trim()
                            val lat  = c[1].trim().toDoubleOrNull() ?: continue
                            val lon  = c[2].trim().toDoubleOrNull() ?: continue
                            val pop  = c[3].trim().toLongOrNull() ?: continue
                            if (pop >= MIN_POPULATION) result.add(CityDef(name, lat, lon, pop))
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "load cities_50k.csv failed: ${e.message}", e)
            }
            cache = result
            android.util.Log.d(TAG, "Loaded ${result.size} cities (pop >= $MIN_POPULATION)")
            return result
        }
    }
}
