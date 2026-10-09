package app.pinballpilot.data

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.pinballpilot.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton class PilotApi @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().callTimeout(24, TimeUnit.SECONDS).build()
    private val jsonType = "application/json".toMediaType()
    val configured get() = BuildConfig.SUPABASE_URL.startsWith("https://") && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("pilot-auth", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(KeyGenParameterSpec.Builder("pilot-auth", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()) }.generateKey()
    }
    private fun secret(name: String, value: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        prefs.edit().putString(name, Base64.encodeToString(c.iv + c.doFinal(value.toByteArray()), Base64.NO_WRAP)).apply()
    }
    private fun secret(name: String): String? = prefs.getString(name, null)?.let {
        val bytes = Base64.decode(it, Base64.NO_WRAP)
        Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))); String(doFinal(bytes.copyOfRange(12, bytes.size))) }
    }
    suspend fun signIn(email: String) = withContext(Dispatchers.IO) {
        check(configured) { "Connect Supabase to enable email accounts. Offline guides are ready to use." }
        val verifier = Base64.encodeToString(ByteArray(32).also(SecureRandom()::nextBytes), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        secret("verifier", verifier)
        val challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        request("/auth/v1/otp?redirect_to=pinballpilot%3A%2F%2Fauth", buildJsonObject { put("email", email); put("create_user", true); put("code_challenge", challenge); put("code_challenge_method", "s256") }, null)
    }
    suspend fun callback(uri: Uri) = withContext(Dispatchers.IO) {
        require(uri.scheme == "pinballpilot" && uri.host == "auth")
        val code = uri.getQueryParameter("code") ?: error("Sign-in link has no code. Request a fresh email link.")
        val result = request("/auth/v1/token?grant_type=pkce", buildJsonObject { put("auth_code", code); put("code_verifier", secret("verifier") ?: error("Open the link on the phone that requested it.")) }, null)
        storeSession(result); prefs.edit().remove("verifier").apply()
    }
    private fun storeSession(result: JsonObject) {
        val existingUser=secret("userId")
        val incomingUser=result["user"]?.jsonObject?.get("id")?.jsonPrimitive?.content
        check(existingUser==null||incomingUser==null||existingUser==incomingUser) {"This private build supports one account per installation. Clear app storage before switching accounts so private histories cannot mix."}
        secret("access", result.getValue("access_token").jsonPrimitive.content)
        secret("refresh", result.getValue("refresh_token").jsonPrimitive.content)
        result["user"]?.jsonObject?.get("id")?.jsonPrimitive?.content?.let { secret("userId",it) }
        prefs.edit().putLong("expires", System.currentTimeMillis() + (result["expires_in"]?.jsonPrimitive?.long ?: 3600) * 1000).apply()
    }
    private fun token(): String {
        val access = secret("access") ?: error("Sign in with your email to use online features.")
        if (System.currentTimeMillis() + 60_000 < prefs.getLong("expires", 0)) return access
        val result = request("/auth/v1/token?grant_type=refresh_token", buildJsonObject { put("refresh_token", secret("refresh") ?: error("Sign in again.")) }, null)
        storeSession(result); return result.getValue("access_token").jsonPrimitive.content
    }
    private fun request(path: String, body: JsonObject, access: String?): JsonObject {
        val request = Request.Builder().url(BuildConfig.SUPABASE_URL.trimEnd('/') + path).header("apikey", BuildConfig.SUPABASE_ANON_KEY).apply { access?.let { header("Authorization", "Bearer $it") } }.post(body.toString().toRequestBody(jsonType)).build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string() ?: error("Empty response")
            val result = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrDefault(buildJsonObject { })
            check(response.isSuccessful) { result["error"]?.jsonPrimitive?.content ?: result["msg"]?.jsonPrimitive?.content ?: "Online service unavailable. Your downloaded guides still work." }
            return result
        }
    }
    suspend fun ask(variantId: String, question: String, confirmedOutcomes:List<String> = emptyList()): JsonObject = withContext(Dispatchers.IO) {
        check(configured) { "AI is not connected yet. Use the downloaded scoring or multiball guide." }
        request("/functions/v1/pilot/coach", buildJsonObject { put("variantId", variantId); put("question", question); put("confirmedOutcomes",JsonArray(confirmedOutcomes.map(::JsonPrimitive)));put("requestId", java.util.UUID.randomUUID().toString()) }, token())
    }
    suspend fun analyse(photos: List<String>, gameId: String? = null): JsonObject = withContext(Dispatchers.IO) {
        check(configured) { "Photo analysis needs a connected backend. You can search for a machine manually." }
        val access=token()
        val result=request("/functions/v1/pilot/identify", buildJsonObject { put("photos", JsonArray(photos.map(::JsonPrimitive))); put("requestId", java.util.UUID.randomUUID().toString()) }, access)
        val uid=secret("userId")?:error("Sign in again to save private photos.")
        for(photo in photos) {
            val path="$uid/${java.util.UUID.randomUUID()}.jpg"
            val bytes=Base64.decode(photo.substringAfter(','),Base64.NO_WRAP)
            val upload=Request.Builder().url(BuildConfig.SUPABASE_URL.trimEnd('/')+"/storage/v1/object/player-photos/$path").header("apikey",BuildConfig.SUPABASE_ANON_KEY).header("Authorization","Bearer $access").post(bytes.toRequestBody("image/jpeg".toMediaType())).build()
            http.newCall(upload).execute().use {check(it.isSuccessful){"Analysis finished, but the photo could not be saved. Try syncing later."}}
            request("/rest/v1/player_photos",buildJsonObject {put("owner_id",uid);put("storage_path",path);gameId?.let{put("game_id",it)}},access)
        }
        result
    }
    private fun get(path:String):ByteArray {
        check(configured) {"Connect the backend and sign in to download packs."}
        val req=Request.Builder().url(BuildConfig.SUPABASE_URL.trimEnd('/')+path).header("apikey",BuildConfig.SUPABASE_ANON_KEY).header("Authorization","Bearer ${token()}").build()
        return http.newCall(req).execute().use {check(it.isSuccessful){"Download unavailable. Existing packs still work."};val body=it.body?:error("Empty download");check(body.contentLength()<=20_000_000){"Download too large"};body.bytes().also{data->check(data.size<=20_000_000){"Download too large"}}}
    }
    suspend fun catalogue():JsonArray=withContext(Dispatchers.IO){Json.parseToJsonElement(String(get("/functions/v1/pilot/catalogue"))).jsonArray}
    suspend fun pack(id:String):String=withContext(Dispatchers.IO){String(get("/functions/v1/pilot/packs/${Uri.encode(id)}"))}
    suspend fun playfield(path:String):ByteArray=withContext(Dispatchers.IO){get("/storage/v1/object/authenticated/playfields/${path.split('/').joinToString("/"){Uri.encode(it)}}")}
    suspend fun sync(payload:JsonObject):JsonObject=withContext(Dispatchers.IO){check(configured){"Connect and sign in to sync private history."};request("/functions/v1/pilot/sync",payload,token())}
    suspend fun syncEvent(event: EventEntity) = withContext(Dispatchers.IO) {
        check(configured) { "Connect and sign in to sync private history." }
        request("/functions/v1/pilot/activity", buildJsonObject { put("id", event.id); put("gameId", event.gameId); put("timestamp", event.timestamp); put("kind", event.kind); put("text", event.text) }, token())
    }
}
