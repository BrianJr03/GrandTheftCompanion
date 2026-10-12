package jr.brian.thorhud.ui

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import jr.brian.thorhud.GtcSettings
import jr.brian.thorhud.led.StickLights
import jr.brian.thorhud.model.GameState
import jr.brian.thorhud.net.GameStateRepository
import jr.brian.thorhud.prefs.HudPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * [MainViewModel.pickWeapon] against a mocked repository. The four
 * outcomes map one-to-one to how the UI surfaces them: no error ->
 * selected and waiting for the game, "refused" / timeout / "not
 * connected" -> error line in the wheel hub.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelPickWeaponTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: GameStateRepository
    private lateinit var ackFlow: MutableStateFlow<GameState?>

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        ackFlow = MutableStateFlow(null)
        repository = mockk(relaxed = true)
        // The ViewModel reads these as flows at construction time.
        every { repository.ack } returns ackFlow
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(): MainViewModel {
        // The weapon-wheel path only touches [GameStateRepository]; the
        // other collaborators are relaxed so the ViewModel can be built
        // without a real Context.
        val settings = mockk<GtcSettings>(relaxed = true).also {
            every { it.policeLightsEnabled } returns MutableStateFlow(false)
        }
        val prefs = mockk<HudPrefs>(relaxed = true)
        val lights = mockk<StickLights>(relaxed = true).also {
            every { it.status } returns MutableStateFlow(StickLights.Status.Unknown)
        }
        return MainViewModel(repository, settings, prefs, lights)
    }

    @Test fun ackOkLeavesPickSelectedWithoutError() = runTest(dispatcher) {
        coEvery { repository.setWeapon(KNIFE_HASH) } returns 7
        val vm = newViewModel()

        vm.weaponPick.test {
            assertNull(awaitItem())            // starting value

            vm.pickWeapon(KNIFE_HASH)
            // The optimistic value appears before the ack lands.
            assertEquals(WeaponPick(KNIFE_HASH), awaitItem())

            ackFlow.value = GameState(type = "ack", ackId = 7, ok = true)
            advanceUntilIdle()
            // ack ok -> no further updates; MainScreen clears the pick
            // once the game reports the weapon equipped.
            expectNoEvents()
        }
    }

    @Test fun ackRefusedSurfacesTheReason() = runTest(dispatcher) {
        coEvery { repository.setWeapon(KNIFE_HASH) } returns 8
        val vm = newViewModel()

        vm.weaponPick.test {
            assertNull(awaitItem())
            vm.pickWeapon(KNIFE_HASH)
            assertEquals(WeaponPick(KNIFE_HASH), awaitItem())

            ackFlow.value = GameState(
                type = "ack", ackId = 8, ok = false, err = "weapon not owned",
            )
            advanceUntilIdle()
            assertEquals(
                WeaponPick(KNIFE_HASH, "weapon not owned"),
                awaitItem(),
            )
        }
    }

    @Test fun noAckWithinTimeoutMarksTheRequestLost() = runTest(dispatcher) {
        coEvery { repository.setWeapon(KNIFE_HASH) } returns 9
        val vm = newViewModel()

        vm.weaponPick.test {
            assertNull(awaitItem())
            vm.pickWeapon(KNIFE_HASH)
            assertEquals(WeaponPick(KNIFE_HASH), awaitItem())

            // Push past the ViewModel's ACK_TIMEOUT_MS (2500 ms) without
            // emitting an ack. The pick turns into a "no reply" error.
            advanceTimeBy(3000)
            assertEquals(
                WeaponPick(KNIFE_HASH, "no reply from the game"),
                awaitItem(),
            )
        }
    }

    @Test fun notConnectedShortCircuitsBeforeWaitingForAck() = runTest(dispatcher) {
        // GameStateClient.send returns null when there is no live socket;
        // the repository forwards that straight through.
        coEvery { repository.setWeapon(KNIFE_HASH) } returns null
        val vm = newViewModel()

        vm.weaponPick.test {
            assertNull(awaitItem())
            vm.pickWeapon(KNIFE_HASH)
            assertEquals(WeaponPick(KNIFE_HASH), awaitItem())

            advanceUntilIdle()
            assertEquals(
                WeaponPick(KNIFE_HASH, "not connected"),
                awaitItem(),
            )
        }
    }

    @Test fun secondPickCancelsTheFirstOnesWaitForAck() = runTest(dispatcher) {
        coEvery { repository.setWeapon(KNIFE_HASH) } returns 10
        coEvery { repository.setWeapon(PISTOL_HASH) } returns 11
        val vm = newViewModel()

        vm.weaponPick.test {
            assertNull(awaitItem())
            vm.pickWeapon(KNIFE_HASH)
            assertEquals(WeaponPick(KNIFE_HASH), awaitItem())

            // Second tap before the first one timed out: optimistic
            // value flips to the new weapon.
            vm.pickWeapon(PISTOL_HASH)
            assertEquals(WeaponPick(PISTOL_HASH), awaitItem())

            // Ack for the FIRST pick arrives late. Its awaiter was
            // cancelled, so nothing updates.
            ackFlow.value = GameState(
                type = "ack", ackId = 10, ok = false, err = "stale",
            )
            advanceTimeBy(500)
            expectNoEvents()

            // Ack for the second pick lands clean.
            ackFlow.value = GameState(type = "ack", ackId = 11, ok = true)
            advanceUntilIdle()
            expectNoEvents()
        }
    }

    private companion object {
        // joaat("weapon_knife"), the knife is one of the weapons whose
        // hash is above Int.MAX_VALUE and so would be mis-sent as an Int.
        const val KNIFE_HASH = 2578778090L

        // joaat("weapon_pistol"), under Int.MAX_VALUE for contrast.
        const val PISTOL_HASH = 453432689L
    }
}
