package com.ljyh.mei.parasite

import com.ljyh.mei.data.network.api.ApiService
import org.junit.Assert.*
import org.junit.Test

class HostRetrofitCompatibilityTest {
    @Test fun restoresOnlyTheMissingContinuationSlot() {
        val method = ApiService::class.java.methods.single { it.name == "getUserPlaylist" }
        val original = method.parameterAnnotations
        val truncated = original.copyOfRange(0, original.size - 1)
        val result = completeSuspendAnnotations(method, truncated)
        assertEquals(method.parameterCount, result.size)
        assertSame(truncated[0], result[0])
        assertTrue(result.last().isEmpty())
        assertSame(original, completeSuspendAnnotations(method, original))
        val missingBody = emptyArray<Array<Annotation>>()
        assertSame(missingBody, completeSuspendAnnotations(method, missingBody))
    }

    @Test fun leavesNonSuspendMethodsUntouched() {
        val method = String::class.java.getMethod("substring", Int::class.javaPrimitiveType)
        val truncated = emptyArray<Array<Annotation>>()
        assertSame(truncated, completeSuspendAnnotations(method, truncated))
    }

    @Test fun extractedRuntimeApisRemainInTheExactModuleCompatibilityRegistry() {
        val services = listOf(HostSongFavoritesApi::class.java, HostPlaylistTracksApi::class.java,
            HostPlaylistCollectionApi::class.java, HostCatalogCollectionApi::class.java, HostDownloadApi::class.java)
        services.forEach { service ->
            assertTrue(HostRetrofitCompatibility.services.contains(service))
            service.declaredMethods.forEach { method ->
                assertEquals(kotlin.coroutines.Continuation::class.java, method.parameterTypes.last())
                val original = method.parameterAnnotations
                assertEquals(method.parameterCount, original.size)
                val truncated = original.copyOfRange(0, original.size - 1)
                val result = completeSuspendAnnotations(method, truncated)
                assertEquals(method.parameterCount, result.size)
                truncated.indices.forEach { index -> assertSame(truncated[index], result[index]) }
                assertTrue(result.last().isEmpty())
                assertSame(original, completeSuspendAnnotations(method, original))
                val missingOwnedParameter = original.copyOfRange(0, original.size - 2)
                assertSame(missingOwnedParameter, completeSuspendAnnotations(method, missingOwnedParameter))
            }
        }
        assertEquals(11, HostRetrofitCompatibility.services.size)
        assertFalse(HostRetrofitCompatibility.services.contains(String::class.java))
    }
}
