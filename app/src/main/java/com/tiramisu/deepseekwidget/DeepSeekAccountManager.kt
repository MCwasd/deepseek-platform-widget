package com.tiramisu.deepseekwidget

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Manages DeepSeek platform login and Bearer Token lifecycle.
 *
 * Flow: login(email, password) → save token → auto re-login on 401
 *
 * Credentials and token are stored in EncryptedSharedPreferences (AES-256-GCM).
 * device_id is generated once per install and persisted.
 *
 * History pitfall avoided: no coroutines, no R8 trouble (isMinifyEnabled=false),
 * no AppCompat dependency (plain Context).
 */
class DeepSeekAccountManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "deepseek_account_prefs"
        private const val KEY_EMAIL = "email"
        private const val KEY_PASSWORD = "password"
        private const val KEY_TOKEN = "token"
        private const val KEY_DEVICE_ID = "device_id"

        private const val LOGIN_URL = "https://platform.deepseek.com/auth-api/v0/users/login"
        private const val TIMEOUT_SECONDS = 15L
    }

    private val gson = Gson()

    // Reuse OkHttp client pattern from DeepSeekApiClient — no coroutines, sync only
    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val encryptedPrefs: SharedPreferences = run {
        val mk = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, PREFS_NAME, mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // device_id from browser packet capture — not random UUID!
    // The web SDK generates a persistent device fingerprint (Base64).
    // Hardcoded to match the real browser request for now.
    private val deviceId: String = encryptedPrefs.getString(KEY_DEVICE_ID, null) ?: run {
        val id = "BVq6rYPvnZX0QGXK0v+Mc+gBvP9jXPAaTeHPJtqTADSm8XQffeOj8x1BV7xSD2O0sPkcWFwduXevl6TUUKndExg=="
        encryptedPrefs.edit().putString(KEY_DEVICE_ID, id).apply()
        id
    }

    // ─── Login ─────────────────────────────────────────────────

    /**
     * Login with email and password.
     * On success: saves credentials + Bearer token to EncryptedSharedPreferences.
     * On failure: throws descriptive Exception.
     */
    fun login(email: String, password: String): String {
        val json = gson.toJson(LoginRequest(
            email = email,
            password = password,
            deviceId = deviceId,
            os = "web"
        ))

        val request = Request.Builder()
            .url(LOGIN_URL)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("x-client-platform", "web")
            .header("x-client-version", "1.0.0")
            .header("x-app-version", "1.0.0")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val bodyStr = response.body?.string() ?: ""
            throw when (response.code) {
                429 -> Exception("登录请求过于频繁，请稍后再试")
                else -> Exception("登录失败 (${response.code}): $bodyStr")
            }
        }

        val body = response.body?.string() ?: throw Exception("登录响应为空")
        Log.d("DS_LOGIN_RESP", body)

        val loginResp = gson.fromJson(body, LoginResponse::class.java)
        val data = loginResp.data
            ?: throw Exception("登录响应格式异常: $body")

        // Check biz_code: 0=success, other values indicate login failure
        if (data.bizCode != 0) {
            // Log full response body so we can see the real biz_msg
            throw Exception("登录失败 (biz_code=${data.bizCode}, biz_msg=${data.bizMsg}): $body")
        }

        val token = data.bizData?.user?.token
            ?: throw Exception("登录响应未包含 token")

        saveCredentials(email, password)
        saveToken(token)

        return token
    }

    // ─── Persistence ────────────────────────────────────────────

    fun saveCredentials(email: String, password: String) {
        encryptedPrefs.edit()
            .putString(KEY_EMAIL, email)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    fun loadEmail(): String? = encryptedPrefs.getString(KEY_EMAIL, null)
    fun loadPassword(): String? = encryptedPrefs.getString(KEY_PASSWORD, null)
    fun loadToken(): String? = encryptedPrefs.getString(KEY_TOKEN, null)

    fun saveToken(token: String) {
        encryptedPrefs.edit().putString(KEY_TOKEN, token).apply()
    }

    fun clearCredentials() {
        encryptedPrefs.edit()
            .remove(KEY_EMAIL)
            .remove(KEY_PASSWORD)
            .remove(KEY_TOKEN)
            .remove(KEY_DEVICE_ID)
            .apply()
    }

    fun hasCredentials(): Boolean = !loadEmail().isNullOrBlank() && !loadPassword().isNullOrBlank()
    fun hasToken(): Boolean = !loadToken().isNullOrBlank()

    // ─── Token Lifecycle ────────────────────────────────────────

    /**
     * Returns a valid Bearer token.
     * If no cached token exists but credentials are stored, auto re-login.
     * Returns null only when no credentials are available at all.
     */
    fun getValidToken(): String? {
        return try {
            val token = loadToken()
            if (token != null) return token

            val email = loadEmail() ?: return null
            val password = loadPassword() ?: return null

            login(email, password)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Called when an API returns 401. Attempts to re-login.
     * Returns new token on success, null on failure.
     */
    fun refreshToken(): String? {
        val email = loadEmail() ?: return null
        val password = loadPassword() ?: return null
        return try {
            login(email, password)
        } catch (_: Exception) {
            null
        }
    }
}

// ─── Request / Response DTOs ─────────────────────────────────────

// Field order matches browser packet capture exactly: email, mobile, password, area_code, device_id, os
data class LoginRequest(
    val email: String,
    val mobile: String = "",
    val password: String,
    @SerializedName("area_code") val areaCode: String = "",
    @SerializedName("device_id") val deviceId: String,
    val os: String
)

data class LoginResponse(
    val code: Int = -1,
    val msg: String = "",
    val data: LoginData? = null
)

data class LoginData(
    @SerializedName("biz_code") val bizCode: Int = -1,
    @SerializedName("biz_msg") val bizMsg: String = "",
    @SerializedName("biz_data") val bizData: LoginBizData? = null
)

data class LoginBizData(
    val user: LoginUser? = null
)

data class LoginUser(
    val token: String? = null
)
