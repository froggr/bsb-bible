package ca.lampandlight

/**
 * The English voices Phonon can read in.
 *
 * Phonon finds voices in a `voices/` folder beside the weights, and names each one after its
 * file — `voices/Micah.safetensors` becomes "Micah". They are not in the model repository;
 * they live in `gradium/phonon-voices` under their opaque ids, so the name is ours to give.
 *
 * Each is a quarter of a megabyte, so the whole set is about 6 MB: small enough to offer
 * together, large enough not to ship in the APK for a reader who never turns narration on.
 *
 * Names, ids, ages and descriptions come from the repository's own `voices.jsonl`
 * (Apache 2.0). The descriptions are Gradium's words, not ours.
 */
object Voices {

    /**
     * One voice.
     *
     * [steady] marks the ones worth trying first for a long reading. Gradium publishes no
     * narration guidance at all, but it does publish an age for each voice, and that age
     * predicts the prose: of the ten Middle-age voices the descriptions use calm words
     * ("slow, patient delivery", "settled, even-toned", "easy, relaxed") nine times against
     * one energetic; across every younger group it is one against nineteen. These voices
     * were written for assistants and support lines — several descriptions say so — and
     * "cuts through a music bed" is close to the opposite of what an hour of reading wants.
     */
    data class Voice(
        val name: String,
        val id: String,
        val accent: String,
        val male: Boolean,
        val age: String,
        val steady: Boolean,
        val about: String,
    )

    const val BASE = "https://huggingface.co/gradium/phonon-voices/resolve/main/voices/"

    /** Where they go, relative to the model folder. Phonon looks here and nowhere else. */
    const val DIR = "voices"

    /** Steadiest first, so the ones suited to a long reading are the ones in reach. */
    val ALL = listOf(
        Voice("Edwin", "zUCxzrOrugTSis03", "British", true, "Middle-age", true,
            "Efficient and firm, sincere in attention, with tidy articulation that holds up at phone bandwidth."),
        Voice("Hugh", "YyKcxPG7iIgS3jsK", "British", true, "Middle-age", true,
            "Soft and close, airy in texture, with lively pitch movement and an attentive, sincere delivery."),
        Voice("Spencer", "JOW0vchyswkQUMDS", "British", true, "Middle-age", true,
            "Light and airy, sitting a touch higher, with crisp consonants and a slow, patient delivery."),
        Voice("Bryony", "gfP44RlHwr2kEwk7", "British", false, "Middle-age", true,
            "Full, rich mid-range tones, softly relaxed and spoken close to the mic, moving briskly with light pitch play."),
        Voice("Preston", "qIxlZ7Ei8KvWlyQH", "American", true, "Middle-age", true,
            "Trim, focused delivery with clean edges on every consonant and a steady, low-set core that carries through phone bandwidth."),
        Voice("Trevor", "0zLApsZfVLLNaEGl", "American", true, "Middle-age", true,
            "Buoyant energy over a resonant mid-low core, phrases landing softly and consonants cutting through a handset."),
        Voice("Delia", "nGzMHMPAWGaJFHOo", "American", false, "Middle-age", true,
            "Low, grounded and quick to the point, with a sincerity that keeps an apology honest."),
        Voice("Everly", "vjFyp2BcCyTlUmYb", "American", false, "Middle-age", true,
            "Rounded, soft-edged tone with gentle lift at the end of phrases and an easy, relaxed delivery."),
        Voice("Hadley", "bXwrkHhURL1VFJdG", "American", false, "Middle-age", true,
            "Settled, even-toned and genuinely kind, with crisp consonants that hold up over a handset."),
        Voice("Toby", "dME3IWyZBvmh1n1q", "British", true, "Adult", false,
            "A sparky, attentive British English adult voice that gets to the point with a smile. Ideal for fast, efficient customer support and helpful voice assistants."),
        Voice("Imogen", "gDT1nz7Ie36ZhL-C", "British", false, "Adult", false,
            "A bubbly, friendly British English adult voice that puts callers instantly at ease. Great for onboarding assistants and warm concierge interactions."),
        Voice("Maeve", "6PWnV0Nq4wu7RVBT", "British", false, "Adult", false,
            "A sparky, attentive British English adult voice that gets to the point with a smile. Ideal for fast, efficient customer support and helpful voice assistants."),
        Voice("Pippa", "uem82D50GRv2Dwma", "British", false, "Adult", false,
            "A confident, upbeat British English adult voice with infectious energy. Perfect for proactive assistants and motivating customer success calls."),
        Voice("Russell", "_6Aslh2DxfmnRLmP", "American", true, "Adult", false,
            "A high-energy American adult voice that pushes and encourages with the intensity of someone who genuinely believes in you."),
        Voice("Harper", "4SZHfMpw-p46Ywgs", "American", false, "Adult", false,
            "Modern, confident and friendly voice with a standard American accent."),
        Voice("Zoey", "NbpkqMVS3CJeq2j8", "American", false, "Adult", false,
            "Playful, upbeat and Gen Z energy voice with a standard American accent. Perfect for engaging conversations!"),
        Voice("Alfie", "X6NceqF285Gr04gb", "British", true, "Young Adult", false,
            "Punchy and forward, with crisp consonant attack and a pitch that lifts across a line, sitting high over a music bed."),
        Voice("Teddy", "Zn7g6NxLqXlJb7Qi", "British", true, "Young Adult", false,
            "Sparky and close in, quick off the mark, with firm consonants and the range to drop right down for a clause then come back up."),
        Voice("Lottie", "m9MxpY6IUGXtWp76", "British", false, "Young Adult", false,
            "Sparky and to the point, high forward energy with clean edges on the consonants and the range to drop low for one line then climb back."),
        Voice("Easton", "LMDIYE0Oa377Qh6Z", "American", true, "Young Adult", false,
            "Punchy and close in, with crisp consonant attack that cuts through a music bed and pitch that lifts across each line."),
        Voice("Micah", "40QrMVgAxFiUnP9f", "American", true, "Young Adult", false,
            "Buoyant and high in energy, with a playful lift at the ends of phrases and clean attack that survives a loud mix."),
        Voice("Miles", "d9XCyhjo4JhkHF1d", "American", true, "Young Adult", false,
            "Resonant and decisive, with genuine dynamic range, dropping right down for one clause then driving back up."),
        Voice("Juniper", "NIWIOv5vTi5eRMj8", "American", false, "Young Adult", false,
            "Cheerful presence with real resonance under it, personable when the copy turns intimate and decisive on the final line."),
        Voice("Elodie-Rose", "y9bQqIDnyxwqK01t", "British", false, "Young", false,
            "A young British voice with sharp, enthusiastic energy and crisp articulation, sparky and to the point for customer support and care."),
        Voice("Freya", "GgfEkEJtxZR7gnpy", "British", false, "Young", false,
            "A young British voice, glossy and confident with fast, girly chatter and bubbly sparkle, perfect for a friendly receptionist or assistant."),
    )

    fun byName(name: String): Voice? = ALL.firstOrNull { it.name.equals(name, true) }

    /** The short line under a name in the picker. */
    fun describe(name: String): String? = byName(name)?.let {
        "${it.accent} \u00b7 ${if (it.male) "male" else "female"}"
    }

    /** Gradium's own description, for the voice a reader is weighing up. */
    fun about(name: String): String? = byName(name)?.about
}
