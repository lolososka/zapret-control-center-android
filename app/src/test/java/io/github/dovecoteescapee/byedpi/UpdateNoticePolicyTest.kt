package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.core.UpdateNoticePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateNoticePolicyTest {
    @Test
    fun noticeRequiresBothReleaseAndVerifiedMembership() {
        assertTrue(UpdateNoticePolicy.shouldShow(releaseAvailable = true, membershipVerified = true))
        assertFalse(UpdateNoticePolicy.shouldShow(releaseAvailable = false, membershipVerified = true))
        assertFalse(UpdateNoticePolicy.shouldShow(releaseAvailable = true, membershipVerified = false))
        assertFalse(UpdateNoticePolicy.shouldShow(releaseAvailable = false, membershipVerified = false))
    }
}
