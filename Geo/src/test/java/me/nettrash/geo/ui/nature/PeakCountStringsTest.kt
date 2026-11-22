package me.nettrash.geo.ui.nature

import android.content.res.Resources
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The Nature top bar's "the floor is hiding everything" pill (#25). One peak
 * reads "hides 1 peak", any other count "hides all N peaks". The count picks
 * the wording, not the device's plural rules: the app ships English text only,
 * so a plural's items were chosen by the rules of whatever language the phone
 * is set to. A Japanese phone has no `one` category and read "hides all 1
 * peaks"; a Russian one counts 21 as `one` and read "hides 21 peak". Mirrors
 * iOS `minAltitudeFloorPillText`.
 */
@RunWith(RobolectricTestRunner::class)
class PeakCountStringsTest {

    private val res: Resources by lazy { RuntimeEnvironment.getApplication().resources }

    @Test
    @Config(qualifiers = "en")
    fun onePeakReadsInTheSingular() {
        assertThat(minAltitudeFloorPillText(res, floorMetres = 600, hiddenCount = 1))
            .isEqualTo("600 m floor hides 1 peak")
    }

    @Test
    @Config(qualifiers = "en")
    fun morePeaksReadAll() {
        assertThat(minAltitudeFloorPillText(res, floorMetres = 600, hiddenCount = 2))
            .isEqualTo("600 m floor hides all 2 peaks")
    }

    @Test
    @Config(qualifiers = "ja")
    fun onePeakReadsInTheSingularOnAJapanesePhone() {
        assertThat(res.configuration.locales[0].language).isEqualTo("ja")
        assertThat(minAltitudeFloorPillText(res, floorMetres = 600, hiddenCount = 1))
            .isEqualTo("600 m floor hides 1 peak")
    }

    @Test
    @Config(qualifiers = "ru")
    fun twentyOnePeaksReadAllOnARussianPhone() {
        assertThat(res.configuration.locales[0].language).isEqualTo("ru")
        assertThat(minAltitudeFloorPillText(res, floorMetres = 600, hiddenCount = 21))
            .isEqualTo("600 m floor hides all 21 peaks")
    }
}
