package com.ljyh.mei.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class AppViewModelFactory @Inject constructor(
    private val creators: Map<Class<*>, @JvmSuppressWildcards Provider<ViewModel>>,
) : ViewModelProvider.Factory {
    val registeredModels: Set<Class<*>> get() = creators.keys

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val creator = requireNotNull(creators[modelClass]) { "Unregistered ViewModel: ${modelClass.name}" }
        return modelClass.cast(creator.get())!!
    }
}
