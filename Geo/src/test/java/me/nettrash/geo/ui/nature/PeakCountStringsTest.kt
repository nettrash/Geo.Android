package me.nettrash.geo.ui.nature

import android.content.res.Resources
import com.google.common.truth.Truth.assertThat
import me.nettrash.geo.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The Nature top bar's "the floor is hiding everything" pill (#25). One peak
 * reads "hides 1 peak" — the plural's `one` item used to say "hides all 1
 * peak" — and more read "hides all N peaks". Mirrors iOS.
 */
@RunWith(RobolectricTestRunner::class)
class PeakCountStringsTest {

    private val res: Resources by lazy { RuntimeEnvironment.getApplication().resources }

    @Test
    fun theFloorPillCountsOnePeakWithoutAll() {
        assertThat(res.getQuantityString(R.plurals.nature_peaks_floor_hides_all, 1, 600, 1))
            .isEqualTo("600 m floor hides 1 peak")
        assertThat(res.getQuantityString(R.plurals.nature_peaks_floor_hides_all, 3, 600, 3))
            .isEqualTo("600 m floor hides all 3 peaks")
    }
}
