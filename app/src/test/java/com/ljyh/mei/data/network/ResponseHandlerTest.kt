package com.ljyh.mei.data.network

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ResponseHandlerTest {
    @Test fun successfulAndFailedCallsKeepTheirExistingResourceContract() = runBlocking {
        assertEquals(Resource.Success(1), safeApiCall { 1 })
        assertEquals(Resource.Error("Offline"), safeApiCall<Int> { throw IOException("Offline") })
    }

    @Test fun cancellationRetainsItsIdentityAndNeverBecomesAVisibleError() = runBlocking {
        val cancellation = CancellationException("Canceled")
        val result = runCatching { safeApiCall<Int> { throw cancellation } }
        assertSame(cancellation, result.exceptionOrNull())
    }
}
