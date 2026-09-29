package com.ljyh.mei.runtime

import android.content.Context

object RuntimeBootstrap {
    // Only the verified official Application hook may start a parasite runtime.
    fun initialize(context: Context) = Unit
}
