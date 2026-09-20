package coredevices.coreapp.automation.trust

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import coredevices.coreapp.automation.AutomationSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[34])
class AuthorityRevisionTest {
    @Test fun privacyAndMasterOffOnRotateAndPersistAuthorityEvenWhenFinalValuesMatch() {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val trust=ClientTrustStore(context)
        val original=trust.authorityRevision
        trust.setMasterEnabled(true)
        trust.setMasterEnabled(false)
        assertNotEquals(original,trust.authorityRevision)
        assertEquals(trust.authorityRevision,ClientTrustStore(context).authorityRevision)
        val settings=AutomationSettings(context)
        val privacy=settings.authorityRevision
        settings.setNotificationContentEnabled(true)
        settings.setNotificationContentEnabled(false)
        assertNotEquals(privacy,settings.authorityRevision)
        assertEquals(settings.authorityRevision,AutomationSettings(context).authorityRevision)
    }
    @Test fun anotherClientDecisionDoesNotRevokeThisClientsPendingEvents() {
        val trust=ClientTrustStore(ApplicationProvider.getApplicationContext<Application>())
        val master=trust.authorityRevision
        val a=trust.clientAuthorityRevision("a")
        val b=trust.clientAuthorityRevision("b")
        trust.deny("b")
        assertEquals(master,trust.authorityRevision)
        assertEquals(a,trust.clientAuthorityRevision("a"))
        assertNotEquals(b,trust.clientAuthorityRevision("b"))
        trust.reconsider("a")
        assertNotEquals(a,trust.clientAuthorityRevision("a"))
    }
}
