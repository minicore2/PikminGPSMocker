package com.devtool.gpsmocker.utils

import android.content.Context
import android.util.Log
import com.devtool.gpsmocker.db.LandmarkDao
import com.devtool.gpsmocker.db.LandmarkDatabase
import com.devtool.gpsmocker.db.LandmarkEntity
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * 抓取條件（依需求變更 2026）：
 *  1. 地標必須位於人口 >= 50,000 的城市周邊 15km 內
 *     — 種子點改用城市座標清單（見 CityLoader / res/raw/cities_50k.csv），
 *       不再使用經緯度網格亂數種子。
 *     — MediaWiki geosearch 的 gsradius 硬性上限是 10,000m，
 *       故以「城市中心 10km 主圈 + 東西南北四方向補點各 5km」的方式，
 *       涵蓋到完整 15km 範圍。
 *  2. 地標必須有中文翻譯
 *     — 直接查詢 zh.wikipedia.org 的 geosearch（原本是 en.wikipedia.org），
 *       抓回來的頁面本身就是中文條目，天生滿足「有中文版本」這個條件，
 *       不需要額外呼叫 langlinks API 做二次判斷。
 *       variant=zh-tw 強制轉繁體，與 App 介面語言一致。
 */
object WikiFetcher {

    private const val TAG = "WikiFetcher"

    /** 使用者需求的目標涵蓋半徑 */
    private const val TARGET_RADIUS_M = 15_000
    /** MediaWiki geosearch API 的半徑上限（超過會被拒絕或夾住，故不可直接使用 15000） */
    private const val API_MAX_RADIUS_M = 10_000
    /** 補點半徑，用來把涵蓋範圍從 10km 延伸到 15km */
    private const val RING_RADIUS_M = 5_000
    /** 補點與城市中心的距離（略小於 10km+5km，確保與主圈有重疊、不留空隙） */
    private const val RING_OFFSET_M = 12_500.0

    private const val PROGRESS_EVERY = 20

    var onProgress: ((String, Int, Int, String) -> Unit)? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    // 只用來分類 continent 欄位（供統計頁 / randomByContinent 使用），
    // 不再拿來產生種子點——種子點改用 CityLoader 提供的城市清單。
    private data class ContDef(
        val continent: String,
        val latMin: Double, val latMax: Double,
        val lonMin: Double, val lonMax: Double
    )
    private val CONTINENTS = listOf(
        ContDef("Asia",      5.0,  55.0,  26.0, 150.0),
        ContDef("Europe",   36.0,  71.0, -11.0,  40.0),
        ContDef("Africa",  -35.0,  37.0, -18.0,  51.0),
        ContDef("Americas", 15.0,  72.0,-169.0, -52.0),
        ContDef("Americas",-56.0,  13.0, -82.0, -34.0),
        ContDef("Oceania", -47.0,  -0.5, 110.0, 179.0),
        ContDef("Asia",     12.0,  42.0,  34.0,  63.0),
    )
    private fun continentFor(lat: Double, lon: Double): String =
        CONTINENTS.firstOrNull { lat in it.latMin..it.latMax && lon in it.lonMin..it.lonMax }
            ?.continent ?: "Other"

    // 中文關鍵字分類——原本是英文關鍵字比對 en.wikipedia 的英文標題，
    // 現在 geosearch 改打 zh.wikipedia，標題已經是中文，故關鍵字也改成中文。
    private val CATEGORY_KEYWORDS = mapOf(
        "landmark" to listOf("塔", "紀念碑", "雕像", "橋", "門", "廣場", "廟", "寺", "教堂",
                              "清真寺", "大教堂", "城堡", "宮", "堡壘", "遺跡", "拱門", "神社",
                              "佛塔", "燈塔"),
        "heritage" to listOf("世界遺產", "古蹟", "歷史", "古代", "考古", "保存區", "殖民",
                              "舊城", "遺產"),
        "culture"  to listOf("博物館", "美術館", "劇院", "戲院", "藝術", "文化", "圖書館",
                              "大學", "歌劇院", "音樂廳"),
        "scenery"  to listOf("公園", "自然", "湖", "河", "瀑布", "山", "海灘", "島", "峽谷",
                              "冰川", "森林", "保護區", "灣", "火山", "谷", "洞", "溫泉",
                              "花園", "國家公園", "礁")
    )
    private fun guessCategory(title: String): String {
        for ((cat, kws) in CATEGORY_KEYWORDS) if (kws.any { title.contains(it) }) return cat
        return "landmark"
    }

    /**
     * 增量更新 — 現有資料永遠不會被刪除。
     * 種子點改為「人口 >= 50,000 的城市清單」（CityLoader），
     * 每個城市會發出多次 geosearch 請求以涵蓋 15km 範圍（見檔案頂端說明）。
     */
    suspend fun fetchAll(context: Context) = withContext(Dispatchers.IO) {
        val dao = LandmarkDatabase.get(context).landmarkDao()
        val cities = CityLoader.load(context).shuffled()

        if (cities.isEmpty()) {
            onProgress?.invoke("錯誤", 0, 0, "⚠️ 找不到城市資料，請確認 res/raw/cities_50k.csv 是否存在")
            Log.e(TAG, "cities_50k.csv 缺失或為空，中止抓取")
            return@withContext
        }

        // Build dedup sets from existing DB (one-time load)
        val existingWikiIds = HashSet<Int>(dao.getAllWikiIds())
        val existingLatLon  = dao.getAllLatLon().mapTo(HashSet()) {
            "${"%.4f".format(it.lat)},${"%.4f".format(it.lon)}"
        }

        val beforeTotal = dao.count()
        var newCount = 0
        Log.d(TAG, "Incremental fetch start. DB=$beforeTotal, cities=${cities.size}")
        onProgress?.invoke("開始", beforeTotal, beforeTotal,
            "已有 $beforeTotal 個地點，開始依 ${cities.size} 個城市" +
            "（人口 ≥5萬、範圍 ${TARGET_RADIUS_M / 1000}km）掃描…")

        for ((idx, city) in cities.withIndex()) {
            coroutineContext.ensureActive()
            newCount += fetchForCity(dao, city, existingWikiIds, existingLatLon)
            if (idx % PROGRESS_EVERY == 0) {
                onProgress?.invoke(city.name, newCount, cities.size,
                    "掃描中 (${idx + 1}/${cities.size})：${city.name}｜新增 $newCount 筆")
            }
        }

        val total = dao.count()
        onProgress?.invoke("完成", total, total, "✅ 更新完成，新增 $newCount 個，共 $total 個地點")
        Log.d(TAG, "Fetch done. new=$newCount total=$total")
    }

    /**
     * 針對單一城市抓取地標：
     *  - 主圈：城市中心，半徑 10km（API 上限）
     *  - 補點：往東西南北四方向偏移 ~12.5km，各用 5km 半徑查詢，
     *    藉此涵蓋 10km ~ 15km 這一圈範圍，達成 15km 總涵蓋半徑。
     */
    private suspend fun fetchForCity(
        dao: LandmarkDao,
        city: CityDef,
        existingWikiIds: HashSet<Int>,
        existingLatLon: HashSet<String>
    ): Int {
        return try {
            var added = 0

            // 主圈：城市中心，10km（API 上限）
            added += insertBatch(
                dao, geosearchZh(city.lat, city.lon, API_MAX_RADIUS_M),
                city, existingWikiIds, existingLatLon
            )
            delay(150)

            // 補點：涵蓋 10km ~ 15km 環狀範圍
            val latOffsetDeg = metersToDegLat(RING_OFFSET_M)
            val lonOffsetDeg = metersToDegLon(RING_OFFSET_M, city.lat)
            val ringPoints = listOf(
                (city.lat + latOffsetDeg) to city.lon,
                (city.lat - latOffsetDeg) to city.lon,
                city.lat to (city.lon + lonOffsetDeg),
                city.lat to (city.lon - lonOffsetDeg)
            )
            for ((lat, lon) in ringPoints) {
                coroutineContext.ensureActive()
                added += insertBatch(
                    dao, geosearchZh(lat, lon, RING_RADIUS_M),
                    city, existingWikiIds, existingLatLon
                )
                delay(150)
            }

            added
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "city ${city.name} error: ${e.message}")
            delay(500)
            0
        }
    }

    private suspend fun insertBatch(
        dao: LandmarkDao,
        batch: List<GeoItem>,
        city: CityDef,
        existingWikiIds: HashSet<Int>,
        existingLatLon: HashSet<String>
    ): Int {
        val entities = batch.mapNotNull { item ->
            // Primary dedup: wikiId
            if (item.pageId > 0 && item.pageId in existingWikiIds) return@mapNotNull null
            // Secondary dedup: lat/lon (covers CSV-imported rows with wikiId=0)
            val llKey = "${"%.4f".format(item.lat)},${"%.4f".format(item.lon)}"
            if (llKey in existingLatLon) return@mapNotNull null

            // Register immediately so same-session duplicates are also skipped
            if (item.pageId > 0) existingWikiIds.add(item.pageId)
            existingLatLon.add(llKey)

            LandmarkEntity(
                name      = item.name,             // zh.wikipedia 回傳，已是中文標題
                summary   = "近 ${city.name}",
                lat       = item.lat,
                lon       = item.lon,
                category  = guessCategory(item.name),
                continent = continentFor(item.lat, item.lon),
                wikiId    = item.pageId
            )
        }
        if (entities.isNotEmpty()) dao.insertAll(entities)
        return entities.size
    }

    private data class GeoItem(val pageId: Int, val name: String, val lat: Double, val lon: Double)

    /**
     * 查詢中文維基百科的 geosearch（原本是 en.wikipedia.org）。
     * 直接打 zh.wikipedia.org，回傳結果本身就是中文條目，滿足「必須有中文翻譯」的條件，
     * 不需要額外呼叫 langlinks API 做二次判斷。
     */
    private fun geosearchZh(lat: Double, lon: Double, radiusM: Int): List<GeoItem> {
        val safeRadius = radiusM.coerceIn(1, API_MAX_RADIUS_M)
        val url = "https://zh.wikipedia.org/w/api.php" +
            "?action=query&list=geosearch" +
            "&gscoord=${lat}|${lon}" +
            "&gsradius=${safeRadius}" +
            "&gslimit=50" +
            "&variant=zh-tw" +
            "&format=json"
        val body = fetchSync(url) ?: return emptyList()
        return try {
            val arr = JSONObject(body).optJSONObject("query")
                ?.optJSONArray("geosearch") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o  = arr.getJSONObject(i)
                val lt = o.optDouble("lat", Double.NaN)
                val ln = o.optDouble("lon", Double.NaN)
                if (lt.isNaN() || ln.isNaN()) null
                else GeoItem(o.optInt("pageid", 0), o.optString("title", ""), lt, ln)
            }
        } catch (e: Exception) {
            Log.e(TAG, "parse: ${e.message}"); emptyList()
        }
    }

    private fun fetchSync(url: String): String? = try {
        client.newCall(
            Request.Builder().url(url)
                .header("User-Agent", "PikminGPSMocker/2.1 Android (com.devtool.gpsmocker)")
                .build()
        ).execute().use { r ->
            if (!r.isSuccessful) { Log.w(TAG, "HTTP ${r.code}"); null }
            else r.body?.string()
        }
    } catch (e: Exception) { Log.e(TAG, "fetch: ${e.message}"); null }

    // ── 座標偏移換算 ──────────────────────────────

    private fun metersToDegLat(m: Double) = m / 111_320.0
    private fun metersToDegLon(m: Double, atLat: Double) =
        m / (111_320.0 * kotlin.math.cos(Math.toRadians(atLat)))
}
