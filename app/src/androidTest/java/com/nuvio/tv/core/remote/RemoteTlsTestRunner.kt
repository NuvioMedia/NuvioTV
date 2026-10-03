package com.nuvio.tv.core.remote

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Isolate the TLS tests from account, catalog and player startup. Opt in via the runner argument. */
class RemoteTlsTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)
}
