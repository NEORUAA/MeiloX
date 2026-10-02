package com.ljyh.mei.runtime

import android.os.Bundle

/** Only platform snapshots and commands cross the helper boundary, never account credentials. */
interface LyricsPipSource : AutoCloseable {
    fun frame(previousCoverRevision: Long): Bundle?
    fun command(command: Int)
}
