package ai.impact.data.memory

data class ExtractedMemory(val content: String, val category: MemoryCategory)

/** Finds long-lived facts about the user in something they wrote. */
fun interface MemoryExtractor {
    fun extract(userMessage: String): List<ExtractedMemory>
}

/**
 * Fast, offline, pattern-based extractor. It never runs the LLM, so it can't compete with the
 * chat for the single on-device inference engine. Only clear first-person statements of durable
 * facts are captured; feelings and one-off requests ("I'm tired", "I want to know…") are ignored.
 */
class HeuristicMemoryExtractor : MemoryExtractor {

    private data class Rule(
        val regex: Regex,
        val category: MemoryCategory,
        val format: (MatchResult) -> String?
    )

    private val relations =
        "wife|husband|partner|boyfriend|girlfriend|fianc[eé]e?|son|daughter|kid|child|mom|mother|mum|dad|father|" +
            "brother|sister|best friend|friend|boss|manager|dog|cat|puppy|kitten|pet|grandma|grandmother|grandpa|grandfather"

    private val transientStates = setOf(
        "tired", "sad", "happy", "bored", "sorry", "fine", "okay", "ok", "good", "bad", "sick", "stressed",
        "anxious", "worried", "confused", "stuck", "lost", "back", "here", "ready", "done", "not sure", "sure",
        "feeling", "going", "trying", "thinking", "wondering", "looking", "having", "just", "so", "very", "really",
        "bit", "little", "lot", "mess", "wreck", "failure", "loser", "burden", "disappointment", "idiot", "fraud",
        "joke", "nobody", "terrible", "horrible", "bad", "lazy", "useless", "worthless", "stupid"
    )

    // Objects that are feelings or self-talk rather than durable preferences ("I hate myself").
    private val ignoredPreferenceObjects = listOf(
        "to ", "that", "it", "you", "this", "when", "how", "what", "myself", "me", "my life", "life",
        "everything", "everyone", "everybody", "nothing", "being", "living", "the way"
    )

    private val rules = listOf(
        // Lead-in words match any case; the name itself must be capitalised to avoid "my name is not important".
        Rule(Regex("\\b(?i:my name is) ([A-Z][\\p{L}'-]+(?: [A-Z][\\p{L}'-]+)?)"), MemoryCategory.Personal) {
            "Their name is ${it.groupValues[1]}"
        },
        Rule(Regex("\\b(?i:call me|you can call me) ([A-Z][\\p{L}'-]+)"), MemoryCategory.Personal) {
            "Prefers to be called ${it.groupValues[1]}"
        },
        Rule(Regex("\\bi(?:'m| am) (\\d{1,2}) (?:years old|yrs old|y/o)", RegexOption.IGNORE_CASE), MemoryCategory.Personal) {
            "Is ${it.groupValues[1]} years old"
        },
        Rule(Regex("\\bi (?:live|am living|'m living) in ([^,.!?]{2,40})", RegexOption.IGNORE_CASE), MemoryCategory.Personal) {
            "Lives in ${it.groupValues[1].trim()}"
        },
        Rule(Regex("\\bi(?:'m| am) from ([^,.!?]{2,40})", RegexOption.IGNORE_CASE), MemoryCategory.Personal) {
            "Is from ${it.groupValues[1].trim()}"
        },
        Rule(Regex("\\bi work (as|at|for|in) ([^,.!?]{2,50})", RegexOption.IGNORE_CASE), MemoryCategory.Work) {
            "Works ${it.groupValues[1].lowercase()} ${it.groupValues[2].trim()}"
        },
        Rule(Regex("\\bi(?:'m| am) (?:studying|majoring in) ([^,.!?]{2,50})", RegexOption.IGNORE_CASE), MemoryCategory.Work) {
            "Is studying ${it.groupValues[1].trim()}"
        },
        Rule(Regex("\\bi(?:'m| am) (?:a|an) ([a-z][a-z -]{2,40}?)(?=[,.!?]| and | who | at | in |$)", RegexOption.IGNORE_CASE), MemoryCategory.Work) {
            val role = it.groupValues[1].trim()
            if (role.split(" ").first().lowercase() in transientStates) null else "Is a${if (role.first().lowercase() in "aeiou") "n" else ""} $role"
        },
        Rule(Regex("\\b(?i:my ($relations)(?:'s name)? is (?:called |named )?)([A-Z][\\p{L}'-]+)"), MemoryCategory.Relationships) {
            "Their ${it.groupValues[1].lowercase()} is named ${it.groupValues[2]}"
        },
        Rule(Regex("\\bi have (?:a|an|two|three|four|\\d+) ((?:little |baby |young |older |younger )?(?:$relations)s?|kids|children)\\b", RegexOption.IGNORE_CASE), MemoryCategory.Relationships) {
            "Has ${it.value.substringAfter("have ").trim()}"
        },
        Rule(Regex("\\bi(?:'m| am) (married|engaged|single|divorced|pregnant)\\b", RegexOption.IGNORE_CASE), MemoryCategory.Relationships) {
            "Is ${it.groupValues[1].lowercase()}"
        },
        Rule(Regex("\\bi (?:really )?(love|like|enjoy|adore|hate|dislike|can't stand) ([^,.!?]{3,50})", RegexOption.IGNORE_CASE), MemoryCategory.Preferences) {
            val verb = it.groupValues[1].lowercase()
            val thing = it.groupValues[2].trim()
            if (ignoredPreferenceObjects.any { prefix -> thing.lowercase().startsWith(prefix) }) null
            else "${verb.replaceFirstChar { c -> c.uppercase() }}${if (verb == "can't stand") "" else "s"} $thing"
        },
        Rule(Regex("\\bi prefer ([^,.!?]{3,50})", RegexOption.IGNORE_CASE), MemoryCategory.Preferences) {
            "Prefers ${it.groupValues[1].trim()}"
        },
        Rule(Regex("\\bi(?:'m| am) (vegetarian|vegan|pescatarian|lactose intolerant|gluten[- ]free)", RegexOption.IGNORE_CASE), MemoryCategory.Preferences) {
            "Is ${it.groupValues[1].lowercase()}"
        },
        Rule(Regex("\\bi(?:'m| am) allergic to ([^,.!?]{2,40})", RegexOption.IGNORE_CASE), MemoryCategory.Wellbeing) {
            "Is allergic to ${it.groupValues[1].trim()}"
        },
        Rule(Regex("\\bi (?:have|suffer from|was diagnosed with|have been diagnosed with|struggle with|deal with) (anxiety|depression|adhd|ocd|ptsd|insomnia|asthma|diabetes|migraines?|panic attacks|bipolar[^,.!?]{0,20}|chronic [^,.!?]{2,30})", RegexOption.IGNORE_CASE), MemoryCategory.Wellbeing) {
            "Deals with ${it.groupValues[1].trim()}"
        },
        Rule(Regex("\\bmy (?:main |big |biggest )?goal is (?:to )?([^.!?]{3,70})", RegexOption.IGNORE_CASE), MemoryCategory.Goals) {
            "Goal: ${it.groupValues[1].trim()}"
        },
        Rule(Regex("\\bi(?:'m| am) (?:trying|planning|working|hoping) to ([^.!?]{3,60})", RegexOption.IGNORE_CASE), MemoryCategory.Goals) {
            "Is working towards: ${it.groupValues[1].trim()}"
        },
        Rule(Regex("\\bi(?:'m| am) (?:training|preparing|studying) for (?:a |an |the |my )?([^.!?,]{3,50})", RegexOption.IGNORE_CASE), MemoryCategory.Goals) {
            "Is preparing for ${it.groupValues[1].trim()}"
        }
    )

    override fun extract(userMessage: String): List<ExtractedMemory> {
        if (userMessage.trim().endsWith("?") && userMessage.count { it == '.' } == 0) return emptyList() // pure questions
        val results = mutableListOf<ExtractedMemory>()
        for (rule in rules) {
            for (match in rule.regex.findAll(userMessage)) {
                val content = rule.format(match)?.let(::thirdPerson)?.trim()?.trimEnd(',', ' ') ?: continue
                if (content.length in 6..140 && results.none { it.content.equals(content, ignoreCase = true) }) {
                    results += ExtractedMemory(content, rule.category)
                }
            }
        }
        return results.take(3)
    }

    private fun thirdPerson(text: String): String = text
        .replace(Regex("\\bmy\\b", RegexOption.IGNORE_CASE), "their")
        .replace(Regex("\\bmyself\\b", RegexOption.IGNORE_CASE), "themselves")
        .replace(Regex("\\bme\\b", RegexOption.IGNORE_CASE), "them")
        .replace(Regex("\\bI(?:'m| am)\\b"), "they are")
        .replace(Regex("\\bI\\b"), "they")
}
