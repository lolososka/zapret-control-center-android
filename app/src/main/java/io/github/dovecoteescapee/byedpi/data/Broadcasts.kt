package io.github.dovecoteescapee.byedpi.data

const val STARTED_BROADCAST = "io.github.lolososka.zapretmobile.STARTED"
const val STOPPED_BROADCAST = "io.github.lolososka.zapretmobile.STOPPED"
const val FAILED_BROADCAST = "io.github.lolososka.zapretmobile.FAILED"

const val SENDER = "sender"

enum class Sender(val senderName: String) {
    Proxy("Proxy"),
    VPN("VPN")
}
