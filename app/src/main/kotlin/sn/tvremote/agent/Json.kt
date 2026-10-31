package sn.tvremote.agent

import org.json.JSONObject

/** Petits utilitaires JSON (org.json est fourni par Android). */
object Json {
    fun ok(): String = JSONObject().put("ok", true).toString()

    fun error(message: String): String =
        JSONObject().put("ok", false).put("error", message).toString()

    /** Analyse tolérante : un corps vide ou invalide donne un objet vide. */
    fun parse(body: String): JSONObject =
        try {
            if (body.isBlank()) JSONObject() else JSONObject(body)
        } catch (e: Exception) {
            JSONObject()
        }
}
