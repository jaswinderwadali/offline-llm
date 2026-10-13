package ai.impact.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * A personality the assistant can take on. Built-in personas ship with the app;
 * custom personas are created by the user and persisted in [AppPreferences].
 */
data class Persona(
    val id: String,
    val name: String,
    val emoji: String,
    val tagline: String,
    val description: String,
    val systemPrompt: String,
    val colorStart: Long,
    val colorEnd: Long,
    val category: PersonaCategory = PersonaCategory.Custom,
    val starters: List<String> = emptyList(),
    val isCustom: Boolean = false
) {
    /** Persona prompt combined with app-wide guardrails that every persona follows. */
    fun fullSystemPrompt(userName: String?, wordLimit: Int): String = buildString {
        if (isCustom) append("You are $name, an AI companion with this personality: ")
        append(systemPrompt.trim())
        if (!userName.isNullOrBlank()) {
            append("\n\nThe user's name is ${userName.trim()}. Use it naturally, not in every message.")
        }
        append("\n\nStay in character as $name. Keep replies under $wordLimit words.")
        append(" Never claim to be human or a licensed professional.")
        append(" If the user mentions self-harm, suicide, or being in danger, respond with care and")
        append(" encourage them to contact local emergency services or a crisis helpline right away.")
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("emoji", emoji)
        put("tagline", tagline)
        put("description", description)
        put("systemPrompt", systemPrompt)
        put("colorStart", colorStart)
        put("colorEnd", colorEnd)
        put("starters", JSONArray(starters))
    }

    companion object {
        fun fromJson(json: JSONObject): Persona {
            val startersJson = json.optJSONArray("starters") ?: JSONArray()
            return Persona(
                id = json.getString("id"),
                name = json.getString("name"),
                emoji = json.optString("emoji", "✨"),
                tagline = json.optString("tagline"),
                description = json.optString("description"),
                systemPrompt = json.getString("systemPrompt"),
                colorStart = json.optLong("colorStart", 0xFF7C5CFF),
                colorEnd = json.optLong("colorEnd", 0xFFB36BFF),
                category = PersonaCategory.Custom,
                starters = List(startersJson.length()) { startersJson.getString(it) },
                isCustom = true
            )
        }
    }
}

enum class PersonaCategory(val label: String) {
    Wellbeing("Wellbeing"),
    Growth("Growth"),
    Social("Social"),
    Learning("Learning"),
    Creative("Creative"),
    Custom("Custom")
}

/** Palette offered when creating a custom persona: pairs of (start, end) gradient colors. */
val personaColorChoices: List<Pair<Long, Long>> = listOf(
    0xFF7C5CFF to 0xFFB36BFF,
    0xFFFF6B8B to 0xFFFF9A6B,
    0xFF2BC4A9 to 0xFF4F9DFF,
    0xFFFFB547 to 0xFFFF7A45,
    0xFF4F9DFF to 0xFF7C5CFF,
    0xFF34C759 to 0xFF2BC4A9,
    0xFFE85DE0 to 0xFF7C5CFF,
    0xFF5A6B8C to 0xFF8A9BBF
)

val personaEmojiChoices: List<String> = listOf(
    "✨", "🤖", "🦉", "🐶", "🦊", "🌙", "🔥", "🌊", "🎯", "🧩",
    "🎨", "🎧", "📚", "💡", "🧭", "🌻", "🛡️", "🚀", "🍀", "👑"
)
