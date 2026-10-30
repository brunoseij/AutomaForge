package com.automaforge.android

import androidx.lifecycle.*
import com.automaforge.android.domain.model.*
import com.automaforge.android.domain.repository.*
import com.automaforge.android.domain.usecase.*
import com.automaforge.android.presentation.camera.CameraViewModel
import com.automaforge.android.presentation.main.MainViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MvvmStateTest {
    private val dispatcher = StandardTestDispatcher()
    private val owners = mutableListOf<ViewModelStore>()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun close() {
        owners.forEach { it.clear() }
        Dispatchers.resetMain()
    }

    private fun <T : ViewModel> retain(vm: T) {
        owners += ViewModelStore().apply { put("test", vm) }
    }

    private class Games : GameLibraryRepository {
        val games = MutableStateFlow(emptyList<GameSummary>())

        override suspend fun initialize() = 0

        override fun observeGames() = games

        override suspend fun requireGame(code: String) {
            require(games.value.any { it.code == code })
        }
    }

    private class Host : HostRepository {
        var opened: SessionTarget? = null
        var closes = 0

        override suspend fun open(target: SessionTarget) =
            SessionConnection(target, "192.168.1.20").also { opened = target }

        override fun close() {
            closes++
        }
    }

    @Test
    fun controllerAndRouteSurviveSavedStateWithoutSecretsOrFrameworkViews() =
        runTest(dispatcher) {
            val host = Host()
            val games = Games()
            val saved = SavedStateHandle()
            val vm = MainViewModel(SessionActions(host, games), saved)
            retain(vm)
            advanceUntilIdle()
            vm.startController()
            advanceUntilIdle()
            vm.rememberLocation("http://127.0.0.1:8765/?room=ABCDEFGH&role=controller")
            assertTrue(vm.state.value.connection!!.target.controller)
            assertFalse(vm.state.value.busy)
            val restored = MainViewModel(SessionActions(host, games), saved)
            retain(restored)
            advanceUntilIdle()
            assertTrue(restored.state.value.currentUrl.contains("room=ABCDEFGH"))
            assertFalse(saved.keys().any { it.contains("key") || it.contains("image") })
        }

    @Test
    fun invalidOrExternalConnectionAndBridgeRoutesAreRejected() =
        runTest(dispatcher) {
            val host = Host()
            val vm = MainViewModel(SessionActions(host, Games()), SavedStateHandle())
            retain(vm)
            advanceUntilIdle()
            vm.connect("8.8.8.8")
            advanceUntilIdle()
            assertNull(host.opened)
            assertNotNull(vm.state.value.error)
            vm.connect("192.168.1.20")
            advanceUntilIdle()
            val old = vm.state.value.currentUrl
            vm.rememberLocation("https://example.com/")
            assertEquals(old, vm.state.value.currentUrl)
            vm.leave()
            assertNull(vm.state.value.connection)
            assertEquals(1, host.closes)
        }

    @Test
    fun committedLibraryChangesUpdateViewModelWithoutPollingOrRoomDetailsInUi() =
        runTest(dispatcher) {
            val games = Games()
            val vm = MainViewModel(SessionActions(Host(), games), SavedStateHandle())
            retain(vm)
            advanceUntilIdle()
            games.games.value = listOf(GameSummary("ABCDEFGH", "Updated", 4, "Play", true))
            advanceUntilIdle()
            assertEquals("Updated", vm.state.value.games.single().name)
            assertTrue(vm.state.value.games.single().pendingProposal)
        }

    @Test
    fun privateCaptureBackpressureAllowsOnlyOneUploadAndDropsExtraImages() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            var calls = 0
            val repository =
                object : CaptureRepository {
                    override suspend fun upload(request: CameraRequest, bytes: ByteArray): String {
                        calls++
                        gate.await()
                        return "Private card read"
                    }
                }
            val vm = CameraViewModel(UploadCapture(repository))
            retain(vm)
            val request = CameraRequest("192.168.1.20", "ABCDEFGH", "cards")
            vm.image(request, byteArrayOf(1))
            runCurrent()
            vm.image(request, byteArrayOf(2))
            runCurrent()
            assertEquals(1, calls)
            assertTrue(vm.state.value.uploading)
            gate.complete(Unit)
            advanceUntilIdle()
            assertFalse(vm.state.value.uploading)
        }

    @Test
    fun cameraTargetValidationPreventsUploadToExternalHostsOrInvalidRooms() =
        runTest(dispatcher) {
            var called = false
            val upload =
                UploadCapture(
                    object : CaptureRepository {
                        override suspend fun upload(
                            request: CameraRequest,
                            bytes: ByteArray,
                        ): String {
                            called = true
                            return "ok"
                        }
                    }
                )
            for (request in
                listOf(
                    CameraRequest("8.8.8.8", "ABCDEFGH", "board"),
                    CameraRequest("192.168.1.20", "bad", "cards"),
                )) {
                try {
                    upload(request, byteArrayOf(1))
                    fail()
                } catch (expected: IllegalArgumentException) {}
            }
            assertFalse(called)
        }
}
