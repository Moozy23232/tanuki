package com.tanuki

import com.tanuki.update.VersionOrder
import org.junit.Assert.*
import org.junit.Test

class VersionOrderTest {
    @Test fun prereleasesAndNumericPartsFollowSemverOrder() {
        val ordered=listOf("0.2.1-alpha.2","0.2.1-alpha.10","0.2.1-beta","0.2.1-rc.5","0.2.1-rc.10","0.2.1","0.2.2-alpha.2","0.2.2","0.10.0","1.0.0")
        for((index,version) in ordered.withIndex()) {
            assertEquals(0,VersionOrder.compare(version,version))
            for(newer in ordered.drop(index+1)) {
                assertTrue("$version must precede $newer",VersionOrder.compare(version,newer)<0)
                assertTrue("$newer must follow $version",VersionOrder.compare(newer,version)>0)
            }
        }
    }
}
