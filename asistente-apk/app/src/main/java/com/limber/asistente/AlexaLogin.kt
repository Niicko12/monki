package com.limber.asistente

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Inicio de sesión de Amazon desde la consola, sin computadora: el mismo proceso que alexa-cookie2.
 * 1. Se abre la página de Amazon (en un WebView) como si fuera la app de Alexa de iOS, con PKCE.
 * 2. Amazon redirige a /ap/maplanding con un código; [register] lo cambia por el permiso (refresh token) en /auth/register.
 * 3. [AlexaClient.refreshSession] convierte ese permiso en las cookies de amazon.com.mx y el csrf.
 * Nada sale de la consola: el permiso se guarda en las preferencias de la app.
 */
class AlexaLogin(private val prefs: Prefs) {
    private val random = SecureRandom()
    private fun bytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }
    private fun b64url(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    /** Identificador de "dispositivo" para Amazon: hex de 32 caracteres hex en mayúsculas + sufijo del tipo de app. */
    private val deviceId = bytes(16).joinToString("") { "%02X".format(it) }.toByteArray().joinToString("") { "%02x".format(it) } +
        "23413249564c5635564d32573831"
    private val deviceSerial = bytes(16).joinToString("") { "%02x".format(it) }
    private val verifier = b64url(bytes(32))
    private val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

    /** Cookies que la app de Alexa pone antes de iniciar sesión (Amazon las espera). */
    val frc: String = Base64.encodeToString(bytes(313), Base64.NO_WRAP)
    val mapMd: String = Base64.encodeToString(
        """{"device_user_dictionary":[],"device_registration_data":{"software_version":"1"},"app_identifier":{"app_version":"$APP_VERSION","bundle_id":"com.amazon.echo"}}""".toByteArray(),
        Base64.NO_WRAP,
    )

    val loginUrl: String get() {
        val p = linkedMapOf(
            "openid.return_to" to RETURN_TO,
            "openid.assoc_handle" to "amzn_dp_project_dee_ios",
            "openid.identity" to "http://specs.openid.net/auth/2.0/identifier_select",
            "pageId" to "amzn_dp_project_dee_ios",
            "accountStatusPolicy" to "P1",
            "openid.claimed_id" to "http://specs.openid.net/auth/2.0/identifier_select",
            "openid.mode" to "checkid_setup",
            "openid.ns.oa2" to "http://www.$LOGIN_PAGE/ap/ext/oauth/2",
            "openid.oa2.client_id" to "device:$deviceId",
            "openid.ns.pape" to "http://specs.openid.net/extensions/pape/1.0",
            "openid.oa2.response_type" to "code",
            "openid.ns" to "http://specs.openid.net/auth/2.0",
            "openid.pape.max_auth_age" to "0",
            "openid.oa2.scope" to "device_auth_access",
            "openid.oa2.code_challenge_method" to "S256",
            "openid.oa2.code_challenge" to challenge,
            "language" to "es_MX",
        )
        return "https://www.$LOGIN_PAGE/ap/signin?" + p.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
    }

    /** ¿Esta dirección es la vuelta de Amazon con el código? Devuelve el código o null. */
    fun codeFrom(url: String): String? =
        if (url.startsWith(RETURN_TO)) android.net.Uri.parse(url).getQueryParameter("openid.oa2.authorization_code") else null

    /**
     * Cambia el código por el permiso de la app y deja lista la sesión. [cookies] son las cookies de amazon.com del
     * WebView después de iniciar sesión. Lanza una excepción con el motivo si Amazon no acepta.
     */
    fun register(code: String, cookies: String) {
        val website = JSONArray()
        cookies.split(";").map { it.trim() }.filter { it.contains("=") }.forEach {
            website.put(JSONObject().put("Name", it.substringBefore("=")).put("Value", it.substringAfter("=")))
        }
        val body = JSONObject()
            .put("requested_extensions", JSONArray().put("device_info").put("customer_info"))
            .put("cookies", JSONObject().put("website_cookies", website).put("domain", ".$LOGIN_PAGE"))
            .put("registration_data", JSONObject()
                .put("domain", "Device").put("app_version", APP_VERSION).put("device_type", "A2IVLV5VM2W81")
                .put("device_name", "%FIRST_NAME%'s%DUPE_STRATEGY_1ST%$APP_NAME").put("os_version", "18.3.1")
                .put("device_serial", deviceSerial).put("device_model", "iPhone").put("app_name", APP_NAME).put("software_version", "1"))
            .put("auth_data", JSONObject()
                .put("client_id", deviceId).put("authorization_code", code).put("code_verifier", verifier)
                .put("code_algorithm", "SHA-256").put("client_domain", "DeviceLegacy"))
            .put("user_context_map", JSONObject().put("frc", frc))
            .put("requested_token_type", JSONArray().put("bearer").put("mac_dms").put("website_cookies"))
        val conn = (URL("https://api.$LOGIN_PAGE/auth/register").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 10_000; readTimeout = 20_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept-Language", "es-MX")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cookie", cookies)
            setRequestProperty("x-amzn-identity-auth-domain", "api.$LOGIN_PAGE")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code2 = conn.responseCode
        val text = (if (code2 in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        val tokens = runCatching { JSONObject(text).getJSONObject("response").getJSONObject("success").getJSONObject("tokens") }.getOrNull()
            ?: throw RuntimeException("Amazon no aceptó el inicio de sesión ($code2)")
        val login = tokens.optJSONArray("website_cookies")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.joinToString("; ") { "${it.optString("Name")}=${it.optString("Value")}" }
        }.orEmpty()
        prefs.alexaRefresh = tokens.getJSONObject("bearer").getString("refresh_token")
        prefs.alexaAppName = APP_NAME
        prefs.alexaLoginCookie = listOf("frc=$frc", "map-md=$mapMd", login).filter { it.isNotBlank() }.joinToString("; ")
        if (!AlexaClient(prefs).refreshSession()) throw RuntimeException("no pude abrir la sesión del Echo")
    }

    companion object {
        const val LOGIN_PAGE = "amazon.com"
        const val RETURN_TO = "https://www.$LOGIN_PAGE/ap/maplanding"
        const val APP_VERSION = "2.2.651540.0"
        const val APP_NAME = "Monki"
        const val USER_AGENT = "AmazonWebView/Amazon Alexa/$APP_VERSION/iOS/18.3.1/iPhone"
    }
}
