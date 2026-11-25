package top.niunaijun.blackboxa.zenix

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import top.niunaijun.blackbox.core.system.user.BUserInfo
import top.niunaijun.blackboxa.databinding.ItemZenixSpaceBinding

/**
 * One row per container space, showing the measured microG stack state for that
 * space -- gms / gsf / vending, individually, never a single "ok".
 */
class ZenixSpaceAdapter(
    private val onInstallStack: (BUserInfo) -> Unit,
    private val onInstallApk: (BUserInfo) -> Unit,
    private val onDelete: (BUserInfo) -> Unit
) : RecyclerView.Adapter<ZenixSpaceAdapter.Holder>() {

    private val items = ArrayList<BUserInfo>()
    private val statuses = HashMap<Int, ZenixSpaceManager.StackStatus>()

    class Holder(val binding: ItemZenixSpaceBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemZenixSpaceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val user = items[position]
        val status = statuses[user.id]
        holder.binding.spaceName.text = "Space ${user.id}  #${user.id}"

        holder.binding.spaceStatus.text = if (status == null) {
            "microG: unknown"
        } else if (status.complete()) {
            "microG: READY  (gms + gsf + vending)"
        } else {
            "microG: ${status.summary()}"
        }

        holder.binding.btnStack.text = if (status?.complete() == true) {
            holder.binding.root.context.getString(top.niunaijun.blackboxa.R.string.zenix_reinstall_microg)
        } else {
            holder.binding.root.context.getString(top.niunaijun.blackboxa.R.string.zenix_install_microg)
        }

        holder.binding.btnStack.setOnClickListener { onInstallStack(user) }
        holder.binding.btnSpaceApk.setOnClickListener { onInstallApk(user) }
        holder.binding.btnDeleteSpace.isEnabled = user.id > 0
        holder.binding.btnDeleteSpace.alpha = if (user.id > 0) 1f else 0.4f
        holder.binding.btnDeleteSpace.setOnClickListener { onDelete(user) }
    }

    override fun getItemCount(): Int = items.size

    fun submit(users: List<BUserInfo>) {
        items.clear()
        items.addAll(users)
        notifyDataSetChanged()
    }

    fun setStatus(status: ZenixSpaceManager.StackStatus) {
        statuses[status.userId] = status
        val index = items.indexOfFirst { it.id == status.userId }
        if (index >= 0) {
            notifyItemChanged(index)
        }
    }
}
