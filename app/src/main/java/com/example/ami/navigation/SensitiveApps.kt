package com.example.ami.navigation

import java.util.Locale

/**
 * Decides whether AMI may read the screen of a given app.
 *
 * Guided navigation ships the visible labels of whatever is in the foreground to a
 * third-party model. Password fields were already excluded, but that only covers the
 * field itself - an account number, a balance, a one-time code or a transaction list
 * is ordinary text and was being sent verbatim. Inside a banking, payments or
 * authenticator app the right behaviour is not to redact but to refuse: those are also
 * the apps where a mis-aimed tap costs real money.
 *
 * Matching is on the package name, which the user cannot be socially engineered into
 * mistyping the way they can an app label: an explicit list of known packages, plus
 * keyword and whole-segment rules over the package name itself. Android has no
 * "finance" application category to lean on, and the categories it does define are
 * self-declared and almost never set by banking apps, so there is no platform signal
 * worth consulting here.
 *
 * Deliberately over-broad. A false positive costs one spoken "I can't help in this
 * app"; a false negative sends someone's bank balance to an LLM.
 */
object SensitiveApps {

    /** Exact packages, or package prefixes ending in '.', that are always refused. */
    private val BLOCKED_PACKAGES = setOf(
        // Password managers and authenticators
        "com.google.android.apps.authenticator2",
        "com.azure.authenticator",
        "com.authy.authy",
        "com.lastpass.lpandroid",
        "com.agilebits.onepassword",
        "com.onepassword.android",
        "com.bitwarden.authenticator",
        "com.x8bit.bitwarden",
        "com.keepersecurity.parental",
        "com.dashlane",
        // Payments and wallets
        "com.google.android.apps.walletnfcrel",
        "com.google.android.apps.nbu.paisa.user",
        "com.phonepe.app",
        "net.one97.paytm",
        "in.org.npci.upiapp",
        "com.paypal.android.p2pmobile",
        "com.squareup.cash",
        "com.venmo",
        "com.coinbase.android",
        "com.binance.dev",
        // Device credential surfaces
        "com.android.settings.credentials",
        "com.google.android.gms.auth"
    )

    /**
     * Substrings that mark a package as financial or credential-bearing.
     *
     * Checked against the package name only. "pay" is matched with a dot or start
     * boundary so that "com.foo.paypal" matches but "com.example.wallpaper" does not.
     */
    private val BLOCKED_KEYWORDS = listOf(
        "bank", "banking", "upi", "wallet", "paytm", "phonepe", "creditcard",
        "authenticator", "password", "keychain", "vault", "brokerage", "trading",
        "insurance", "mutualfund", "demat", "netbanking", "finserv"
    )

    /** Package segments that mean "payments" on their own, matched whole. */
    private val BLOCKED_SEGMENTS = setOf("pay", "payments", "money", "cash", "finance", "fintech")

    /** @param packageName foreground package, or null when it can't be determined */
    fun isSensitive(packageName: String?): Boolean {
        val pkg = packageName?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() } ?: return false

        if (pkg in BLOCKED_PACKAGES) return true
        if (BLOCKED_PACKAGES.any { it.endsWith('.') && pkg.startsWith(it) }) return true
        if (BLOCKED_KEYWORDS.any { pkg.contains(it) }) return true

        return pkg.split('.').any { it in BLOCKED_SEGMENTS }
    }

    /** What AMI says instead of guiding. Plain, and never implies the app is broken. */
    const val REFUSAL = "I don't help inside banking or password apps, to keep your " +
        "money and your details safe. I can help again once you leave this app."
}
