package io.github.agelinena.bergapipe.update

import android.content.Context
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Uma release publicada no GitHub do Bergapipe. */
data class Release(
    val tag: String,
    val versionName: String,
    /** versionCode sem o dígito da arquitetura (sempre múltiplo de 10). */
    val versionCode: Long,
    /** APK para a arquitetura deste aparelho. */
    val apkUrl: String,
)

internal object ReleaseSource {
    private const val REPO = "Agelinena/Bergapipe"
    private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val VERSION_ASSET = "version.json"

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * A release "latest" do repositório, ou null se ela não tiver APK para este aparelho.
     * O GitHub nunca devolve pre-release nem rascunho em /releases/latest.
     */
    @Throws(IOException::class)
    fun latest(): Release? {
        val release = JSONObject(get(LATEST_URL, "application/vnd.github+json"))
        val assets = release.getJSONArray("assets")
        var versionUrl: String? = null
        val apks = LinkedHashMap<String, String>()
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.getString("name")
            val url = asset.getString("browser_download_url")
            when {
                name == VERSION_ASSET -> versionUrl = url
                name.endsWith(".apk") -> apks[name] = url
            }
        }
        val apkUrl = pickApk(apks) ?: return null
        val version = JSONObject(get(versionUrl ?: return null, null))
        return Release(
            tag = release.getString("tag_name"),
            versionName = version.getString("versionName"),
            versionCode = version.getLong("versionCode"),
            apkUrl = apkUrl,
        )
    }

    /** versionCode instalado, sem o dígito da arquitetura. */
    fun installedVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return PackageInfoCompat.getLongVersionCode(info) / 10 * 10
    }

    /** Os APKs se chamam Bergapipe_<versão>-<abi>.apk; escolhe a melhor ABI do aparelho. */
    private fun pickApk(apks: Map<String, String>): String? {
        for (abi in Build.SUPPORTED_ABIS) {
            apks.entries.firstOrNull { it.key.endsWith("-$abi.apk") }?.let { return it.value }
        }
        return apks.entries.firstOrNull { it.key.endsWith("-universal.apk") }?.value
    }

    private fun get(url: String, accept: String?): String {
        val request = Request.Builder().url(url).apply {
            if (accept != null) header("Accept", accept)
        }.build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} em $url")
            return response.body.string()
        }
    }
}
