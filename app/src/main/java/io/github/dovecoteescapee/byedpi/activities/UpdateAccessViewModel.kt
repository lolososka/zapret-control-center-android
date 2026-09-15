package io.github.dovecoteescapee.byedpi.activities

import androidx.lifecycle.ViewModel
import io.github.dovecoteescapee.byedpi.core.UpdateMembershipRepository

/** Short-lived access survives rotation, never preferences, backups or process death. */
class UpdateAccessViewModel : ViewModel() {
    var session: UpdateMembershipRepository.Session? = null
    var grant: UpdateMembershipRepository.Grant? = null
    var awaitingBotReturn = false

    fun clear() {
        session = null
        grant = null
        awaitingBotReturn = false
    }
}
