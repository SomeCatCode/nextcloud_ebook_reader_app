package com.somecatcode.ebookreader.ui.util

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppLinksTest {

    @Test
    fun shareIntent_sendsTextWithThePlayStoreLinkOfTheReleaseApp() {
        val chooser = AppLinks.shareIntent(ApplicationProvider.getApplicationContext())
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        val text = send.getStringExtra(Intent.EXTRA_TEXT)!!
        assertTrue(text, text.endsWith("https://play.google.com/store/apps/details?id=com.somecatcode.ebookreader"))
    }
}
