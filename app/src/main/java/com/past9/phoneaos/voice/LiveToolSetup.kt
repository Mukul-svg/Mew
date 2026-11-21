package com.past9.phoneaos.voice

import org.json.JSONArray

/**
 * The tool declarations a Live setup sends for a given model.
 *
 * Gemini 3.8 Live supports both blocking and asynchronous function calls, and some clients report it treating
 * undeclared tools as asynchronous. Mew's hand-off returns a short "sent" acknowledgement and the agent's real answer
 * arrives later as a client turn, so the conversation should wait for that acknowledgement exactly as it did on
 * 3.1 Flash Live. Marking the declarations BLOCKING keeps that behaviour explicit. Older models do not accept the
 * field, so they get the declarations unchanged.
 */
object LiveToolSetup {
    /** The model id without a `models/` prefix. */
    private fun name(model: String): String = model.substringAfterLast('/')

    fun forModel(model: String, tools: JSONArray?): JSONArray? {
        val original = tools ?: return null
        if (name(model) != "gemini-3.8-live") return original
        // Deep copy: the same declaration array is shared by every session and must not gain a model-specific field.
        val copy = JSONArray(original.toString())
        for (i in 0 until copy.length()) {
            val group = copy.optJSONObject(i) ?: continue
            val declarations = group.optJSONArray("functionDeclarations") ?: continue
            for (j in 0 until declarations.length()) declarations.optJSONObject(j)?.put("behavior", "BLOCKING")
        }
        return copy
    }
}
