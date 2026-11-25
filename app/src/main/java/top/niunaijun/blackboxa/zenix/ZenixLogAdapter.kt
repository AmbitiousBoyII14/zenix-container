package top.niunaijun.blackboxa.zenix

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import top.niunaijun.blackboxa.databinding.ItemZenixLogBinding

/**
 * Live debugger list. Mirrors the ZenixLog ring buffer: appends, trims from the
 * head, and never re-renders the whole buffer on a single line.
 */
class ZenixLogAdapter : RecyclerView.Adapter<ZenixLogAdapter.Holder>() {

    private val lines = ArrayList<ZenixLog.Entry>()
    private var minLevel = ZenixLog.LEVEL_VERBOSE

    class Holder(val binding: ItemZenixLogBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemZenixLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = lines[position]
        holder.binding.logLine.text = entry.uiLine
        holder.binding.logLine.setTextColor(
            when {
                entry.level >= ZenixLog.LEVEL_ERROR -> 0xFFFF6B6B.toInt()
                entry.level >= ZenixLog.LEVEL_WARN -> 0xFFFFC857.toInt()
                entry.gate == "MICROG" || entry.gate == "SPACE" -> 0xFF8ED6A0.toInt()
                else -> 0xFFD8D8E0.toInt()
            }
        )
    }

    override fun getItemCount(): Int = lines.size

    fun seed(entries: List<ZenixLog.Entry>) {
        lines.clear()
        entries.forEach { if (it.level >= minLevel) lines.add(it) }
        trim()
        notifyDataSetChanged()
    }

    fun append(entry: ZenixLog.Entry) {
        if (entry.level < minLevel) return
        lines.add(entry)
        trim()
        notifyItemInserted(lines.size - 1)
    }

    fun setMinLevel(level: Int) {
        minLevel = level
        seed(ZenixLog.snapshot())
    }

    private fun trim() {
        while (lines.size > MAX_LINES) {
            lines.removeAt(0)
        }
    }

    companion object {
        private const val MAX_LINES = 600
    }
}
