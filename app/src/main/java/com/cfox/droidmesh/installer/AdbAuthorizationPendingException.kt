package com.cfox.droidmesh.installer

// PROV-BEHAVE-010: the one loopback-ADB failure a person can actually fix, told apart from every
// other socket timeout. adbd has the client's public key and is waiting for someone to answer
// "Allow debugging from this computer?" on the device's own screen - unanswerable from the remote
// web UI, so the message has to say where to look and that a retry will then succeed.
//
// PROV-OPEN-003: it also has to name the one state a retry alone cannot escape. adbd dispatches a
// single authorization prompt at a time and never releases that slot when the client that asked
// for it gives up, so on a device where an earlier attempt timed out no dialog appears at all and
// only an adbd restart (in practice, a reboot) un-wedges it. Observed twice on the Theater GTV
// while verifying this fix live.
class AdbAuthorizationPendingException : Exception(MESSAGE) {
    companion object {
        const val MESSAGE =
            "Waiting for ADB authorization on the device screen: tap \"Allow debugging from this " +
                "computer?\" (tick \"Always allow from this computer\"), then run the repair again. " +
                "If no prompt ever appears, restart the device and repair again - the device shows " +
                "only one ADB authorization prompt at a time and does not free that slot when an " +
                "earlier attempt gave up waiting."
    }
}
