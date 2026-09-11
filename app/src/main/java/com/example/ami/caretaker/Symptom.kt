package com.example.ami.caretaker

/**
 * The health complaints AMI can recognise when someone describes them out loud.
 *
 * This is a fixed vocabulary rather than free text on purpose. A check-in note saying
 * "my head has been hurting since morning" is unreadable as data - it cannot be counted,
 * it cannot be noticed happening four days running, and it cannot be summarised for a
 * caregiver. Mapping speech onto a known key is what turns a passing remark on a phone
 * call into something the caretaker report can actually act on.
 *
 * Two fields carry the safety rule of the whole feature:
 *
 * - [redFlag] marks a complaint that is never advised about. These short-circuit the
 *   caretaker agent before any model is consulted and route to a human instead, the same
 *   way [com.example.ami.health.VitalsAssessment] hard-stops diet advice on an URGENT
 *   reading. Offering someone stretches for chest pain would be actively harmful.
 * - [foodTip] and [moveTip] are the deterministic fallback used whenever the model is
 *   unavailable - which includes the common case of `GEMINI_API_KEY` being blank.
 *   They are everyday comfort measures, never treatment, and are null for red flags.
 *
 * [phrases] are matched with a leading word boundary only, so a phrase also matches its
 * plural and its simple inflections ("headache" catches "headaches"). Every phrase names
 * the *complaint*, never just the body part: "stomach" alone would read "my stomach is
 * fine" as a stomach ache.
 */
enum class Symptom(
    val key: String,
    /** Title-case, for the caretaker report screen. */
    val label: String,
    /** Reads naturally after "they mentioned", e.g. "a headache". */
    val spoken: String,
    val phrases: List<String>,
    val redFlag: Boolean = false,
    val foodTip: String? = null,
    val moveTip: String? = null
) {

    // --- Red flags: a person, not advice ------------------------------------------

    CHEST_PAIN(
        key = "chest_pain",
        label = "Chest pain",
        spoken = "chest pain",
        phrases = listOf(
            "chest pain", "pain in my chest", "pain in the chest", "chest hurts",
            "chest is hurting", "tightness in my chest", "tight chest",
            "pressure in my chest", "heaviness in my chest", "heart pain"
        ),
        redFlag = true
    ),

    BREATHLESSNESS(
        key = "breathlessness",
        label = "Trouble breathing",
        spoken = "trouble breathing",
        phrases = listOf(
            "breathless", "short of breath", "shortness of breath", "cannot breathe",
            "can t breathe", "cant breathe", "trouble breathing", "hard to breathe",
            "difficulty breathing", "gasping", "out of breath"
        ),
        redFlag = true
    ),

    FALL(
        key = "fall",
        label = "A fall",
        spoken = "a fall",
        phrases = listOf(
            "i fell", "fell down", "had a fall", "have fallen", "i have fallen",
            "slipped and fell", "fell over", "fell in the"
        ),
        redFlag = true
    ),

    FAINTING(
        key = "fainting",
        label = "Fainting",
        spoken = "fainting or blacking out",
        phrases = listOf(
            "fainted", "passed out", "blacked out", "lost consciousness", "collapsed",
            "blackout"
        ),
        redFlag = true
    ),

    BLEEDING(
        key = "bleeding",
        label = "Bleeding",
        spoken = "bleeding",
        phrases = listOf("bleeding", "blood in", "coughing blood", "vomiting blood"),
        redFlag = true
    ),

    STROKE_SIGNS(
        key = "stroke_signs",
        label = "Weakness or slurred speech",
        spoken = "weakness on one side or slurred speech",
        phrases = listOf(
            "slurred speech", "slurring", "face is drooping", "drooping",
            "numb on one side", "one side is numb", "cannot move my arm",
            "cant move my arm", "can t move my arm", "cannot move my leg"
        ),
        redFlag = true
    ),

    // --- Everyday complaints -------------------------------------------------------

    HEADACHE(
        key = "headache",
        label = "Headache",
        spoken = "a headache",
        phrases = listOf(
            "headache", "head ache", "head hurts", "head is hurting", "head pain",
            "pain in my head", "migraine", "my head is heavy", "heavy head"
        ),
        foodTip = "Drink water through the day, and don't skip meals - a headache often " +
            "follows too little fluid or a late meal.",
        moveTip = "Rest your eyes away from screens, sit somewhere quiet and dim, and " +
            "roll your shoulders and neck slowly a few times."
    ),

    STOMACH_ACHE(
        key = "stomach_ache",
        label = "Stomach ache",
        spoken = "a stomach ache",
        phrases = listOf(
            "stomach ache", "stomachache", "stomach pain", "stomach hurts",
            "stomach is hurting", "stomach upset", "upset stomach", "tummy ache",
            "tummy hurts", "belly pain", "abdominal pain", "pain in my stomach",
            "acidity", "indigestion", "gas problem", "bloated", "burning in my stomach"
        ),
        foodTip = "Keep to simple, plain food today - khichdi, curd, rice, bananas, " +
            "toast. Skip fried, spicy and very oily things until it settles.",
        moveTip = "Nothing strenuous. A slow ten-minute walk after eating helps more " +
            "than lying down flat straight after a meal."
    ),

    NAUSEA(
        key = "nausea",
        label = "Nausea",
        spoken = "feeling sick",
        phrases = listOf(
            "nausea", "nauseous", "feel sick", "feeling sick", "queasy", "vomiting",
            "throwing up", "threw up", "want to vomit", "sick to my stomach"
        ),
        foodTip = "Small sips of water often, rather than a full glass at once. Dry " +
            "toast, plain rice or a banana when you can manage something.",
        moveTip = "Sit upright and rest. Fresh air at a window helps; avoid bending " +
            "over or lying flat right away."
    ),

    DIZZINESS(
        key = "dizziness",
        label = "Dizziness",
        spoken = "dizziness",
        phrases = listOf(
            "dizzy", "dizziness", "light headed", "lightheaded", "giddy", "giddiness",
            "head spinning", "room is spinning", "vertigo", "unsteady"
        ),
        foodTip = "Have something to eat and drink - dizziness often follows a missed " +
            "meal or too little water. Go easy on very salty food.",
        moveTip = "Stand up slowly, and sit on the edge of the bed for a moment before " +
            "getting up. Hold a rail on stairs. Don't walk outside alone today."
    ),

    FEVER(
        key = "fever",
        label = "Fever",
        spoken = "a fever",
        phrases = listOf(
            "fever", "feverish", "temperature is high", "running a temperature",
            "chills", "shivering", "hot and cold"
        ),
        foodTip = "Fluids matter most - water, nimbu pani, soup, coconut water. Light " +
            "food like dal, khichdi or soup rather than a heavy meal.",
        moveTip = "Rest today. No exercise while there's a fever; gentle movement can " +
            "wait until the temperature has settled."
    ),

    COUGH_COLD(
        key = "cough_cold",
        label = "Cough or cold",
        spoken = "a cough or cold",
        phrases = listOf(
            "cough", "coughing", "sore throat", "throat hurts", "runny nose",
            "blocked nose", "stuffy nose", "sneezing", "congestion", "have a cold",
            "caught a cold"
        ),
        foodTip = "Warm fluids through the day - soup, warm water, ginger or tulsi tea. " +
            "Honey in warm water soothes a sore throat.",
        moveTip = "Stay warm indoors and rest. A little walking about the house is fine; " +
            "leave harder exercise until the chest is clear."
    ),

    BODY_ACHE(
        key = "body_ache",
        label = "Body ache",
        spoken = "body aches",
        phrases = listOf(
            "body ache", "body pain", "aching all over", "ache all over", "muscle pain",
            "muscles ache", "whole body hurts", "body is paining"
        ),
        foodTip = "Drink water regularly and eat proper meals with some protein - dal, " +
            "eggs, milk, curd - which is what aching muscles repair on.",
        moveTip = "Gentle stretching and a warm bath, not rest in bed all day. Keep " +
            "moving lightly; stillness usually makes the aching worse."
    ),

    JOINT_PAIN(
        key = "joint_pain",
        label = "Joint pain",
        spoken = "joint pain",
        phrases = listOf(
            "joint pain", "joints hurt", "joints are hurting", "joint ache",
            "knee pain", "knees hurt", "knees are paining", "arthritis",
            "hip pain", "shoulder pain", "elbow pain", "wrist pain", "stiff joints"
        ),
        foodTip = "Include calcium and protein - milk, curd, paneer, ragi, green leafy " +
            "vegetables. Keep drinking water; go easy on very salty and fried food.",
        moveTip = "Keep the joint moving gently rather than resting it completely - " +
            "slow ankle and knee bends while seated, and short flat walks. Avoid stairs " +
            "and squatting while it's sore."
    ),

    BACK_PAIN(
        key = "back_pain",
        label = "Back pain",
        spoken = "back pain",
        phrases = listOf(
            "back pain", "back ache", "backache", "back hurts", "back is hurting",
            "lower back", "pain in my back", "spine hurts"
        ),
        foodTip = "Nothing specific to change in your food - just keep drinking water " +
            "and eating regular meals so you're not stiff from an empty stomach.",
        moveTip = "Short, frequent walks beat sitting still for hours. Sit in a chair " +
            "with a firm back rather than a soft sofa, and avoid lifting anything heavy."
    ),

    FATIGUE(
        key = "fatigue",
        label = "Tiredness",
        spoken = "tiredness",
        phrases = listOf(
            "tired", "very tired", "weak", "weakness", "no energy", "no strength",
            "exhausted", "fatigue", "worn out", "lethargic", "drained", "feeling low"
        ),
        foodTip = "Eat at regular times and don't skip breakfast. Include some protein " +
            "at each meal - dal, eggs, milk, curd, nuts - and iron-rich food like green " +
            "leafy vegetables and dates.",
        moveTip = "A short walk in daylight, even ten minutes, usually helps more than " +
            "a long nap. Don't push hard - little and often."
    ),

    POOR_SLEEP(
        key = "poor_sleep",
        label = "Trouble sleeping",
        spoken = "trouble sleeping",
        phrases = listOf(
            "cannot sleep", "cant sleep", "can t sleep", "could not sleep",
            "couldnt sleep", "not sleeping", "no sleep", "insomnia", "slept badly",
            "awake all night", "up all night", "keep waking", "disturbed sleep",
            "bad sleep", "poor sleep",
            // Negatively phrased, like the "cant sleep" family: the matched phrase carries
            // its own negation, so SymptomExtractor.isDenied does not read these as denials.
            "didnt sleep", "did not sleep", "not slept", "havent slept", "hardly slept",
            "barely slept", "sleep was bad"
        ),
        foodTip = "Keep tea and coffee to the morning, and make the evening meal a " +
            "lighter one eaten a couple of hours before bed. Warm milk at night helps " +
            "some people settle.",
        moveTip = "Get some daylight and a walk earlier in the day, and keep the last " +
            "hour before bed quiet and screen-free."
    ),

    APPETITE_LOSS(
        key = "appetite_loss",
        label = "Poor appetite",
        spoken = "not wanting to eat",
        phrases = listOf(
            "no appetite", "lost my appetite", "not hungry", "dont feel like eating",
            "don t feel like eating", "do not feel like eating", "cannot eat",
            "cant eat", "not eating properly", "food has no taste"
        ),
        foodTip = "Small plates more often rather than three big meals. Foods that go " +
            "down easily - curd rice, khichdi, soup, banana, a glass of milk - so " +
            "something is going in even on a poor day.",
        moveTip = "A short walk before a meal often brings some appetite back."
    ),

    CONSTIPATION(
        key = "constipation",
        label = "Constipation",
        spoken = "constipation",
        phrases = listOf(
            "constipation", "constipated", "not passing motion", "hard motion",
            "bowels are not", "trouble passing", "stomach is not clear"
        ),
        foodTip = "More fibre and more water together - both, since fibre without fluid " +
            "makes it worse. Papaya, soaked raisins, guava, whole grains, vegetables, " +
            "and a glass of warm water in the morning.",
        moveTip = "Walking is the single most useful thing - a ten to fifteen minute " +
            "walk after meals, every day rather than occasionally."
    ),

    SWELLING(
        key = "swelling",
        label = "Swelling",
        spoken = "swelling",
        phrases = listOf(
            "swelling", "swollen", "ankles are swollen", "feet are swollen", "puffy",
            "legs are swollen", "puffiness"
        ),
        foodTip = "Cut back on salt today - pickles, papad, packet snacks and namkeen " +
            "in particular. Keep drinking normal amounts of water.",
        moveTip = "Put your feet up on a stool or pillow when sitting, and move your " +
            "ankles in circles now and then rather than sitting still for hours."
    ),

    BLURRED_VISION(
        key = "blurred_vision",
        label = "Blurred vision",
        spoken = "blurred vision",
        phrases = listOf(
            "blurred vision", "blurry vision", "vision is blurred", "cannot see clearly",
            "cant see clearly", "eyes are blurry", "seeing double", "double vision"
        ),
        foodTip = "Nothing in particular to change today. Keep meals and fluids regular.",
        moveTip = "Don't drive or use the stairs alone while things look unclear, and " +
            "mention it to your doctor at the next visit."
    );

    companion object {

        val redFlags: List<Symptom> get() = entries.filter { it.redFlag }

        fun forKey(key: String): Symptom? = entries.firstOrNull { it.key == key }
    }
}

/**
 * How bad the person said it was.
 *
 * Read from their own words rather than asked as a number: putting "on a scale of one
 * to ten" to someone on a check-in call gets a shrug, whereas "it's quite bad today"
 * comes out on its own. [UNKNOWN] is an honest answer and stays distinct from [MILD] -
 * a complaint with no intensity attached is not the same as a small one.
 */
enum class SymptomSeverity(val key: String, val label: String) {
    UNKNOWN("UNKNOWN", "mentioned"),
    MILD("MILD", "mild"),
    MODERATE("MODERATE", "moderate"),
    SEVERE("SEVERE", "severe");

    companion object {
        fun forKey(key: String): SymptomSeverity =
            entries.firstOrNull { it.key == key } ?: UNKNOWN
    }
}
