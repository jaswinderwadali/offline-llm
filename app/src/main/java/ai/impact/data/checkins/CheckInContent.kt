package ai.impact.data.checkins

import ai.impact.data.Persona
import ai.impact.data.PersonaCategory
import ai.impact.data.skills.BuiltInSkills
import ai.impact.data.skills.SkillAction
import ai.impact.data.skills.SkillRouter

/** Something the user was exploring, pulled from one of their messages. */
data class Topic(
    /** In the user's own words, for the prompt they'll send: "plan my day". */
    val raw: String,
    /** Addressed to the user, for the notification: "plan your day". */
    val display: String,
    val kind: Kind,
    val conversationId: Long = 0,
    val personaId: String = ""
) {
    enum class Kind {
        /** A thing to learn about: "black holes". */
        Subject,
        /** An open question, quoted as asked: "Why is the sky blue?" */
        Question,
        /** Something they want to do: "start running". */
        Task
    }
}

/** A message the user sent, as read back from chat history. */
data class SentMessage(val text: String, val conversationId: Long, val personaId: String, val createdAt: Long)

/**
 * Finds what a message was about, or null when it's small talk, a tool request ("remind me at
 * 6pm…") or too unclear to quote back. It's deliberately cautious: no notification is better
 * than one that garbles what the user said.
 */
object TopicExtractor {

    private fun rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    private val smallTalk = rx("^(?:hi|hey|hello|yo|thanks|thank you|thx|ok|okay|yes|yeah|no|nope|lol|haha|cool|nice|great|good (?:morning|night|evening)|bye|sure)\\b")
    private val politeLead = rx("^(?:please|hey|hi|hello|ok|okay|so|um|well|can you|could you|would you|will you|pls)[,!]?\\s+")
    private val politeTail = rx("[,\\s]*(?:please|pls|for me|to me|thanks|thank you|simply|in simple terms|briefly|in detail)\\s*$")
    private val article = rx("^(?:a|an)\\s+")

    private val subjectPatterns = listOf(
        rx("^(?:tell me|teach me|talk to me)\\s+(?:something|more|a bit|a little|everything)?\\s*about\\s+(.+)"),
        rx("^(?:something|more)\\s+about\\s+(.+)"),
        rx("^(?:explain|describe|summari[sz]e)(?:\\s+to\\s+me)?\\s+(.+)"),
        rx("^(?:what|who)(?:'s|\\s+is|\\s+are|\\s+was|\\s+were)\\s+(.+)"),
        rx("^(?:i\\s+want\\s+to|i'd\\s+like\\s+to|i\\s+would\\s+like\\s+to|help\\s+me|let\\s+me)\\s+(?:learn|know|understand)\\s+(?:more\\s+)?(?:about\\s+)?(.+)"),
        rx("^(?:quiz|test)\\s+me\\s+(?:on|about)\\s+(.+)"),
        rx("^(?:facts|history|info|information)\\s+(?:about|on|of)\\s+(.+)"),
        rx("^teach\\s+me\\s+(.+)"),
        rx("^(?:give|show|find)\\s+me\\s+(.+)"),
        rx("^(?:brainstorm|suggest|recommend)(?:\\s+(?:me|some))?\\s+(.+)")
    )
    private val taskPatterns = listOf(
        rx("^how\\s+(?:do|can|should|could)\\s+i\\s+(.+)"),
        rx("^how\\s+to\\s+(.+)"),
        rx("^(?:help\\s+me|i\\s+want\\s+to|i\\s+need\\s+to|i'd\\s+like\\s+to|i'm\\s+trying\\s+to|i\\s+am\\s+trying\\s+to)\\s+(.+)")
    )
    private val questionLead = rx("^(?:why|how|what|when|where|which|who|should|is|are|can|does|do|will|would|could)\\b")
    // Drafts and writing requests are about the text, not a topic worth coming back to.
    private val writingRequest = rx("^(?:write|draft|compose|rewrite|rephrase|translate|fix|correct|proofread)\\b")

    private val toolSkillIds = BuiltInSkills.all
        .filter { it.action != SkillAction.Chat || it.share != null }
        .map { it.id }
        .toSet()

    fun extract(message: String): Topic? {
        var t = message.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        t = t.replace(Regex("\\s+"), " ")
        if (t.length > 160) t = t.split(Regex("(?<=[.!?])\\s")).first()
        if (t.length > 140 || t.wordCount() < 2) return null
        if (smallTalk.containsMatchIn(t) && t.wordCount() <= 4) return null
        // Reminders, QR codes, pictures and posts already did their job.
        if (SkillRouter.detect(t, toolSkillIds) != null) return null

        var core = t
        while (true) {
            val next = politeLead.replaceFirst(core, "")
            if (next == core) break
            core = next
        }
        val asked = core.trimEnd().endsWith("?")
        core = core.trimEnd('?', '!', '.', ' ')
        core = politeTail.replace(core, "")
        if (writingRequest.containsMatchIn(core)) return null

        for (pattern in subjectPatterns) {
            val match = pattern.find(core) ?: continue
            return subject(match.groupValues[1])
        }
        for (pattern in taskPatterns) {
            val match = pattern.find(core) ?: continue
            return task(match.groupValues[1])
        }
        if ((asked || questionLead.containsMatchIn(core)) && core.wordCount() in 3..14) {
            val question = core.replaceFirstChar { it.uppercaseChar() } + "?"
            return Topic(question, question, Topic.Kind.Question)
        }
        return null
    }

    private fun subject(text: String): Topic? {
        val cleaned = politeTail.replace(article.replaceFirst(text.trim(), ""), "").trimEnd('?', '!', '.', ',', ' ')
        if (cleaned.wordCount() !in 1..7 || !cleaned.hasRealWord()) return null
        return Topic(cleaned, toYou(cleaned), Topic.Kind.Subject)
    }

    private fun task(text: String): Topic? {
        val cleaned = politeTail.replace(text.trim(), "").trimEnd('?', '!', '.', ',', ' ')
        if (cleaned.wordCount() !in 2..8 || !cleaned.hasRealWord()) return null
        return Topic(cleaned, toYou(cleaned), Topic.Kind.Task)
    }

    private val pronouns = listOf(
        rx("\\bi'm\\b") to "you're",
        rx("\\bi am\\b") to "you are",
        rx("\\bmyself\\b") to "yourself",
        rx("\\bmine\\b") to "yours",
        rx("\\bmy\\b") to "your",
        rx("\\bme\\b") to "you",
        rx("\\bi\\b") to "you"
    )

    /** "plan my day" → "plan your day", so the notification speaks to the user. */
    fun toYou(text: String): String = pronouns.fold(text) { acc, (pattern, replacement) -> pattern.replace(acc, replacement) }

    private fun String.wordCount() = split(' ').count { it.isNotBlank() }
    private fun String.hasRealWord() = split(' ').any { word -> word.count(Char::isLetter) >= 3 }
}

data class CheckInMessage(val title: String, val body: String, val prompt: String)

/**
 * Turns a topic into a short, warm notification and the prompt that's typed for the user when
 * they tap it. Nothing here claims work was already done: every line is an invitation, and the
 * reply is written only once they're back.
 */
object CheckInComposer {

    /** Chats with these personas can be personal, so they're never quoted on a notification. */
    fun isPrivate(persona: Persona): Boolean =
        persona.category == PersonaCategory.Wellbeing || persona.category == PersonaCategory.Social

    /**
     * The distinct topics in [messages] (newest first), skipping chats with private personas.
     * [personaFor] resolves a conversation's persona; null drops the message.
     */
    fun topics(messages: List<SentMessage>, personaFor: (String) -> Persona?): List<Topic> {
        val seen = HashSet<String>()
        return messages.mapNotNull { message ->
            val persona = personaFor(message.personaId) ?: return@mapNotNull null
            if (isPrivate(persona)) return@mapNotNull null
            val topic = TopicExtractor.extract(message.text) ?: return@mapNotNull null
            if (!seen.add(topic.raw.lowercase())) return@mapNotNull null
            topic.copy(conversationId = message.conversationId, personaId = message.personaId)
        }
    }

    private class Template(val title: String, val body: String, val prompt: String)

    private val subject = listOf(
        Template("Still curious about {t}?", "there's more to {t} than we covered. Tap and let's pick up right where you left off.", "Tell me more about {r}: something we haven't covered yet."),
        Template("A fresh angle on {t}", "ready for a surprising side of {t}? It only takes a minute.", "Tell me a surprising fact about {r}."),
        Template("Let's go deeper on {t}", "a quick deep dive could make {t} really click. Want to try?", "Give me a quick deep dive on {r}, then ask me one question to check I've got it.")
    )
    private val question = listOf(
        Template("Back to your question", "“{t}” has more to it than a quick answer. Tap to explore it together.", "Let's go deeper on my question: {r}"),
        Template("Still wondering?", "you asked “{t}” Tap for a follow-up that takes it further.", "Following up on \"{r}\": what's the most interesting thing to know next?"),
        Template("One more thought for you", "let's look at “{t}” from a completely new angle.", "Look at this from a different angle: {r}")
    )
    private val task = listOf(
        Template("Ready to {t}?", "let's make progress today. Tap and we'll take the first small step together.", "Help me {r}. Start with the first small step."),
        Template("Your next step to {t}", "small steps add up. Tap for one simple thing you can do in the next 10 minutes.", "What's one thing I can do in the next 10 minutes to {r}?"),
        Template("Still want to {t}?", "no pressure. Whenever you're ready, we'll pick it back up from where you left off.", "Let's pick up where we left off: help me {r}.")
    )
    private val caring = listOf(
        Template("Checking in on you", "how are you feeling today? {p} is here whenever you want to talk.", "Today I'm feeling "),
        Template("A small moment for you", "take a slow breath. {p} has a calm minute ready whenever you are.", "Can we take a calm minute together?"),
        Template("Thinking of you", "{p} saved your spot. Come back anytime, no pressure at all.", "Hi, I'm back. ")
    )
    private val general = listOf(
        Template("Got a minute?", "{p} has a fresh idea for you today. Tap to start a quick chat.", ""),
        Template("Something new to explore", "curious about something? {p} is ready for whatever's on your mind.", ""),
        Template("Your spot is saved", "{p} is ready to pick up wherever you like. Tap to say hi.", "")
    )

    /**
     * The notification for [topic], or a check-in in [persona]'s voice when there's no topic to
     * mention. [variant] rotates the wording so repeat notifications don't read the same.
     */
    fun compose(topic: Topic?, persona: Persona, userName: String?, variant: Int): CheckInMessage {
        val templates = when {
            topic == null -> if (isPrivate(persona)) caring else general
            topic.kind == Topic.Kind.Subject -> subject
            topic.kind == Topic.Kind.Question -> question
            else -> task
        }
        val template = templates[Math.floorMod(variant, templates.size)]
        val shown = topic?.display.orEmpty()
        // Long topics fit the body but would be cut off in the title.
        val titleTopic = if (shown.length > 38) shown.take(36).trimEnd() + "…" else shown
        fun fill(text: String, t: String) = text
            .replace("{t}", t)
            .replace("{r}", topic?.raw.orEmpty())
            .replace("{p}", persona.name)

        val title = "${persona.emoji} " + fill(template.title, titleTopic)
        val body = addressed(fill(template.body, shown), userName)
        val prompt = fill(template.prompt, shown).ifBlank { persona.starters.getOrNull(Math.floorMod(variant, maxOf(persona.starters.size, 1))).orEmpty() }
        return CheckInMessage(title, body, prompt)
    }

    /** "there's more…" becomes "Sam, there's more…", or "There's more…" with no name. */
    private fun addressed(body: String, userName: String?): String {
        val name = userName?.trim()?.takeIf { it.isNotEmpty() }
        return if (name != null) "$name, $body" else body.replaceFirstChar { it.uppercaseChar() }
    }
}
