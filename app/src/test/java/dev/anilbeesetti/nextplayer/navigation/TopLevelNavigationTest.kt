package dev.anilbeesetti.nextplayer.navigation

import androidx.compose.runtime.mutableIntStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import dev.anilbeesetti.nextplayer.settings.navigation.AppearancePreferencesRoute
import dev.anilbeesetti.nextplayer.settings.navigation.SettingsRoute
import org.junit.Assert.assertEquals
import org.junit.Test

class TopLevelNavigationTest {

    @Test
    fun topLevelDestinationsAreInOrder() {
        assertEquals(
            listOf(
                TopLevelDestination.MEDIA,
                TopLevelDestination.NETWORK,
                TopLevelDestination.SETTINGS,
                TopLevelDestination.MORE,
            ),
            TopLevelDestination.entries,
        )
    }

    @Test
    fun switchingTabsPreservesSettingsDetailStack() {
        val stacks = TopLevelDestination.entries.associate { destination ->
            destination.route to NavBackStack<NavKey>(destination.route)
        }
        val state = TopLevelNavState(
            destinations = TopLevelDestination.entries,
            backStacks = stacks,
            selectedIndexState = mutableIntStateOf(value = 0),
        )
        val settingsStack = stacks.getValue(TopLevelDestination.SETTINGS.route)

        state.switchTo(TopLevelDestination.SETTINGS.route)
        settingsStack += AppearancePreferencesRoute
        state.switchTo(TopLevelDestination.MEDIA.route)
        state.switchTo(TopLevelDestination.SETTINGS.route)

        assertEquals(
            listOf(
                TopLevelDestination.SETTINGS.route,
                AppearancePreferencesRoute,
            ),
            state.currentStack,
        )
    }

    @Test
    fun goBackFromSettingsRootReturnsToMediaTab() {
        val stacks = TopLevelDestination.entries.associate { destination ->
            destination.route to NavBackStack<NavKey>(destination.route)
        }
        val state = TopLevelNavState(
            destinations = TopLevelDestination.entries,
            backStacks = stacks,
            selectedIndexState = mutableIntStateOf(value = 0),
        )

        state.switchTo(TopLevelDestination.SETTINGS.route)
        state.goBack()

        assertEquals(
            SettingsRoute,
            stacks.getValue(TopLevelDestination.SETTINGS.route).first(),
        )
        assertEquals(
            TopLevelDestination.MEDIA.route,
            state.topLevelRoute,
        )
    }

    @Test
    fun topLevelContentKeysContainsDestinations() {
        val stacks = TopLevelDestination.entries.associate { destination ->
            destination.route to NavBackStack<NavKey>(destination.route)
        }
        val state = TopLevelNavState(
            destinations = TopLevelDestination.entries,
            backStacks = stacks,
            selectedIndexState = mutableIntStateOf(value = 0),
        )
        for (dest in TopLevelDestination.entries) {
            assert(state.topLevelContentKeys.contains(dest.route))
            assert(
                state.topLevelContentKeys.contains(
                    element = Pair(
                        first = "${dest.route}",
                        second = "${dest.route::class}",
                    ),
                ),
            )
        }
    }
}
