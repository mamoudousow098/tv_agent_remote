package sn.tvremote.agent

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Cœur de l'agent : service d'accessibilité qui
 *  1. héberge le serveur HTTP (port 8787) ;
 *  2. exécute les commandes reçues.
 *
 * Android garde ce service en vie tant qu'il est activé, et le relance après
 * un redémarrage de la TV : pas besoin de service en avant-plan.
 *
 * Protocole (JSON) :
 *   GET  /info                         → identité, commandes supportées (sans jeton)
 *   POST /pair    {"code","client"}    → {"token"}
 *   POST /command {"command"}          → {"ok"}       (jeton requis)
 *   POST /text    {"text"}             → {"ok"}       (jeton requis)
 *   POST /launch  {"package"}          → {"ok"}       (jeton requis)
 */
class RemoteAccessibilityService : AccessibilityService() {

    private lateinit var pairing: Pairing
    private lateinit var executor: CommandExecutor
    private var server: HttpServer? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        pairing = Pairing(this)
        executor = CommandExecutor(this)
        server = HttpServer(PORT, ::route).also { it.start() }
        instance = this
        Log.i(TAG, "Agent actif")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Aucun traitement : on n'observe pas l'écran.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        server?.stop()
        server = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ routes

    private fun route(req: HttpRequest): HttpResponse = when ("${req.method} ${req.path}") {
        "GET /info" -> info()
        "POST /pair" -> pair(req)
        "POST /command" -> authorized(req) {
            val command = Json.parse(req.body).optString("command")
            if (onMain { executor.execute(command) }) {
                HttpResponse(200, Json.ok())
            } else {
                HttpResponse(422, Json.error("commande non disponible : $command"))
            }
        }
        "POST /text" -> authorized(req) {
            val text = Json.parse(req.body).optString("text").take(500)
            if (onMain { executor.typeText(text) }) {
                HttpResponse(200, Json.ok())
            } else {
                HttpResponse(422, Json.error("aucun champ de texte actif sur la TV"))
            }
        }
        "POST /launch" -> authorized(req) {
            val pkg = Json.parse(req.body).optString("package")
            if (onMain { executor.launch(pkg) }) {
                HttpResponse(200, Json.ok())
            } else {
                HttpResponse(422, Json.error("application introuvable : $pkg"))
            }
        }
        else -> HttpResponse(404, Json.error("route inconnue"))
    }

    private fun info(): HttpResponse {
        val json = JSONObject()
            .put("app", "tv_remote_agent")
            .put("protocol", PROTOCOL_VERSION)
            .put("name", Build.MODEL)
            .put("manufacturer", Build.MANUFACTURER)
            .put("android", Build.VERSION.SDK_INT)
            .put("paired", pairing.isPaired)
            .put("commands", JSONArray(executor.supported))
            .put("textInput", true)
        return HttpResponse(200, json.toString())
    }

    private fun pair(req: HttpRequest): HttpResponse {
        val body = Json.parse(req.body)
        val client = body.optString("client").ifBlank { req.remoteAddress }
        return when (val r = pairing.pair(body.optString("code"), client)) {
            is Pairing.PairResult.Success -> {
                Log.i(TAG, "Appairé avec $client (${req.remoteAddress})")
                HttpResponse(200, JSONObject().put("ok", true).put("token", r.token).toString())
            }
            Pairing.PairResult.WrongCode -> HttpResponse(403, Json.error("code incorrect"))
            is Pairing.PairResult.Locked ->
                HttpResponse(429, Json.error("trop d'essais, réessayez dans ${r.seconds} s"))
        }
    }

    private inline fun authorized(req: HttpRequest, block: () -> HttpResponse): HttpResponse {
        val token = req.headers["authorization"]?.removePrefix("Bearer")?.trim()
        return if (pairing.isValidToken(token)) block()
        else HttpResponse(401, Json.error("non appairé : envoyez le code affiché sur la TV"))
    }

    /**
     * Exécute [action] sur le fil principal (les API d'accessibilité y sont
     * prévues) et attend le résultat, au plus 2 s.
     */
    private fun onMain(action: () -> Boolean): Boolean {
        var result = false
        val latch = CountDownLatch(1)
        main.post {
            result = try { action() } catch (e: Exception) { Log.e(TAG, "Commande", e); false }
            latch.countDown()
        }
        return latch.await(2, TimeUnit.SECONDS) && result
    }

    // ----------------------------------------------- accès pour SetupActivity

    val pairingCode: String get() = pairing.code
    val isPaired: Boolean get() = pairing.isPaired
    val clientName: String? get() = pairing.clientName
    fun resetPairing() = pairing.reset()

    companion object {
        const val PORT = 8787
        const val PROTOCOL_VERSION = 1
        private const val TAG = "TvAgent"

        /** Instance active (null si le service n'est pas activé). */
        @Volatile
        var instance: RemoteAccessibilityService? = null
            private set
    }
}
