package com.automaforge.android

import androidx.lifecycle.ViewModelStore
import com.automaforge.android.domain.model.*
import com.automaforge.android.domain.repository.AiConfigurationRepository
import com.automaforge.android.domain.usecase.SettingsActions
import com.automaforge.android.presentation.settings.AiSettingsViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** ViewModel events and settings serialization on the JVM; no Compose/UI execution. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val owner = ViewModelStore()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun close() {
        owner.clear()
        Dispatchers.resetMain()
    }

    private class Settings : AiConfigurationRepository {
        var config =
            AiConfiguration(
                "gpt-5.4",
                "default",
                listOf(
                    ModelOption(
                        "gpt-5.4",
                        "Reasoning model",
                        listOf(
                            ReasoningOption("default", "Default"),
                            ReasoningOption("high", "High"),
                        ),
                    ),
                    ModelOption(
                        "gpt-4.1-mini",
                        "Without adjustment",
                        listOf(ReasoningOption("default", "Default")),
                    ),
                ),
                true,
            )
        var calls = 0
        var clears = 0
        var fail = false
        var gate: CompletableDeferred<Unit>? = null

        override fun read() = config

        override suspend fun save(model: String, effort: String, newKey: String) {
            calls++
            gate?.await()
            check(!fail) { "Simulated save failure" }
            config =
                config.copy(
                    model = model,
                    effort = effort,
                    keyConfigured = config.keyConfigured || newKey.isNotBlank(),
                )
        }

        override suspend fun clearKey() {
            clears++
            config = config.copy(keyConfigured = false)
        }
    }

    private fun vm(repository: Settings): AiSettingsViewModel =
        AiSettingsViewModel(SettingsActions(repository), dispatcher).also {
            owner.put("settings", it)
        }

    @Test
    fun savingDoesNotCloseAReopenedDialogThroughAReplayedEvent() =
        runTest(dispatcher) {
            val repository = Settings()
            val vm = vm(repository)
            vm.reload()
            advanceUntilIdle()
            var events = 0
            var collector =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    vm.savedEvents.collect { events++ }
                }
            vm.chooseEffort("high")
            vm.save("")
            advanceUntilIdle()
            assertEquals(1, events)
            collector.cancel()
            vm.reload()
            advanceUntilIdle()
            collector =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    vm.savedEvents.collect { events++ }
                }
            runCurrent()
            assertEquals(1, events)
            assertEquals("high", vm.state.value.effort)
            vm.save("")
            advanceUntilIdle()
            assertEquals(2, events)
            collector.cancel()
        }

    @Test
    fun clearKeyCannotRaceWithAnInFlightSave() =
        runTest(dispatcher) {
            val repository = Settings()
            val vm = vm(repository)
            vm.reload()
            advanceUntilIdle()
            repository.gate = CompletableDeferred()
            vm.save("")
            runCurrent()
            vm.clearKey()
            runCurrent()
            assertTrue(vm.state.value.saving)
            assertEquals(0, repository.clears)
            repository.gate!!.complete(Unit)
            advanceUntilIdle()
            vm.clearKey()
            advanceUntilIdle()
            assertEquals(1, repository.clears)
            assertFalse(vm.state.value.configuration!!.keyConfigured)
            assertEquals("gpt-5.4", vm.state.value.model)
        }

    @Test
    fun modelChoiceKeepsOnlySupportedEfforts() =
        runTest(dispatcher) {
            val vm = vm(Settings())
            vm.reload()
            advanceUntilIdle()
            vm.chooseEffort("high")
            assertEquals("high", vm.state.value.effort)
            vm.chooseModel("gpt-4.1-mini")
            assertEquals("default", vm.state.value.effort)
            vm.chooseEffort("high")
            assertEquals("default", vm.state.value.effort)
        }

    @Test
    fun failurePreservesSelectionAndDoesNotEmitSuccess() =
        runTest(dispatcher) {
            val repository = Settings()
            val vm = vm(repository)
            vm.reload()
            advanceUntilIdle()
            var events = 0
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.savedEvents.collect { events++ }
            }
            repository.fail = true
            vm.chooseEffort("high")
            vm.save("")
            advanceUntilIdle()
            assertEquals(0, events)
            assertNotNull(vm.state.value.error)
            assertFalse(vm.state.value.saving)
            assertEquals("high", vm.state.value.effort)
        }

    @Test
    fun cancellationDoesNotBecomeAnErrorOrSuccess() =
        runTest(dispatcher) {
            val repository = Settings()
            val vm = vm(repository)
            vm.reload()
            advanceUntilIdle()
            var events = 0
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.savedEvents.collect { events++ }
            }
            repository.gate = CompletableDeferred()
            vm.save("")
            runCurrent()
            owner.clear()
            runCurrent()
            assertNull(vm.state.value.error)
            assertFalse(vm.state.value.saving)
            assertEquals(0, events)
        }
}
