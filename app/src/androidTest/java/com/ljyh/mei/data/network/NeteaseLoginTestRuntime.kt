package com.ljyh.mei.data.network

import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.di.RetrofitModule
import com.ljyh.mei.data.repository.MeloXRepository

/** Use one runtime per process, as production does: WatchMan.init cannot run twice. */
internal object NeteaseLoginTestRuntime {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    val security by lazy {
        NeteaseLoginSecurity(context, RetrofitModule.provideNeteaseSecurityClient())
    }
    val urs by lazy { NeteaseUrsSmsLogin(context) }
    val repository by lazy {
        val client = RetrofitModule.provideOkHttpClient()
        val eapi = RetrofitModule.provideMeloXEapiService(RetrofitModule.provideRetrofit(client))
        MeloXRepository(
            eapi, eapi, context, RetrofitModule.provideCloudUploadClient(), security, urs,
        )
    }
}
