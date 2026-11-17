package top.zainzh.rearscreen.platform

/** Holds one Binder gate for the complete pause→bind→commit transaction. */
internal class CandidateActionGateSlot<T : Any> {
    private var operation: String? = null
    private var gate: T? = null

    @Synchronized fun bind(operationId: String, value: T): Boolean {
        if (operationId.isBlank() || (operation != null && operation != operationId)) return false
        val current = gate
        if (current != null) return current === value
        operation = operationId
        gate = value
        return true
    }

    @Synchronized fun get(operationId: String): T? = if (operation == operationId) gate else null

    @Synchronized fun clear() { operation = null; gate = null }
}
