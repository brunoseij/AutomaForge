package com.automaforge.android

import android.app.Application
import com.automaforge.android.di.DaggerAppComponent

class AutomaForgeApplication : Application() {
    val graph by lazy { DaggerAppComponent.factory().create(applicationContext) }
}
