package com.ljyh.mei.standalone

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Fixture startup is offline even when a later test explicitly opts into server reads. */
class StandaloneFixtureInstrumentation : AndroidJUnitRunner() {
    override fun newApplication(loader: ClassLoader, name: String, context: Context): Application {
        // Android creates the application before onCreate registers instrumentation arguments.
        return super.newApplication(loader, StandaloneFixtureApplication::class.java.name, context)
    }
}
