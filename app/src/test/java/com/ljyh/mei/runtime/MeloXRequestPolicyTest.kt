package com.ljyh.mei.runtime

import com.google.gson.JsonObject
import com.ljyh.mei.BuildConfig
import com.ljyh.mei.data.session.SessionChangedException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MeloXRequestPolicyTest {
    private val standalone = BuildConfig.FLAVOR == "standalone"

    @Test fun successDoesNotInvokeTheAlternative() = runBlocking {
        val response = JsonObject()
        assertSame(response, MeloXRequestPolicy.request({ response }, { error("Unexpected fallback") }))
    }

    @Test fun transportFailureRetriesOnlyInStandalone() = runBlocking {
        assertRetry(IOException("Synthetic transport failure"))
    }

    @Test fun businessFailureRetriesOnlyInStandalone() = runBlocking {
        assertRetry(IllegalStateException("Synthetic business failure"))
    }

    @Test fun cancellationNeverRetries() = runBlocking {
        val error = CancellationException("Synthetic cancellation")
        assertSame(error, runCatching {
            MeloXRequestPolicy.request({ throw error }, { error("Unexpected fallback") })
        }.exceptionOrNull())
    }

    @Test fun changedSessionNeverRetries() = runBlocking {
        val error = SessionChangedException()
        assertSame(error, runCatching {
            MeloXRequestPolicy.request({ throw error }, { error("Unexpected fallback") })
        }.exceptionOrNull())
    }

    @Test fun alternativeFailureIsTerminalWithoutALoop() = runBlocking {
        val primary = IOException("Primary")
        val fallback = IOException("Fallback")
        var attempts = 0
        val result = runCatching {
            MeloXRequestPolicy.request({ attempts++; throw primary }, { attempts++; throw fallback })
        }
        assertSame(if (standalone) fallback else primary, result.exceptionOrNull())
        assertEquals(if (standalone) 2 else 1, attempts)
    }

    private suspend fun assertRetry(error: Exception) {
        val response = JsonObject()
        var alternatives = 0
        val result = runCatching {
            MeloXRequestPolicy.request({ throw error }, { alternatives++; response })
        }
        if (standalone) assertSame(response, result.getOrThrow())
        else assertSame(error, result.exceptionOrNull())
        assertEquals(if (standalone) 1 else 0, alternatives)
    }
}
