package dev.zmeyka.avpnp

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

/**
 * One row per installed app: icon, label, and a destination picker (Direct, or one of the clients).
 * Selecting a destination records the intent; the caller re-applies routing afterwards.
 */
class AppListAdapter(
    private val context: Context,
    private val apps: List<InstalledApps.Entry>,
    private val destinations: List<Destination>,
    private val onChanged: () -> Unit,
) : BaseAdapter() {

    /** A routing choice. A null [value] means unassigned; otherwise one of the exit constants or a
     *  client package. */
    data class Destination(val value: String?, val label: String)

    override fun getCount(): Int = apps.size
    override fun getItem(position: Int): Any = apps[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val row = (convertView as? LinearLayout) ?: buildRow()
        val app = apps[position]

        (row.getChildAt(0) as ImageView).setImageDrawable(app.icon)
        (row.getChildAt(1) as TextView).text = app.label

        val spinner = row.getChildAt(2) as Spinner
        spinner.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            destinations.map { it.label },
        )

        val current = AppAssignments.destinationOf(context, app.packageName)
        val index = destinations.indexOfFirst { it.value == current }.coerceAtLeast(0)
        spinner.setSelection(index, false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                AppAssignments.setDestination(context, app.packageName, destinations[pos].value)
                onChanged()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        return row
    }

    private fun buildRow(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(32, 16, 32, 16)

        addView(
            ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(96, 96)
            }
        )
        addView(
            TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                textSize = 15f
            }
        )
        addView(Spinner(context))
    }
}
