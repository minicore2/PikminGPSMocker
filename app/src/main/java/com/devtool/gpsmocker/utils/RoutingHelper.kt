package com.devtool.gpsmocker.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.util.concurrent.TimeUnit

/**
 * 路線模式二：「沿道路走」。
 *
 * 呼叫公開的 OSRM demo server，把使用者依序選取的航點丟給它做路網比對，
 * 回傳的是「貼著實際道路」的密集座標序列（每隔幾公尺一個點），
 * 而不是使用者點的那幾個航點之間的直線。
 *
 * 不要求最短路徑——OSRM 預設就是回傳它認為最快/最合理的一條路線，
 * 我們只是原封不動採用它算出來的那條路，並不會額外做「一定要最短」的最佳化。
 *
 * 注意：router.project-osrm.org 是 OSRM 官方提供、僅供展示測試用的免費公開伺服器，
 * 目前只提供 "driving"（開車）路網 profile。用在「沿道路走」情境已經足夠
 * （它一樣會走一般道路、巷弄），但如果之後想嚴格模擬「只走人行道」，
 * 需要自行架設支援 foot profile 的 OSRM/GraphHopper 服務，並把下面的
 * BASE_URL 換掉即可，呼叫端的介面完全不用改。
 */
object RoutingHelper {

    private const val TAG = "RoutingHelper"
    private const val BASE_URL = "https://router.project-osrm.org/route/v1/driving/"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * 依序把 [waypoints] 串成一條沿道路的路徑。
     * 回傳 null 代表查無路線、連線失敗、或伺服器回應格式不如預期——
     * 呼叫端應該 fallback 回原本的直線 waypoints，不要讓使用者卡住。
     */
    suspend fun fetchRoadRoute(waypoints: List<GeoPoint>): List<GeoPoint>? =
        withContext(Dispatchers.IO) {
            if (waypoints.size < 2) return@withContext null
            try {
                val coords = waypoints.joinToString(";") { "${it.longitude},${it.latitude}" }
                val url = "$BASE_URL$coords?overview=full&geometries=geojson"
                val req = Request.Builder().url(url)
                    .header("User-Agent", "PikminGPSMocker/2.1 Android (com.devtool.gpsmocker)")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "HTTP ${resp.code}")
                        return@withContext null
                    }
                    val body = resp.body?.string() ?: return@withContext null
                    val json = JSONObject(body)
                    if (json.optString("code") != "Ok") {
                        Log.w(TAG, "OSRM code=${json.optString("code")}")
                        return@withContext null
                    }
                    val route = json.optJSONArray("routes")?.optJSONObject(0)
                        ?: return@withContext null
                    val coordsArr = route.optJSONObject("geometry")
                        ?.optJSONArray("coordinates") ?: return@withContext null

                    val pts = (0 until coordsArr.length()).mapNotNull { i ->
                        val pair = coordsArr.optJSONArray(i) ?: return@mapNotNull null
                        // GeoJSON 座標順序是 [lon, lat]，跟一般習慣的 (lat, lon) 相反
                        val lon = pair.optDouble(0, Double.NaN)
                        val lat = pair.optDouble(1, Double.NaN)
                        if (lat.isNaN() || lon.isNaN()) null else GeoPoint(lat, lon)
                    }
                    if (pts.size < 2) null else pts
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetchRoadRoute failed: ${e.message}")
                null
            }
        }
}
