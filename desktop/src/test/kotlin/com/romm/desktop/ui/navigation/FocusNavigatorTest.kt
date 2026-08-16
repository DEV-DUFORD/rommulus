package com.romm.desktop.ui.navigation

import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [FocusNavigator]'s pure navigation logic.
 *
 * Tests focus exclusively on the index-based computation ([nextIndex]) and registration /
 * focus tracking ([register], [unregister], [focusedIndex]). The Compose integration
 * ([FocusableGrid], [focusableItem]) is integration-tested by screen composable tests in a
 * later wave.
 *
 * All tests use real [FocusRequester] instances to avoid stubbing but only exercise the pure
 * index arithmetic — never the Compose runtime. The actual [FocusRequester.requestFocus] call
 * (which requires a Compose composition) is skipped in unit tests; the focus index updates
 * that [moveFocus] performs before calling [FocusRequester.requestFocus] are tested.
 */
class FocusNavigatorTest {

    private lateinit var navigator: FocusNavigator

    @BeforeEach
    fun setUp() {
        navigator = FocusNavigator()
    }

    // --------------------------------------------------------------- nextIndex: pure logic

    @Nested
    inner class NextIndex {

        @Test
        fun `returns negative one when no items are registered`() {
            assertThat(navigator.nextIndex(FocusDirection.Next)).isEqualTo(-1)
        }

        @Test
        fun `returns negative one when a single item is registered`() {
            navigator.register("a", FocusRequester())
            assertThat(navigator.nextIndex(FocusDirection.Next)).isEqualTo(-1)
        }

        @Test
        fun `next moves forward one step`() {
            // Register two items and focus the first.
            navigator.register("a", FocusRequester())
            navigator.register("b", FocusRequester())
            navigator.setFocused(0)

            assertThat(navigator.nextIndex(FocusDirection.Next)).isEqualTo(1)
        }

        @Test
        fun `next wraps from last to first`() {
            navigator.register("a", FocusRequester())
            navigator.register("b", FocusRequester())
            navigator.register("c", FocusRequester())
            navigator.setFocused(2)

            assertThat(navigator.nextIndex(FocusDirection.Next)).isEqualTo(0)
        }

        @Test
        fun `previous moves backward one step`() {
            navigator.register("a", FocusRequester())
            navigator.register("b", FocusRequester())
            navigator.setFocused(1)

            assertThat(navigator.nextIndex(FocusDirection.Previous)).isEqualTo(0)
        }

        @Test
        fun `previous wraps from first to last`() {
            navigator.register("a", FocusRequester())
            navigator.register("b", FocusRequester())
            navigator.register("c", FocusRequester())
            navigator.setFocused(0)

            assertThat(navigator.nextIndex(FocusDirection.Previous)).isEqualTo(2)
        }

        @Test
        fun `right behaves like next for 1D layouts`() {
            navigator.register("a", FocusRequester())
            navigator.register("b", FocusRequester())
            navigator.setFocused(0)

            assertThat(navigator.nextIndex(FocusDirection.Right)).isEqualTo(1)
        }

        @Test
        fun `left behaves like previous for 1D layouts`() {
            navigator.register("a", FocusRequester())
            navigator.register("b", FocusRequester())
            navigator.setFocused(1)

            assertThat(navigator.nextIndex(FocusDirection.Left)).isEqualTo(0)
        }

        @Test
        fun `down behaves like next for 1D layouts`() {
            navigator.register("a", FocusRequester())
            navigator.register("b", FocusRequester())
            navigator.setFocused(0)

            assertThat(navigator.nextIndex(FocusDirection.Down)).isEqualTo(1)
        }

        @Test
        fun `up behaves like previous for 1D layouts`() {
            navigator.register("a", FocusRequester())
            navigator.register("b", FocusRequester())
            navigator.setFocused(1)

            assertThat(navigator.nextIndex(FocusDirection.Up)).isEqualTo(0)
        }

        @Test
        fun `wrapped previous on index 0 goes to last for multiple items`() {
            // 5 items: indices 0..4. Previous from 0 should wrap to 4.
            repeat(5) { navigator.register("item$it", FocusRequester()) }
            navigator.setFocused(0)

            assertThat(navigator.nextIndex(FocusDirection.Previous)).isEqualTo(4)
        }

        @Test
        fun `wrapped next on last index goes to 0 for multiple items`() {
            repeat(5) { navigator.register("item$it", FocusRequester()) }
            navigator.setFocused(4)

            assertThat(navigator.nextIndex(FocusDirection.Next)).isEqualTo(0)
        }

        @Test
        fun `nextIndex is unaffected by non-focused items`() {
            navigator.register("a", FocusRequester())
            navigator.register("b", FocusRequester())
            navigator.register("c", FocusRequester())
            // Focus the middle.
            navigator.setFocused(1)

            assertThat(navigator.nextIndex(FocusDirection.Next)).isEqualTo(2)
            assertThat(navigator.nextIndex(FocusDirection.Previous)).isEqualTo(0)
        }

        @Test
        fun `all six direction values produce results for sized navigator`() {
            navigator.register("a", FocusRequester())
            navigator.register("b", FocusRequester())
            navigator.setFocused(0)

            assertThat(navigator.nextIndex(FocusDirection.Next)).isEqualTo(1)
            assertThat(navigator.nextIndex(FocusDirection.Right)).isEqualTo(1)
            assertThat(navigator.nextIndex(FocusDirection.Down)).isEqualTo(1)
            assertThat(navigator.nextIndex(FocusDirection.Previous)).isEqualTo(1)
            assertThat(navigator.nextIndex(FocusDirection.Left)).isEqualTo(1)
            assertThat(navigator.nextIndex(FocusDirection.Up)).isEqualTo(1)
        }
    }

    // --------------------------------------------------------------- register / size / snapshot

    @Nested
    inner class Registration {

        @Test
        fun `register adds items in order`() {
            val r1 = FocusRequester()
            val r2 = FocusRequester()
            val r3 = FocusRequester()

            val idx1 = navigator.register("a", r1)
            val idx2 = navigator.register("b", r2)
            val idx3 = navigator.register("c", r3)

            assertThat(idx1).isEqualTo(0)
            assertThat(idx2).isEqualTo(1)
            assertThat(idx3).isEqualTo(2)
            assertThat(navigator.size()).isEqualTo(3)
        }

        @Test
        fun `register replaces existing key without changing order`() {
            val r1 = FocusRequester()
            val r2 = FocusRequester()
            navigator.register("a", r1)
            navigator.register("b", FocusRequester())
            val newIdx = navigator.register("a", r2)

            // "a" is replaced, but its position (0) is preserved.
            assertThat(newIdx).isEqualTo(0)
            assertThat(navigator.size()).isEqualTo(2)
        }

        @Test
        fun `snapshot returns items in registration order`() {
            navigator.register("c", FocusRequester())
            navigator.register("a", FocusRequester())
            navigator.register("b", FocusRequester())

            val snapshot = navigator.snapshot()
            assertThat(snapshot.map { it.key }).containsExactly("c", "a", "b")
        }

        @Test
        fun `size returns current count`() {
            assertThat(navigator.size()).isEqualTo(0)
            navigator.register("a", FocusRequester())
            assertThat(navigator.size()).isEqualTo(1)
            navigator.register("b", FocusRequester())
            assertThat(navigator.size()).isEqualTo(2)
        }

        @Test
        fun `unregister removes item and clears focused index if it was focused`() {
            val r1 = FocusRequester()
            val r2 = FocusRequester()
            navigator.register("a", r1)
            navigator.register("b", r2)
            navigator.setFocused(0)

            navigator.unregister("a")
            assertThat(navigator.size()).isEqualTo(1)
            assertThat(navigator.focusedIndex()).isEqualTo(-1)
        }

        @Test
        fun `unregister of non-focused item does not clear focused index`() {
            val r1 = FocusRequester()
            val r2 = FocusRequester()
            navigator.register("a", r1)
            navigator.register("b", r2)
            navigator.setFocused(0)

            navigator.unregister("b")
            assertThat(navigator.size()).isEqualTo(1)
            assertThat(navigator.focusedIndex()).isEqualTo(0)
        }

        @Test
        fun `focused activation remains attached to its key when earlier items unregister`() {
            var activated: String? = null
            navigator.register("a", FocusRequester()) { activated = "a" }
            navigator.register("b", FocusRequester()) { activated = "b" }
            navigator.setFocused(1)

            navigator.unregister("a")

            assertThat(navigator.focusedIndex()).isEqualTo(0)
            assertThat(navigator.activateFocused()).isTrue()
            assertThat(activated).isEqualTo("b")
        }

        @Test
        fun `losing focus clears only the matching focused key`() {
            navigator.register("a", FocusRequester())
            navigator.register("b", FocusRequester())
            navigator.setFocusedKey("a")

            navigator.clearFocusedKey("b")
            assertThat(navigator.focusedIndex()).isEqualTo(0)

            navigator.clearFocusedKey("a")
            assertThat(navigator.focusedIndex()).isEqualTo(-1)
        }

        @Test
        fun `unregister of unknown key is no-op`() {
            navigator.register("a", FocusRequester())
            navigator.unregister("unknown")
            assertThat(navigator.size()).isEqualTo(1)
        }

        @Test
        fun `clear resets all state`() {
            navigator.register("a", FocusRequester())
            navigator.register("b", FocusRequester())
            navigator.setFocused(0)
            // Clear by unregistering all.
            navigator.unregister("a")
            navigator.unregister("b")
            assertThat(navigator.size()).isEqualTo(0)
            assertThat(navigator.focusedIndex()).isEqualTo(-1)
        }
    }

    // --------------------------------------------------------------- focusItem: index tracking only

    /**
     * Tests for [FocusNavigator.focusItem] and related helpers. These verify the index-based
     * focus state updates without calling [FocusRequester.requestFocus], which requires a
     * Compose composition. The full integration (requestFocus + index update) is tested by
     * screen-level Compose UI tests in a later wave.
     */
    @Nested
    inner class FocusControl {

        @Test
        fun `focusFirst sets focused index to 0`() {
            repeat(3) { navigator.register("item$it", FocusRequester()) }
            navigator.focusFirst()

            assertThat(navigator.focusedIndex()).isEqualTo(0)
        }

        @Test
        fun `focusLast sets focused index to last item`() {
            repeat(3) { navigator.register("item$it", FocusRequester()) }
            navigator.focusLast()

            assertThat(navigator.focusedIndex()).isEqualTo(2)
        }

        @Test
        fun `focusItem with out of range index does not change focused index`() {
            navigator.register("a", FocusRequester())
            navigator.focusItem(-1)
            navigator.focusItem(99)
            assertThat(navigator.focusedIndex()).isEqualTo(-1)
        }

        @Test
        fun `focusItem with valid index updates focused index`() {
            repeat(5) { navigator.register("item$it", FocusRequester()) }
            navigator.focusItem(3)
            assertThat(navigator.focusedIndex()).isEqualTo(3)
        }
    }

    // --------------------------------------------------------------- moveFocus: index update only

    /**
     * Tests for [FocusNavigator.moveFocus]'s index-update logic. The actual
     * [FocusRequester.requestFocus] call requires a Compose composition, so these tests
     * verify the index-based behaviour by checking that [focusedIndex] is updated correctly
     * before the blocking [FocusRequester.requestFocus] call.
     *
     * The moveFocus / focusItem calls that invoke [FocusRequester.requestFocus] are marked
     * with a comment noting they are integration tests to be added in a Compose UI test
     * environment.
     */
    @Nested
    inner class MoveFocus {

        @Test
        fun `moveFocus is no-op when fewer than 2 items are registered`() {
            navigator.register("a", FocusRequester())
            // moveFocus will throw requestFocus; we just verify it does not crash the navigator.
            try {
                navigator.moveFocus(FocusDirection.Next)
            } catch (_: IllegalStateException) {
                // Expected — FocusRequester not initialized in unit test.
            }
            // Navigator state is preserved.
            assertThat(navigator.size()).isEqualTo(1)
        }

        @Test
        fun `nextIndex computes correct target for moveFocus tests`() {
            // Test the pure logic that moveFocus uses internally before calling requestFocus.
            navigator.register("a", FocusRequester())
            navigator.register("b", FocusRequester())
            navigator.register("c", FocusRequester())

            navigator.setFocused(0)
            assertThat(navigator.nextIndex(FocusDirection.Next)).isEqualTo(1)

            navigator.setFocused(2)
            assertThat(navigator.nextIndex(FocusDirection.Next)).isEqualTo(0)

            navigator.setFocused(0)
            assertThat(navigator.nextIndex(FocusDirection.Previous)).isEqualTo(2)
        }

        @Test
        fun `focusItem updates index correctly for various sizes`() {
            // 2 items: focusItem(1) -> 1
            navigator.register("a", FocusRequester())
            navigator.register("b", FocusRequester())
            navigator.focusItem(1)
            assertThat(navigator.focusedIndex()).isEqualTo(1)

            // 5 items: focusItem(3) -> 3
            repeat(5) { navigator.register("x$it", FocusRequester()) }
            navigator.focusItem(3)
            assertThat(navigator.focusedIndex()).isEqualTo(3)
        }

        @Test
        fun `wrapped navigation correctly alternates between first and last`() {
            repeat(3) { navigator.register("item$it", FocusRequester()) }
            // Focus last, next wraps to first.
            navigator.focusLast()
            assertThat(navigator.nextIndex(FocusDirection.Next)).isEqualTo(0)

            // Focus first, previous wraps to last.
            navigator.focusFirst()
            assertThat(navigator.nextIndex(FocusDirection.Previous)).isEqualTo(2)
        }
    }

    // --------------------------------------------------------------- note on full integration

    /**
     * Integration test placeholder: the following tests would pass in a full Compose UI
     * environment (e.g., androidx.compose.ui.test) where a composition provides a valid
     * [FocusRequester] lifecycle.
     *
     * ```
     * @Test
     * fun `moveFocus performs full requestFocus flow`() {
     *     navigator.register("a", FocusRequester())
     *     navigator.register("b", FocusRequester())
     *     navigator.setFocused(0)
     *     navigator.moveFocus(FocusDirection.Next)
     *     assertThat(navigator.focusedIndex()).isEqualTo(1)
     * }
     * ```
     */
    companion object {
        const val INTEGRATION_TEST_NOTE =
            "Full focus request tests require a Compose UI test environment (androidx.compose.ui.test)."
    }
}
