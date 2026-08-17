package com.romm.desktop.player

/** Test double for [PlayerProcessLauncher]: records every launch and returns a scripted outcome. */
class FakePlayerProcessLauncher(
    private val outcomeFor: (PlayerRequest) -> LaunchOutcome = { LaunchOutcome.Started(pid = 4242L) },
) : PlayerProcessLauncher {

    private val launchedRequests = mutableListOf<PlayerRequest>()

    /** Every request handed to [launch], in order. */
    val launches: List<PlayerRequest> get() = launchedRequests.toList()

    override fun launch(request: PlayerRequest): LaunchOutcome {
        launchedRequests += request
        return outcomeFor(request)
    }
}
