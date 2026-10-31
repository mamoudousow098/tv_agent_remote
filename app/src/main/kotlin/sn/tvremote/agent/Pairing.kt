package sn.tvremote.agent

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Appairage par code affiché sur la TV.
 *
 * - Un code à 6 chiffres est généré une fois et conservé (stable entre redémarrages).
 * - Le téléphone envoie ce code à POST /pair et reçoit un jeton aléatoire de 128 bits.
 * - Toutes les commandes exigent ce jeton (en-tête Authorization: Bearer …).
 * - Après 5 codes faux, l'appairage est bloqué 60 s (anti force brute :
 *   1 million de codes possibles, 5 essais par minute).
 * - Seule l'empreinte SHA-256 du jeton est stockée.
 */
class Pairing(context: Context) {

    private val prefs = context.getSharedPreferences("tv_agent", Context.MODE_PRIVATE)
    private val random = SecureRandom()

    private var failures = 0
    private var lockedUntil = 0L

    /** Code à afficher sur la TV. */
    val code: String
        get() = prefs.getString(KEY_CODE, null) ?: newCode()

    val isPaired: Boolean
        get() = prefs.getString(KEY_TOKEN_HASH, null) != null

    /** Nom du téléphone appairé (affiché sur la TV). */
    val clientName: String?
        get() = prefs.getString(KEY_CLIENT, null)

    /** Renvoie un jeton si [input] est le bon code, sinon null. */
    @Synchronized
    fun pair(input: String, client: String): PairResult {
        val now = System.currentTimeMillis()
        if (now < lockedUntil) return PairResult.Locked((lockedUntil - now) / 1000 + 1)

        if (!MessageDigest.isEqual(input.toByteArray(), code.toByteArray())) {
            failures++
            if (failures >= MAX_FAILURES) {
                failures = 0
                lockedUntil = now + LOCK_MS
            }
            return PairResult.WrongCode
        }

        failures = 0
        val bytes = ByteArray(16).also(random::nextBytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit()
            .putString(KEY_TOKEN_HASH, sha256(token))
            .putString(KEY_CLIENT, client.take(60))
            .apply()
        return PairResult.Success(token)
    }

    /** Vérifie un jeton reçu (comparaison en temps constant). */
    fun isValidToken(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        val stored = prefs.getString(KEY_TOKEN_HASH, null) ?: return false
        return MessageDigest.isEqual(sha256(token).toByteArray(), stored.toByteArray())
    }

    /** Oublie le téléphone appairé et génère un nouveau code. */
    @Synchronized
    fun reset() {
        prefs.edit().remove(KEY_TOKEN_HASH).remove(KEY_CLIENT).remove(KEY_CODE).apply()
        newCode()
    }

    private fun newCode(): String {
        val c = "%06d".format(random.nextInt(1_000_000))
        prefs.edit().putString(KEY_CODE, c).apply()
        return c
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    sealed interface PairResult {
        data class Success(val token: String) : PairResult
        data object WrongCode : PairResult
        data class Locked(val seconds: Long) : PairResult
    }

    private companion object {
        const val KEY_CODE = "pair_code"
        const val KEY_TOKEN_HASH = "token_sha256"
        const val KEY_CLIENT = "client_name"
        const val MAX_FAILURES = 5
        const val LOCK_MS = 60_000L
    }
}
