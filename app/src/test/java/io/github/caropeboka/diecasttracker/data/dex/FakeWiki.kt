package io.github.caropeboka.diecasttracker.data.dex

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.net.URLEncoder

/** The fan wikis' API and image CDN from fixtures (the web tests' `Http::fake` in `DexTest`). */
class FakeWiki(private val pages: Map<String, String> = PAGES) : Interceptor {
    val requests = mutableListOf<Request>()
    var offline = false

    /** What the image CDN answers with. */
    var imageStatus = 200

    val imageDownloads get() = requests.count { it.url.host == CDN }
    val apiCalls get() = requests.count { it.url.encodedPath == "/api.php" }

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        requests += request
        if (offline) throw IOException("Connection reset")

        if (request.url.host == CDN) return respond(request, "image/jpeg", "fake-image-bytes", imageStatus)

        val url = request.url
        return when (url.queryParameter("action")) {
            "parse" -> {
                val page = url.queryParameter("page")
                val body = pages[page]?.let { wikitext ->
                    buildJsonObject { putJsonObject("parse") { put("title", page); put("wikitext", wikitext) } }
                } ?: buildJsonObject {
                    putJsonObject("error") { put("code", "missingtitle"); put("info", "The page does not exist.") }
                }
                respond(request, "application/json", body.toString())
            }
            else -> {
                // Like the real API: underscores normalized to spaces.
                val titles = url.queryParameter("titles").orEmpty().split('|')
                val body = buildJsonObject {
                    putJsonObject("query") {
                        putJsonArray("normalized") {
                            addJsonObject { put("from", "File:Porsche_Cayman_2015_MB1.jpg"); put("to", "File:Porsche Cayman 2015 MB1.jpg") }
                        }
                        putJsonArray("pages") {
                            titles.map { it.replace('_', ' ') }.forEach { title ->
                                addJsonObject {
                                    put("title", title)
                                    putJsonArray("imageinfo") {
                                        addJsonObject { put("thumburl", thumbUrl(title.substring(5))) }
                                    }
                                }
                            }
                        }
                    }
                }
                respond(request, "application/json", body.toString())
            }
        }
    }

    private fun respond(request: Request, type: String, body: String, code: Int = 200) = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code == 200) "OK" else "Error")
        .header("Content-Type", type)
        .body(body.toResponseBody(type.toMediaType()))
        .build()

    companion object {
        const val CDN = "static.wikia.nocookie.net"

        fun thumbUrl(file: String) = "https://$CDN/wiki/${URLEncoder.encode(file, "UTF-8")}/scale-to-width-down/240"

        /** Trimmed copy of the real "List of 2026 Hot Wheels" table, quirks included. */
        val HW_2026 = """
==2026 Hot Wheels #1-250==
For 2026, the Hot Wheels mainline is numbered 1 through 250.
{| class="sortable wikitable" style="font-size: 85%;" width="100%"
|-
! style="border-style: none none solid solid; |Toy #
! style="border-style: none none solid solid; |Col.#
! style="border-style: none none solid solid; |Model Name
! style="border-style: none none solid solid; |Series
! style="border-style: none none solid solid; |Series #
! style="border-style: none none solid solid; |Photo
|-
|JJM00
|009
|[['87 Buick Regal GNX]]
|bgcolor="#E60026" |[[Nightspeed Mini Collection (2026)|<font color="black">Nightspeed</font>]]<br>{{TH|2026}}
|1/10
|[[File:Image Not Available.jpg|center|75px]]
|-
|JJM15
|013
|[[Drift-Ender]]
| bgcolor="yellow" |[[Truckin' Along Mini Collection (2026)|<font color="black">Truckin' Along]]</font><br>'''[[2026 Treasure Hunts Series|<font color=#00CC00">Super Treasure Hunt]]'''</font>
|1/5
|[[File:STH Drift-Ender.jpg|center|75px]]
|-
|JJJ29
|061
|[[Mazda RX-7]]
|bgcolor="#54CBF5" |[[HW J-Imports Mini Collection (2026)|<font color="black">HW J-Imports</font>]]
|2/10
|[[File:1978MazdaRX-7.jpg|center|75px]]
|-
|JJL35
|061
|[[Mazda RX-7]] (2nd Color - Zamac)
|bgcolor="#54CBF5" |[[HW J-Imports Mini Collection (2026)|<font color="black">HW J-Imports</font>]]<br>{{WM|2026}}
|2/10
|[[File:MazdaRX-7Custom.jpg|center|75px]]
|-
|FYG07
|223
|[[Lamborghini Aventador J]] (2nd Color)
|bgcolor="red" |[[HW Exotics Mini Collection (2026)|<font color="black">HW Exotics</font>]]
|8/10
|[[File:1280х720.jpg|center|75px]]
|-
|JJX99
|250
|[[Bone Shaker]] <!-- Incorrectly numbered as 235 -->
|bgcolor="black" |[[HW Flames Mini Collection (2026)|<font color="white">HW Flames</font>]]
|5/5
|[[File:BoneShaker2026.jpg|center|75px]]
|-
|PROMO
|No #
|[[Promo Car]]
|Promo
|
|
|}
        """.trimIndent()

        val HW_2023 = """
{| class="wikitable sortable"
! Toy # !! Col.# !! Model Name !! Series !! Series # !! Photo
|-
|HKH26
|061
|[[Mercedes-Benz Unimog 1300L|Mercedes-Benz Unimog 1300]]
| bgcolor="#FFAA1D" |[[HW Haulers Mini Collection (2023)|<font color="white">HW Haulers</font>]]
|4/5
|[[File:Unimog.png|center|75px]]
|-
|HKJ10
|021
|[[Subaru WRX STI]]
| bgcolor="#87CEFA" |[[HW J-Imports Mini Collection (2023)|<font color="black">HW J-Imports</font>]]
|2/10
|[[File:WRX.png|center|75px]]
|}
        """.trimIndent()

        val MB_2015 = """
==1-120 Series==
{|width="80%" style="font-size: 100%; text-align: center" class="wikitable sortable"
! style="border-style: none none solid solid" |'''Model #''' <ref name=mbxforum>[https://www.mbxforum.com/x.php www.mbxforum.com] Retrieved 2019</ref>
!style="border-style: none none solid solid"|'''Man. #''' <ref name=mbxforum/>
!style="border-style: none none solid solid"|'''Card #'''
!style="border-style: none none solid solid"|'''Model Name''' <ref name=mbxforum/>
!style="border-style: none none solid solid"|'''Segment'''
!style="border-style: none none solid solid"|'''Photo''' <ref name=mbxforum/>
|-
|MB1
|MB979
|CFV75
|[[Porsche Cayman]] <br> (New)
|MBX Adventure City
|[[File:Porsche_Cayman_2015_MB1.jpg|100px]]
|-
|MB5
|MB974
|CFV80<br>DFK04
|[[Cadillac One]]
|MBX Adventure City
|[[File:Cadillac_One_2015.jpg|100px]]
|}
        """.trimIndent()

        val PAGES = mapOf(
            "List of 2026 Hot Wheels" to HW_2026,
            "List of 2023 Hot Wheels" to HW_2023,
            "List of 2015 Matchbox" to MB_2015,
        )
    }
}
