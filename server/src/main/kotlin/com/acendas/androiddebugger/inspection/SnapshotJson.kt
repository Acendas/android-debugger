package com.acendas.androiddebugger.inspection

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Serialize a [FrameSnapshot] into the JSON shape the MCP tool returns. */
fun FrameSnapshot.toJson(): JsonObject = buildJsonObject {
    put("event", event)
    put("thread", buildJsonObject {
        put("id", thread.id)
        put("name", thread.name)
        put("status", thread.status)
    })
    put("frames", buildJsonArray {
        for (f in frames) add(f.toJson())
    })
    put("watches", buildJsonArray {
        for (w in watches) add(w.toJson())
    })
    pausedReason?.let { put("paused_reason", it) }
    // Per Story 7.1.2 (Task 7.1.2.2): when every frame in the snapshot has stripped
    // local-variable info, surface a top-level R8/ProGuard warning so the agent can
    // suggest "rebuild as debug variant" instead of trying to inspect locals it can
    // never read. We keep `localsUnavailable` per-frame for partial cases.
    if (frames.isNotEmpty() && frames.all { it.localsUnavailable }) {
        put(
            "warning",
            buildJsonObject {
                put("type", "absent_local_variables")
                put(
                    "hint",
                    "this build appears to be R8/ProGuard-stripped — rebuild as debug variant",
                )
            },
        )
    }
}

private fun FrameInfo.toJson(): JsonObject = buildJsonObject {
    put("index", index)
    put("frame_id", frameId)
    put("method", method)
    put("class", className)
    sourceFile?.let { put("file", it) }
    put("line", lineNumber)
    thisId?.let { put("this_id", it) }
    if (locals == null) {
        put("locals_unavailable", true)
        put("hint", "compiled without debug info — possibly a release/R8 build")
    } else {
        put("locals", buildJsonObject {
            for ((name, v) in locals) put(name, v.toJson())
        })
    }
}

private fun WatchValue.toJson(): JsonObject = buildJsonObject {
    put("expr", expr)
    value?.let { put("value", it.toJson()) }
    error?.let { put("error", it) }
    if (stale) put("stale", true)
}

internal fun RenderedValue.toJson(): JsonObject = buildJsonObject {
    put("rendered", rendered)
    put("type", type)
    refId?.let { put("ref_id", it) }
}
