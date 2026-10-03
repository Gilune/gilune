package org.gilune.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher

// Real IO completions must queue UI effects, not resume them on their producer
// thread. Compose's main clock pumps this scheduler on the Android UI thread.
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
internal fun createDeviceComposeRule() =
    androidx.compose.ui.test.junit4.createComposeRule(effectContext = StandardTestDispatcher())

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
internal inline fun <reified A : ComponentActivity> createDeviceAndroidComposeRule() =
    androidx.compose.ui.test.junit4.createAndroidComposeRule<A>(effectContext = StandardTestDispatcher())
