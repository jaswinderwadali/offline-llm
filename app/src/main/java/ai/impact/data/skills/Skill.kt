package ai.impact.data.skills

import java.net.URLDecoder
import java.net.URLEncoder

/** How a skill turns the user's message into a reply. */
enum class SkillAction {
    /** The chat model answers, guided by the skill's instruction. */
    Chat,
    /** Encodes the message as a QR code. */
    QrCode,
    /** Adds the message to the calendar at the time it mentions. */
    Reminder,
    /** Posts a notification from this app at the time the message mentions. */
    Notification,
    /** Paints the message as a picture with the on-device image model. */
    Image
}

/** An app a drafted message can be sent straight to. */
enum class ShareTarget(val label: String, val packages: List<String>) {
    WhatsApp("WhatsApp", listOf("com.whatsapp", "com.whatsapp.w4b")),
    Instagram("Instagram", listOf("com.instagram.android")),
    X("X", listOf("com.twitter.android")),
    LinkedIn("LinkedIn", listOf("com.linkedin.android"));

    /**
     * A web link that opens the app (or its website) with the text filled in, for when no app
     * package answers. Null when the service has no such link.
     */
    fun webUrl(text: String, phone: String?): String? {
        val encoded = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
        return when (this) {
            WhatsApp -> "https://wa.me/${phone.orEmpty()}?text=$encoded"
            X -> "https://x.com/intent/post?text=$encoded"
            LinkedIn -> "https://www.linkedin.com/feed/?shareActive=true&text=$encoded"
            Instagram -> null
        }
    }
}

object PhoneNumbers {
    private val pattern = Regex("""(?<![\w+])\+?\d[\d\s().-]{6,18}\d(?!\w)""")

    /**
     * The first phone number in [text] as digits only (country code included when given), so a
     * WhatsApp message can go straight to that chat. Null when there isn't one.
     */
    fun find(text: String): String? = pattern.findAll(text)
        .map { it.value.filter(Char::isDigit) }
        .firstOrNull { it.length in 8..15 }
}

/**
 * Something the assistant can do beyond chatting. The user picks one for a message from the
 * input field; enabled skills stay one tap away.
 */
data class Skill(
    val id: String,
    val name: String,
    val emoji: String,
    val description: String,
    val placeholder: String,
    val action: SkillAction,
    val colorStart: Long,
    val colorEnd: Long,
    /** Extra system instruction for [SkillAction.Chat] skills. */
    val instruction: String = "",
    /** Sent when the user sends without typing anything; null means the skill needs input. */
    val emptyPrompt: String? = null,
    val share: ShareTarget? = null,
    val enabledByDefault: Boolean = false
) {
    /**
     * Whether the skill can only run with a chat model loaded. Sharing skills still work without
     * one: the typed text goes to the app as it is, undrafted.
     */
    val needsModel: Boolean get() = action == SkillAction.Chat && share == null

    /** Replies that aren't conversation the model should see again (a QR code, a scheduled ping). */
    val isTool: Boolean get() = action == SkillAction.QrCode || action == SkillAction.Reminder || action == SkillAction.Notification
}

object BuiltInSkills {

    private const val ONLY_TEXT = "Reply with only the finished text, no preamble, options or explanation."

    val all: List<Skill> = listOf(
        Skill(
            id = "image",
            name = "Create an image",
            emoji = "🎨",
            description = "Paints a picture on your phone with your image model. Just ask: \"draw a cat in space\".",
            placeholder = "Describe the picture to create…",
            action = SkillAction.Image,
            colorStart = 0xFFE85DE0, colorEnd = 0xFFFFB547,
            enabledByDefault = true
        ),
        Skill(
            id = "reminder",
            name = "Schedule reminder",
            emoji = "📅",
            description = "Adds a reminder to your calendar at the time you mention.",
            placeholder = "Call mom tomorrow at 6pm",
            action = SkillAction.Reminder,
            colorStart = 0xFF4F9DFF, colorEnd = 0xFF2BC4A9,
            enabledByDefault = true
        ),
        Skill(
            id = "notify",
            name = "Schedule a notification",
            emoji = "⏰",
            description = "TinyAI pings you with a notification when it's time.",
            placeholder = "Stretch in 30 minutes",
            action = SkillAction.Notification,
            colorStart = 0xFFFFB547, colorEnd = 0xFFFF7A45,
            enabledByDefault = true
        ),
        Skill(
            id = "qr_code",
            name = "Generate QR code",
            emoji = "🔳",
            description = "Turns a link, Wi-Fi details or any text into a QR code you can share.",
            placeholder = "Link or text for the QR code",
            action = SkillAction.QrCode,
            colorStart = 0xFF34C759, colorEnd = 0xFF2BC4A9,
            enabledByDefault = true
        ),
        Skill(
            id = "learn",
            name = "Learn something new",
            emoji = "💡",
            description = "A bite-sized lesson on any topic, or a surprise one, with a quick quiz.",
            placeholder = "Pick a topic, or just tap send",
            action = SkillAction.Chat,
            colorStart = 0xFFE85DE0, colorEnd = 0xFF7C5CFF,
            instruction = "Skill: Learn something new. Teach one bite-sized, accurate lesson about the topic the user gives " +
                "(if they give none, pick a surprising topic from science, history, nature or culture). Use a catchy " +
                "title, three to five short points, one fun fact, then end with a single quiz question.",
            emptyPrompt = "Teach me something new and surprising.",
            enabledByDefault = true
        ),
        Skill(
            id = "text_spinner",
            name = "Text spinner",
            emoji = "🌀",
            description = "Rewrites your text three fresh ways while keeping the meaning.",
            placeholder = "Text to rewrite",
            action = SkillAction.Chat,
            colorStart = 0xFF7C5CFF, colorEnd = 0xFF4F9DFF,
            instruction = "Skill: Text spinner. Rewrite the user's text in three distinct versions that keep its meaning: " +
                "1. clearer, 2. more casual, 3. more formal. Number them and add nothing else."
        ),
        Skill(
            id = "whatsapp",
            name = "WhatsApp message",
            emoji = "💬",
            description = "Drafts a friendly message and opens WhatsApp to send it. Add a number to go straight to that chat.",
            placeholder = "e.g. Tell Sam I'm running late, +1 555 010 2030",
            action = SkillAction.Chat,
            colorStart = 0xFF34C759, colorEnd = 0xFF2BA84A,
            instruction = "Skill: Draft a WhatsApp message. Write it as a warm, natural chat message the user can send " +
                "as is, in the first person, short, with an emoji or two where it fits. The request may say who it's " +
                "for or give their phone number: address them by name if given, and never put the phone number in " +
                "the message. $ONLY_TEXT",
            share = ShareTarget.WhatsApp
        ),
        Skill(
            id = "instagram",
            name = "Instagram caption",
            emoji = "📸",
            description = "Writes a catchy caption with hashtags for your post.",
            placeholder = "What's the post about?",
            action = SkillAction.Chat,
            colorStart = 0xFFFF6B8B, colorEnd = 0xFFFFB547,
            instruction = "Skill: Write an Instagram caption. Start with a hook, keep it to two or three short lines with " +
                "emojis, then a blank line and five to eight relevant hashtags. $ONLY_TEXT",
            share = ShareTarget.Instagram
        ),
        Skill(
            id = "x_post",
            name = "X post",
            emoji = "𝕏",
            description = "Crafts a punchy post that fits in 280 characters.",
            placeholder = "What do you want to post?",
            action = SkillAction.Chat,
            colorStart = 0xFF2B2B2B, colorEnd = 0xFF5A6B8C,
            instruction = "Skill: Write a post for X (Twitter). Make it punchy and engaging, under 280 characters " +
                "including at most two hashtags. $ONLY_TEXT",
            share = ShareTarget.X
        ),
        Skill(
            id = "linkedin",
            name = "LinkedIn post",
            emoji = "💼",
            description = "Shapes your update into a professional LinkedIn post.",
            placeholder = "What's your update?",
            action = SkillAction.Chat,
            colorStart = 0xFF4F9DFF, colorEnd = 0xFF2B6BD6,
            instruction = "Skill: Write a LinkedIn post. Open with a strong first line, use short paragraphs, keep a " +
                "professional but human tone, and finish with a question or call to action and three hashtags. $ONLY_TEXT",
            share = ShareTarget.LinkedIn
        )
    )

    val defaultEnabledIds: Set<String> = all.filter { it.enabledByDefault }.map { it.id }.toSet()

    fun byId(id: String?): Skill? = id?.let { wanted -> all.firstOrNull { it.id == wanted } }
}

/**
 * Chat history stores plain text, so a message's skill travels as a small header on its text:
 * `[[skill:<id>|<url-encoded arg>]]` followed by a newline and the message itself.
 */
object SkillTag {

    data class Decoded(val skillId: String?, val arg: String?, val text: String)

    private val pattern = Regex("""^\[\[skill:([a-z0-9_]+)(?:\|([^\]]*))?]]\n?""")

    fun encode(skillId: String?, arg: String?, text: String): String {
        if (skillId == null) return text
        val argPart = arg?.let { "|" + URLEncoder.encode(it, "UTF-8") }.orEmpty()
        return "[[skill:$skillId$argPart]]\n$text"
    }

    fun decode(stored: String): Decoded {
        val match = pattern.find(stored) ?: return Decoded(null, null, stored)
        val arg = match.groupValues[2].takeIf { it.isNotEmpty() }?.let {
            runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull()
        }
        return Decoded(match.groupValues[1], arg, stored.substring(match.range.last + 1))
    }
}
