package com.acendas.androiddebugger.inspection

import com.sun.jdi.ObjectReference
import com.sun.jdi.ReferenceType
import com.sun.jdi.ThreadReference
import java.util.concurrent.ConcurrentHashMap

/**
 * Mint opaque short ids that the agent can pass back to drill-down tools (`inspect_object`,
 * `get_array_slice`, `evaluate`, etc.) without ever seeing raw JDI handles. The ids are
 * shaped as namespaced strings (`obj#<n>`, `cls#<name>`, `frame#<thread>:<idx>`) so they
 * survive JSON round-trips and remain readable in logs. Per Task 2.1.3.1.
 */
object ObjectIdMint {

    private val objects: MutableMap<Long, ObjectReference> = ConcurrentHashMap()

    fun registerObject(ref: ObjectReference): String {
        val id = ref.uniqueID()
        objects[id] = ref
        return "obj#$id"
    }

    fun resolveObject(idStr: String): ObjectReference? {
        if (!idStr.startsWith("obj#")) return null
        val id = idStr.removePrefix("obj#").toLongOrNull() ?: return null
        return objects[id]
    }

    fun frameId(thread: ThreadReference, index: Int): String =
        "frame#${thread.uniqueID()}:$index"

    fun resolveFrame(idStr: String): Pair<Long, Int>? {
        if (!idStr.startsWith("frame#")) return null
        val rest = idStr.removePrefix("frame#")
        val parts = rest.split(":")
        if (parts.size != 2) return null
        val threadId = parts[0].toLongOrNull() ?: return null
        val frameIdx = parts[1].toIntOrNull() ?: return null
        return threadId to frameIdx
    }

    fun classId(type: ReferenceType): String = "cls#${type.name()}"

    fun resolveClassName(idStr: String): String? =
        if (idStr.startsWith("cls#")) idStr.removePrefix("cls#") else null

    /** Drop all cached references — called on detach / VM disconnect. */
    fun clear() {
        objects.clear()
    }
}
