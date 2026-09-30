package com.ljyh.mei.runtime

import android.app.Application
import androidx.work.Configuration
import com.ljyh.mei.standalone.StandaloneDownloadWorkerFactory

open class RuntimeApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(StandaloneDownloadWorkerFactory()).build()
}
