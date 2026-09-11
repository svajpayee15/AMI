package com.example.ami.profile

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.example.ami.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar

/**
 * The five read-only pages behind "About & help".
 *
 * One activity rather than five: they differ only in a title and a block of text, and
 * five near-identical classes would be five places to forget to apply a change. The
 * enum keeps the set closed, so the settings list can build itself from it and a page
 * can never be added without also appearing in the menu.
 */
class InfoActivity : AppCompatActivity() {

    enum class Page(
        @get:StringRes val titleRes: Int,
        @get:StringRes val bodyRes: Int,
        /** Only the issue-report page offers an action. */
        val showsReportAction: Boolean = false
    ) {
        ABOUT(R.string.profile_about, R.string.info_about_body),
        PRIVACY(R.string.profile_privacy, R.string.info_privacy_body),
        AGREEMENT(R.string.profile_user_agreement, R.string.info_agreement_body),
        HELP(R.string.profile_help, R.string.info_help_body),
        REPORT(R.string.profile_report_issue, R.string.info_report_body, showsReportAction = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_info)

        val page = Page.valueOf(intent.getStringExtra(EXTRA_PAGE) ?: Page.ABOUT.name)

        findViewById<TextView>(R.id.infoTitle).setText(page.titleRes)
        findViewById<TextView>(R.id.infoBody).setText(page.bodyRes)
        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<MaterialButton>(R.id.btnReport).apply {
            visibility = if (page.showsReportAction) View.VISIBLE else View.GONE
            setOnClickListener { composeIssueEmail() }
        }
    }

    /**
     * Hands off to whatever mail app exists rather than collecting the report itself.
     *
     * This app has no backend that accepts reports, and a form that silently threw them
     * away would be worse than no form. If there is no mail app, the user is told that
     * plainly instead of watching nothing happen.
     */
    private fun composeIssueEmail() {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = "mailto:".toUri()
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.info_report_subject))
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Snackbar.make(
                findViewById(R.id.infoBody),
                R.string.info_report_no_email,
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    companion object {
        private const val EXTRA_PAGE = "page"

        fun intent(context: Context, page: Page): Intent =
            Intent(context, InfoActivity::class.java).putExtra(EXTRA_PAGE, page.name)
    }
}
