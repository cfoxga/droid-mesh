package com.cfox.droidmesh.installer

// PROV-BEHAVE-010: the one loopback-ADB failure a person can actually fix, told apart from every
// other socket timeout. adbd has the client's public key and is waiting for someone to answer
// "Allow debugging from this computer?" on the device's own screen - unanswerable from the remote
// web UI, so the message has to say where to look and that a retry will then succeed.
class AdbAuthorizationPendingException : Exception(MESSAGE) {
    companion object {
        const val MESSAGE =
            "Waiting for ADB authorization on the device screen: tap \"Allow debugging from this " +
                "computer?\" (tick \"Always allow from this computer\"), then run the repair again."
    }
}
