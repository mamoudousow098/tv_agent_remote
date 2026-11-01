package sn.tvremote.agent

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
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

        findViewById<Button>(R.id.openAppInfo).setOnClickListener { openAndroidAppInfo() }
        findViewById<Button>(R.id.openAbout).setOnClickListener {
            openStockSettings(Settings.ACTION_DEVICE_INFO_SETTINGS)
        }
        findViewById<Button>(R.id.openDevOptions).setOnClickListener {
            openStockSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        }

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

    /**
     * Ouvre l'écran « Infos sur l'appli » des Réglages Android d'origine.
     *
     * La surcouche du fabricant affiche sa propre fenêtre, sans le menu ⋮ qui
     * contient « Autoriser les paramètres restreints » (Android 13+). On vise
     * donc explicitement l'application Réglages d'Android, puis, en repli,
     * n'importe quelle application qui gère cet intent.
     */
    private fun openAndroidAppInfo() {
        val uri = Uri.fromParts("package", packageName, null)
        val stock = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
            .setPackage("com.android.settings")
        val any = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
        for (intent in listOf(stock, any)) {
            try {
                startActivity(intent)
                return
            } catch (e: Exception) {
                // essai suivant
            }
        }
        Toast.makeText(this, "Écran d'infos de l'application introuvable", Toast.LENGTH_LONG).show()
    }

    /**
     * Ouvre un écran des Réglages Android d'origine (et non la surcouche du
     * fabricant), avec repli sur n'importe quelle application qui gère l'action.
     */
    private fun openStockSettings(action: String) {
        for (intent in listOf(Intent(action).setPackage("com.android.settings"), Intent(action))) {
            try {
                startActivity(intent)
                return
            } catch (e: Exception) {
                // essai suivant
            }
        }
        Toast.makeText(this, "Écran introuvable sur cette TV", Toast.LENGTH_LONG).show()
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
