package sn.tvremote.agent

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Écran affiché sur la TV : état du service, adresse IP, code d'appairage.
 * Rafraîchi chaque seconde (pour voir le service s'activer et l'appairage réussir).
 */
class SetupActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var serviceStatus: TextView
    private lateinit var address: TextView
    private lateinit var code: TextView
    private lateinit var pairStatus: TextView
    private lateinit var pairing: Pairing

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        serviceStatus = findViewById(R.id.serviceStatus)
        address = findViewById(R.id.address)
        code = findViewById(R.id.code)
        pairStatus = findViewById(R.id.pairStatus)
        // Même SharedPreferences que le service : le code s'affiche même avant activation
        pairing = Pairing(this)

        findViewById<Button>(R.id.openSettings).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                // Certains lanceurs de TV n'exposent pas cet écran directement
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
        findViewById<Button>(R.id.resetPairing).setOnClickListener {
            RemoteAccessibilityService.instance?.resetPairing() ?: pairing.reset()
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun refresh() {
        val service = RemoteAccessibilityService.instance
        serviceStatus.text = if (service != null) {
            "✅ Service actif"
        } else {
            "❌ Service inactif : Réglages → Accessibilité → « Télécommande Agent » → Activer"
        }

        val ip = localIpv4()
        address.text = if (ip != null) {
            "Adresse : $ip   (port ${RemoteAccessibilityService.PORT})"
        } else {
            "Adresse : TV non connectée au Wi-Fi"
        }

        code.text = (service?.pairingCode ?: pairing.code).chunked(3).joinToString(" ")

        val paired = service?.isPaired ?: pairing.isPaired
        val client = service?.clientName ?: pairing.clientName
        pairStatus.text = if (paired) {
            "Appairé avec : ${client ?: "un téléphone"}"
        } else {
            "Saisissez ce code dans l'application du téléphone."
        }
    }

    /** Première adresse IPv4 non locale (Wi-Fi ou Ethernet). */
    private fun localIpv4(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }
}
