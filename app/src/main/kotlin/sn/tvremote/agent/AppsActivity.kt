package sn.tvremote.agent

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Diagnostic : liste les applications installées sur la TV.
 *
 * But : identifier l'application de télécommande du fabricant (ex. le service
 * « rmservice » vu dans Accessibilité), pour retrouver l'appli téléphone
 * officielle qui parle au port 4321.
 *
 * Affiché en trois blocs : services d'accessibilité, applications
 * « télécommande » probables, puis toutes les autres.
 */
class AppsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }
        // ScrollView sélectionnable : défilement avec les flèches de la TV
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#101418"))
            isFocusable = true
            isFocusableInTouchMode = true
            addView(content)
        }
        setContentView(scroll)

        val pm = packageManager
        val packages = installedPackages(pm)

        title(content, "Applications installées : ${packages.size}")

        // 1. Services d'accessibilité (dont rmservice)
        title(content, "Services d'accessibilité")
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .map { it.id }
            .toSet()
        val services = am.installedAccessibilityServiceList
        if (services.isEmpty()) line(content, "(aucun)", MUTED)
        for (s in services) {
            val si = s.resolveInfo.serviceInfo
            val label = si.loadLabel(pm)
            val state = if (s.id in enabled) "ACTIVÉ" else "désactivé"
            line(content, "• $label  [$state]", ACCENT)
            line(content, "   ${si.packageName}/${si.name}", TEXT)
            s.loadDescription(pm)?.takeIf { it.isNotBlank() }?.let {
                line(content, "   « ${it.take(200)} »", MUTED)
            }
        }

        // 2. Applications « télécommande » probables
        val (likely, others) = packages.partition { isRemoteLike(it, pm) }
        title(content, "Télécommande / multi-écran probables : ${likely.size}")
        if (likely.isEmpty()) line(content, "(aucune)", MUTED)
        likely.forEach { describe(content, it, pm, highlight = true) }

        // 3. Toutes les autres
        title(content, "Autres applications : ${others.size}")
        others.forEach { describe(content, it, pm, highlight = false) }

        scroll.requestFocus()
    }

    @Suppress("DEPRECATION")
    private fun installedPackages(pm: PackageManager): List<PackageInfo> {
        val list = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        } else {
            pm.getInstalledPackages(0)
        }
        return list.sortedBy { it.packageName }
    }

    private fun isRemoteLike(p: PackageInfo, pm: PackageManager): Boolean {
        val text = (p.packageName + " " + label(p, pm)).lowercase()
        return KEYWORDS.any { it in text }
    }

    private fun describe(parent: LinearLayout, p: PackageInfo, pm: PackageManager, highlight: Boolean) {
        val app = p.applicationInfo
        val system = app != null && (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val kind = if (system) "système" else "installée"
        line(parent, "• ${label(p, pm)}  ($kind, v${p.versionName ?: "?"})", if (highlight) ACCENT else TEXT)
        line(parent, "   ${p.packageName}", MUTED)
    }

    private fun label(p: PackageInfo, pm: PackageManager): String =
        p.applicationInfo?.loadLabel(pm)?.toString() ?: p.packageName

    private fun title(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 32, 0, 8)
        })
    }

    private fun line(parent: LinearLayout, text: String, color: Int) {
        parent.addView(TextView(this).apply {
            this.text = text
            setTextColor(color)
            textSize = 17f
            setTextIsSelectable(false)
        })
    }

    private companion object {
        val ACCENT = Color.parseColor("#7CC4FF")
        val TEXT = Color.parseColor("#E6EDF3")
        val MUTED = Color.parseColor("#9FB3C8")

        /** Mots-clés repérant les applis de télécommande / partage d'écran. */
        val KEYWORDS = listOf(
            "remote", "rmservice", "rmserver", "control", "multiscreen", "multi_screen",
            "screen", "cast", "dlna", "airplay", "miracast", "mirror", "keyboard",
            "input", "assistant", "voice", "wukong", "eshare", "lebo", "hpplay",
            "tvremote", "tvhelper", "helper", "share",
        )
    }
}

