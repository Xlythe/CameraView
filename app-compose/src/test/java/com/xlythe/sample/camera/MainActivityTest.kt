package com.xlythe.sample.camera

import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(minSdk = 24)
class MainActivityTest {

    @Test
    fun testActivityCreation() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().get()
        assertNotNull(activity)
    }
}
