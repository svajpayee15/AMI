package com.example.ami.games

/**
 * The thoughts that ride the bubbles.
 *
 * This is the one activity here that is doing something psychological rather than merely
 * soothing. Naming an unwanted thought, seeing it as an object separate from yourself, and
 * then getting rid of it deliberately is a recognised defusion exercise - and it is far
 * more use to a lonely older person than another generic puzzle.
 *
 * It is also the one that could do harm if it were careless, so the wording is held to
 * three rules:
 *
 * 1. **Every line is a thought someone might already be having, not a suggestion.** These
 *    are the phrases that turn up in the reference design and in what older people
 *    actually say about loneliness. Nothing here introduces an idea that was not there.
 * 2. **Nothing about self-harm, dying, or being better off gone.** A tap-to-pop game is
 *    the wrong place for those, and seeing one rendered as a toy would be cruel. If
 *    someone is having those thoughts they need [com.example.ami.caretaker.Symptom]'s
 *    red-flag path and a person, not a bubble.
 * 3. **The screen always ends on something kind.** Clearing the last bubble leaves a
 *    warm line rather than an empty field, so the exercise closes rather than just
 *    stopping.
 */
object ThoughtBubbles {

    /**
     * Taken from the design, lightly edited so each one fits on two short lines at a size
     * someone can read without their glasses.
     */
    val THOUGHTS: List<String> = listOf(
        "I'm useless now",
        "No one loves me",
        "I'm a burden to my family",
        "Children don't need me",
        "Nobody cares about me anymore",
        "I have nothing left to give",
        "I only get in the way",
        "I'm too old to matter"
    )

    /**
     * Thoughts in a shuffled order, repeating as needed.
     *
     * Repeating rather than running out is deliberate: the same thought coming round again
     * and being popped again is the point of the exercise, and a field that empties
     * permanently would end it after eight taps.
     */
    fun cycle(seed: Int = 0): Iterator<String> = iterator {
        val order = THOUGHTS.shuffled(kotlin.random.Random(seed)).toMutableList()
        var i = 0
        while (true) {
            if (i >= order.size) {
                i = 0
                order.shuffle(kotlin.random.Random(seed + 1))
            }
            yield(order[i++])
        }
    }
}
