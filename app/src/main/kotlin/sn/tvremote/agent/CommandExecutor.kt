package sn.tvremote.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Exécute les commandes reçues du téléphone avec les API publiques d'Android.
 *
 * Noms des commandes = noms de l'enum TvCommand côté Flutter (ex. "volumeUp").
 *
 * Non disponibles via les API publiques (renvoient false) :
 *  power, channelUp/Down, digit0-9, source/sourceHdmiX.
 *  Android n'offre aucune action globale pour ces touches, et une appli non
 *  système ne peut pas injecter de KeyEvent arbitraire.
 */
class CommandExecutor(private val service: AccessibilityService) {

    private val audio = service.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Commandes réellement exécutables sur cette TV (exposées par GET /info). */
    val supported: List<String> = buildList {
        if (Build.VERSION.SDK_INT >= 33) addAll(listOf("up", "down", "left", "right", "ok"))
        addAll(
            listOf(
                "back", "home", "menu",
                "volumeUp", "volumeDown", "mute",
                "playPause", "play", "pause", "stop", "rewind", "fastForward",
            ),
        )
    }

    fun execute(command: String): Boolean = when (command) {
        // --- Navigation : actions globales D-pad (Android 13+, la TV est en Android 14) ---
        "up" -> dpad(AccessibilityService.GLOBAL_ACTION_DPAD_UP)
        "down" -> dpad(AccessibilityService.GLOBAL_ACTION_DPAD_DOWN)
        "left" -> dpad(AccessibilityService.GLOBAL_ACTION_DPAD_LEFT)
        "right" -> dpad(AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT)
        "ok" -> dpad(AccessibilityService.GLOBAL_ACTION_DPAD_CENTER)

        "back" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        "home" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        // Pas de touche « Menu » universelle : on ouvre les applis récentes
        "menu" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)

        // --- Son : AudioManager, aucune permission nécessaire ---
        "volumeUp" -> volume(AudioManager.ADJUST_RAISE)
        "volumeDown" -> volume(AudioManager.ADJUST_LOWER)
        "mute" -> volume(AudioManager.ADJUST_TOGGLE_MUTE)

        // --- Multimédia : touches envoyées à la session média active ---
        "playPause" -> media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        "play" -> media(KeyEvent.KEYCODE_MEDIA_PLAY)
        "pause" -> media(KeyEvent.KEYCODE_MEDIA_PAUSE)
        "stop" -> media(KeyEvent.KEYCODE_MEDIA_STOP)
        "rewind" -> media(KeyEvent.KEYCODE_MEDIA_REWIND)
        "fastForward" -> media(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)

        else -> false
    }

    /**
     * Écrit [text] dans le champ texte qui a le focus (recherche YouTube…).
     * Renvoie false si aucun champ éditable n'est actif.
     */
    fun typeText(text: String): Boolean {
        val node = service.rootInActiveWindow
            ?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return false
        if (!node.isEditable) return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Lance une application installée par son nom de paquet. */
    fun launch(packageName: String): Boolean {
        if (!PACKAGE_REGEX.matches(packageName)) return false
        val pm = service.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(packageName)
            ?: pm.getLaunchIntentForPackage(packageName)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            service.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun dpad(action: Int): Boolean =
        Build.VERSION.SDK_INT >= 33 && service.performGlobalAction(action)

    private fun volume(direction: Int): Boolean = try {
        // FLAG_SHOW_UI : la barre de volume s'affiche sur la TV (retour visuel)
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        true
    } catch (e: SecurityException) {
        false // mode Ne pas déranger, volume fixe imposé par le fabricant…
    }

    private fun media(keyCode: Int): Boolean {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        return true
    }

    private companion object {
        val PACKAGE_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
    }
}
