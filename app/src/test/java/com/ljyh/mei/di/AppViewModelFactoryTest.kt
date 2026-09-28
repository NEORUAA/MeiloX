package com.ljyh.mei.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import javax.inject.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppViewModelFactoryTest {
    @Test fun delegatesOnlyRegisteredClasses() {
        var creations = 0
        val factory = AppViewModelFactory(mapOf(TrackedModel::class.java to Provider {
            creations++
            TrackedModel()
        }))
        assertEquals(setOf(TrackedModel::class.java), factory.registeredModels)
        assertNotSame(factory.create(TrackedModel::class.java), factory.create(TrackedModel::class.java))
        assertEquals(2, creations)
        assertThrows(IllegalArgumentException::class.java) { factory.create(ViewModel::class.java) }
        assertEquals(2, creations)
    }

    @Test fun retainsModelsPerOwnerAndKeyThenClearsThem() {
        val factory = AppViewModelFactory(mapOf(TrackedModel::class.java to Provider { TrackedModel() }))
        val first = Owner()
        val second = Owner()
        val firstProvider = ViewModelProvider(first, factory)
        val model = firstProvider[TrackedModel::class.java]
        assertSame(model, ViewModelProvider(first, factory)[TrackedModel::class.java])
        val keyed = firstProvider["another-entry", TrackedModel::class.java]
        val otherOwner = ViewModelProvider(second, factory)[TrackedModel::class.java]
        assertNotSame(model, keyed)
        assertNotSame(model, otherOwner)
        first.viewModelStore.clear()
        assertTrue(model.cleared && keyed.cleared)
        assertEquals(false, otherOwner.cleared)
        second.viewModelStore.clear()
        assertTrue(otherOwner.cleared)
    }

    @Test fun preservesProviderFailures() {
        val error = IllegalStateException("dependency unavailable")
        val factory = AppViewModelFactory(mapOf(TrackedModel::class.java to Provider { throw error }))
        assertSame(error, assertThrows(IllegalStateException::class.java) { factory.create(TrackedModel::class.java) })
    }

    private class Owner : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }

    private class TrackedModel : ViewModel() {
        var cleared = false
        override fun onCleared() { cleared = true }
    }
}
