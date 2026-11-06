package ca.lampandlight

/**
 * The voices the app reads in.
 *
 * Phonon finds voices in a `voices/` folder beside the weights, and names each one after its
 * file — `voices/Silas.safetensors` becomes "Silas". They are not in the model repository;
 * they live in `gradium/phonon-voices` under opaque ids, and the name is ours to give: the
 * repository's own names are only in its README (Apache 2.0, nothing binds them), and "Grub
 * the Ogre" is not a name for a Bible to read in.
 *
 * Two kinds. The READERS are Gradium's catalogue voices, written for assistants and support
 * lines; of the twenty-five English ones only two are kept, the two that hold up over an
 * hour of reading. The CHARACTERS are the twelve English voices Gradium made with Voice
 * Design — a sea captain, an ancient dragon, a snow queen — and heard reading scripture they
 * are, by a distance, the better narrators: they have a chest, a pace and a point of view,
 * where the catalogue voices have a smile. Joe, having heard all of them, 2026-11-06.
 *
 * Each is a quarter of a megabyte, so the whole set is about 3.5 MB.
 */
object Voices {

    /**
     * One voice.
     *
     * [tempo] is how much faster than Phonon's own output the voice is played, pitch kept.
     * It is 1 for every voice: the characters were designed to speak slowly and the first
     * cut gave each a tempo derived from its duration on the same text (Methuselah 1.25,
     * the radio's figure), and Joe found him "way too much" sped up — the app has a Pace
     * control, and that is where speed belongs, chosen by the listener, not baked into the
     * voice where it multiplies with Pace. Kept as a field so a voice can be corrected if
     * one ever needs it.
     *
     * [warmth] is the sampling temperature, how much the voice is allowed to move. The
     * runtime's default of 0.3 is steady and a little flat; 0.6 gives a reading voice its
     * rise and fall, and the deepest character is wanted at 0.9, where his pitch spread
     * went from 11 to 17 semitones and the reading stopped being a drone.
     */
    data class Voice(
        val name: String,
        val id: String,
        val male: Boolean,
        /** The short line under the name in the picker. */
        val detail: String,
        val tempo: Float = 1f,
        val warmth: Float = 0.6f,
        /** A Voice Design character rather than a catalogue voice. */
        val character: Boolean = false,
    )

    const val BASE = "https://huggingface.co/gradium/phonon-voices/resolve/main/voices/"

    /** Where they go, relative to the model folder. Phonon looks here and nowhere else. */
    const val DIR = "voices"

    /** The default reader, and what a saved choice that no longer exists falls back to. */
    const val DEFAULT = "Spencer"

    val ALL = listOf(
        // ---- readers: the two catalogue voices worth an hour
        Voice("Spencer", "JOW0vchyswkQUMDS", true, "British · light, patient"),
        Voice("Hadley", "bXwrkHhURL1VFJdG", false, "American · settled, kind"),

        // ---- characters, renamed. The repository's name is in the comment.
        Voice("Methuselah", "xO8xQo3BrB8FuI6J", true, "ancient, very deep, unhurried",     // Vyrmathrax the Ancient
            warmth = 0.9f, character = true),
        Voice("Aldric", "gSgG5Tor0GPEnqzC", true, "a master's measured authority",         // Master Aldric
            character = true),
        Voice("Silas", "xPDpsV3F592srkjA", true, "a sea captain, weathered and sure",       // Captain Silas Blackwater
            character = true),
        Voice("Bramwell", "y2eiKxtQo2B67deU", true, "a knight, courtly and warm",           // Sir Bramwell
            character = true),
        Voice("Mortlake", "lX3LavMNdlIbgEHH", true, "old, dry, close to the ear",           // Old Mortlake
            character = true),
        Voice("Og", "cYtsRSxV5q9IMehn", true, "a giant, slow and heavy",                    // Grub the Ogre
            character = true),
        Voice("Abner", "dGyGDKo2osj7h3s7", true, "dark and resonant",                      // Azgorath
            character = true),
        Voice("Hollis", "GXaSqMEmOg12OIYb", true, "hushed, strange, from the shadows",      // The Thing Under the Stairs
            character = true),
        Voice("Fennick", "cXoIXJ2llS8S0WOW", true, "young, bright, quick",                  // Fennick the Young Elf
            character = true),
        Voice("Morwenna", "nOykTmcH9ydsGGIc", false, "low, slow, from the deep",            // Morwenna of the Deep
            character = true),
        Voice("Esther", "NIWoEurJHMJjyUTe", false, "a queen, cool and clear",               // The Snow Queen
            character = true),
        Voice("Marian", "QXNr0SvkukLB4wPq", false, "a godmother, warm and twinkling",       // Fairy Godmother
            character = true),
    )

    fun byName(name: String): Voice? = ALL.firstOrNull { it.name.equals(name, true) }

    /** The short line under a name in the picker. */
    fun describe(name: String): String? = byName(name)?.let {
        "${it.detail} · ${if (it.male) "male" else "female"}"
    }

    /** How fast [name] is played, pitch kept, before the reader's own pace is applied. */
    fun tempo(name: String): Float = byName(name)?.tempo ?: 1f

    /** The sampling temperature [name] reads at. */
    fun warmth(name: String): Float = byName(name)?.warmth ?: Narrator.NARRATION_TEMP
}
