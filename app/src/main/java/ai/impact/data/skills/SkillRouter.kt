package ai.impact.data.skills

import java.util.TimeZone

/**
 * Picks an enabled skill for a message the user sent without choosing one, so "make a QR code
 * for example.com" or "remind me to call mom at 6pm" just work.
 *
 * It's rule-based on purpose. Small on-device models can't reliably choose a tool, asking them
 * would add a whole extra model pass to every message, and rules work with no model loaded.
 * Each rule needs a clear request, not just a keyword, so "how do QR codes work?" stays chat.
 */
object SkillRouter {

    private fun rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    /** Words that turn a mention into a request: "write a tweet", "send a WhatsApp". */
    private val compose = rx("\\b(write|draft|compose|create|make|send|text|message|post|share|caption|craft|prepare)\\b")
    private val create = rx("\\b(make|create|generate|give|get|turn|convert|build|need|want|show)\\b")

    private val qr = rx("\\bqr(?:[- ]?code)?s?\\b|\\bbarcode\\b")
    private val qrDirect = rx("^\\s*qr(?:[- ]?code)?\\b|\\bqr(?:[- ]?code)?\\s+(?:for|of|with|to|that|containing)\\b")
    private val whatsapp = rx("\\bwhats\\s?app\\b")
    private val instagram = rx("\\binsta(?:gram)?\\b|\\big\\s+(?:caption|post|story)\\b")
    private val tweet = rx("\\btweet\\b")
    private val xPost = rx("\\btwitter\\b|\\bx\\s+post\\b|\\bpost\\s+(?:it\\s+)?(?:on|to|for)\\s+x\\b|\\bfor\\s+x\\b")
    private val linkedin = rx("\\blinked\\s?in\\b")
    private val notify = rx("\\b(?:notify|ping|alert|nudge|buzz)\\s+me\\b|\\bnotification\\b")
    private val remind = rx("\\bremind\\s+me\\b|\\breminder\\b")
    private val calendar = rx("\\bcalendar\\b|\\bschedule\\s+(?:a|an|my)?\\s*(?:meeting|call|event|appointment)\\b|\\b(?:add|put|save)\\b.*\\b(?:event|appointment)\\b")
    private val learn = rx("\\bteach\\s+me\\b|\\b(?:i\\s+want\\s+to|help\\s+me|let\\s+me)\\s+learn\\b|\\blearn\\s+something\\b|\\bfun\\s+fact\\b|\\b(?:a|quick|short|mini)\\s+lesson\\b")
    // "create a picture of…", "make me an image with…", or starting with "draw"/"paint".
    private val pictureNouns = "image|picture|pic|photo|drawing|painting|illustration|artwork|art|wallpaper|portrait|logo|poster|sticker|sketch"
    private val imageAsk = rx("\\b(?:create|make|generate|draw|paint|render|design|imagine|produce|show\\s+me)\\b[^.?!]{0,40}?\\b(?:$pictureNouns)s?\\b")
    private val drawFirst = rx("^\\s*(?:please\\s+)?(?:(?:can|could|would)\\s+you\\s+)?(?:draw|paint|sketch|illustrate)\\b")
    // Questions about pictures ("how do I make a photo smaller?") aren't requests for one.
    private val question = rx("^\\s*(?:how|why|what|where|when|which|should|do|does|is|are|can\\s+i|could\\s+i)\\b")
    // Make/create are picture verbs here, so only the other compose verbs mark a text request.
    private val textCompose = rx("\\b(?:write|draft|compose|send|text|message|post|share|caption|craft|prepare)\\b")
    private val spin = rx("\\b(?:rewrite|rephrase|paraphrase|reword)\\b|\\bspin\\s+(?:this|the\\s+text|it)\\b|\\bsay\\s+(?:this|it)\\s+(?:differently|another\\s+way)\\b")

    /**
     * The enabled skill this message is clearly asking for, or null to treat it as plain chat.
     * When several could apply, the more specific one wins (a WhatsApp request mentioning
     * "remind" is still a WhatsApp message).
     */
    fun detect(
        text: String,
        enabledIds: Set<String>,
        now: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault()
    ): Skill? {
        val t = text.trim()
        if (t.isEmpty() || t.startsWith("/")) return null
        fun on(id: String) = id in enabledIds
        val hasTime by lazy { TimeParser.parse(t, now, zone) != null }

        val id = when {
            on("qr_code") && qr.containsMatchIn(t) && (create.containsMatchIn(t) || qrDirect.containsMatchIn(t)) -> "qr_code"
            on("image") && !question.containsMatchIn(t) && (imageAsk.containsMatchIn(t) || drawFirst.containsMatchIn(t)) &&
                !textCompose.containsMatchIn(t) -> "image"
            on("whatsapp") && whatsapp.containsMatchIn(t) && compose.containsMatchIn(t) -> "whatsapp"
            on("instagram") && instagram.containsMatchIn(t) && compose.containsMatchIn(t) -> "instagram"
            on("x_post") && (tweet.containsMatchIn(t) || (xPost.containsMatchIn(t) && compose.containsMatchIn(t))) -> "x_post"
            on("linkedin") && linkedin.containsMatchIn(t) && compose.containsMatchIn(t) -> "linkedin"
            // Reminders need a time; without one it's just talk about reminders.
            (remind.containsMatchIn(t) || notify.containsMatchIn(t) || calendar.containsMatchIn(t)) && hasTime ->
                reminderSkill(t, ::on)
            on("text_spinner") && spin.containsMatchIn(t) -> "text_spinner"
            on("learn") && learn.containsMatchIn(t) -> "learn"
            else -> null
        }
        return BuiltInSkills.byId(id)
    }

    /** Calendar words mean a calendar reminder; "notify/ping me" or a plain "remind me" means a notification. */
    private fun reminderSkill(t: String, on: (String) -> Boolean): String? {
        val wantsCalendar = calendar.containsMatchIn(t)
        val wantsPing = notify.containsMatchIn(t)
        return when {
            wantsCalendar && on("reminder") -> "reminder"
            wantsPing && on("notify") -> "notify"
            on("notify") -> "notify"
            on("reminder") -> "reminder"
            else -> null
        }
    }

    private val url = rx("\\b(?:https?://|www\\.)\\S+|\\b[\\w-]+(?:\\.[\\w-]+)+\\.?(?:/\\S*)?")
    private val qrLead = rx("^.*?\\bqr(?:[- ]?code)?s?\\b\\s*(?:for|of|with|to|that\\s+says|containing|saying)?\\s*:?\\s*")

    /** What an auto-detected QR request should encode: its link, or whatever follows "QR code for". */
    fun qrContent(text: String): String {
        url.find(text)?.let { return it.value.trimEnd('.', ',', '!', '?', ')') }
        val rest = qrLead.replaceFirst(text.trim(), "").trim().trimEnd('.', '!', '?')
        return rest.ifBlank { text.trim() }
    }

    private val genericPicture = setOf("image", "picture", "pic", "photo", "drawing", "painting", "illustration", "artwork", "art")
    private val pictureLead = rx("^.*?\\b($pictureNouns)s?\\b\\s*(?:of|showing|with|that\\s+shows|depicting|featuring|about)?\\s*:?\\s*")
    private val drawLead = rx("^\\s*(?:please\\s+)?(?:(?:can|could|would)\\s+you\\s+)?(?:draw|paint|sketch|illustrate)\\s+(?:me\\s+)?")
    private val trailingPlease = rx("\\s*\\bplease\\b\\s*$")

    /**
     * What to paint for an auto-detected image request: "create a picture of a red fox" paints
     * "a red fox"; "design a logo for my bakery" keeps the kind of picture, "logo for my bakery".
     */
    fun imagePrompt(text: String): String {
        val t = text.trim()
        val prompt = pictureLead.find(t)?.let { match ->
            val noun = match.groups[1]!!
            // A generic word ("picture of") adds nothing; a specific one ("portrait of") is part of the subject.
            if (noun.value.lowercase() in genericPicture) t.substring(match.range.last + 1) else t.substring(noun.range.first)
        } ?: drawLead.replaceFirst(t, "")
        val cleaned = trailingPlease.replace(prompt, "").trim().trimEnd('.', '!', '?', ',')
        return cleaned.ifBlank { t }
    }

    private val bodyLead = rx("\\b(?:saying|that\\s+says|telling\\s+(?:him|her|them)|to\\s+say|that)\\b\\s*:?\\s*|:\\s*")

    /**
     * The message itself from a request like "send a WhatsApp to mom saying I'm late", for when
     * there's no model to draft it and the text goes to the app as it is.
     */
    fun messageBody(text: String): String {
        val match = bodyLead.find(text) ?: return text.trim()
        val rest = text.substring(match.range.last + 1).trim()
        return if (rest.isBlank()) text.trim() else rest.replaceFirstChar { it.uppercaseChar() }
    }
}
