package com.nutrilens

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Instrumentation runner that swaps in Hilt's test application.
 *
 * Without it the real [NutriLensApplication] would run, taking its whole
 * dependency graph -- including the periodic sync worker -- into every UI test.
 */
class NutriLensTestRunner : AndroidJUnitRunner() {

    override fun newApplication(
        classLoader: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = super.newApplication(classLoader, HiltTestApplication::class.java.name, context)
}
