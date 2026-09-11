package com.example.ami.profile

import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.isNotEmpty
import com.example.ami.R
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Builds the rows of a grouped settings list.
 *
 * Rows are added in code rather than written out in XML because the list is short, the
 * rows are identical apart from their content, and several of them only exist once a
 * permission state is known. Fourteen near-identical XML blocks would be harder to keep
 * in step than one builder.
 */
class SettingsRows(private val group: LinearLayout) {

    private val inflater = LayoutInflater.from(group.context)

    init {
        // Keeps a row's touch ripple inside the group's rounded corners. Set here rather
        // than in the style: the XML attribute is API 31+, while the property itself has
        // worked since long before this app's minSdk.
        group.clipToOutline = true
    }

    /** A row that goes somewhere: chevron on the right, optional value beside it. */
    fun navigation(
        @StringRes titleRes: Int,
        value: String? = null,
        @ColorRes titleColorRes: Int? = null,
        onClick: () -> Unit
    ): View = row(titleRes, titleColorRes) { view ->
        view.findViewById<ImageView>(R.id.rowChevron).visibility = View.VISIBLE
        value?.let {
            view.findViewById<TextView>(R.id.rowValue).apply {
                text = it
                visibility = View.VISIBLE
            }
        }
        view.setOnClickListener { onClick() }
    }

    /**
     * A row that reports an on/off state.
     *
     * The switch is not independently focusable and the whole row handles the tap, so
     * there is one target of [R.dimen.touch_target] height rather than a small switch
     * floating at the end of a large inert row.
     */
    fun toggle(
        @StringRes titleRes: Int,
        checked: Boolean,
        onClick: () -> Unit
    ): View = row(titleRes, null) { view ->
        view.findViewById<SwitchMaterial>(R.id.rowSwitch).apply {
            isChecked = checked
            visibility = View.VISIBLE
        }
        view.setOnClickListener { onClick() }
    }

    /** A row that does something here and now, with no chevron - used for destructive actions. */
    fun action(
        @StringRes titleRes: Int,
        @ColorRes titleColorRes: Int? = null,
        onClick: () -> Unit
    ): View = row(titleRes, titleColorRes) { view ->
        view.setOnClickListener { onClick() }
    }

    private fun row(
        @StringRes titleRes: Int,
        @ColorRes titleColorRes: Int?,
        configure: (View) -> Unit
    ): View {
        // A divider before every row but the first, so groups never open or close on one.
        if (group.isNotEmpty()) group.addView(divider())

        val view = inflater.inflate(R.layout.item_settings_row, group, false)
        view.findViewById<TextView>(R.id.rowTitle).apply {
            setText(titleRes)
            titleColorRes?.let { setTextColor(ContextCompat.getColor(context, it)) }
        }
        configure(view)
        group.addView(view)
        return view
    }

    private fun divider(): View = View(group.context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            group.resources.getDimensionPixelSize(R.dimen.divider_height)
        )
        setBackgroundResource(R.drawable.bg_settings_divider)
    }

    fun clear() = group.removeAllViews()
}
