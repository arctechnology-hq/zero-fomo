package com.arctechnology.zerofomo

import com.arctechnology.zerofomo.model.Geo
import org.junit.Assert.assertEquals
import org.junit.Test

class DistanceLabelTest {

    @Test fun `under a kilometre is not spuriously precise`() {
        assertEquals("< 1 km", Geo.distanceLabel(0.0))
        assertEquals("< 1 km", Geo.distanceLabel(0.94))
    }

    @Test fun `single digits keep one decimal`() {
        assertEquals("~1.0 km", Geo.distanceLabel(1.04))
        assertEquals("~3.5 km", Geo.distanceLabel(3.46))
        assertEquals("~9.9 km", Geo.distanceLabel(9.94))
    }

    @Test fun `ten and up round to whole kilometres`() {
        assertEquals("~10 km", Geo.distanceLabel(10.0))
        assertEquals("~12 km", Geo.distanceLabel(12.4))
        assertEquals("~13 km", Geo.distanceLabel(12.5))
        assertEquals("~318 km", Geo.distanceLabel(317.8))
    }

    @Test fun `nassau to miami is about 300 km`() {
        val km = Geo.distanceKm(25.06, -77.345, 25.7617, -80.1918)
        assertEquals(295.0, km, 5.0)
    }
}
