package com.romm.desktop.player

/**
 * Test double for [PlayerProcessLauncher]: records every launch and returns a scripted outcome.
 * Optionally runs [onLaunch] with the request at spawn time — use it to simulate what the real
 * player does while running (e.g. writing its candidate save + result file before exiting).
 */
class FakePlayerProcessLauncher(
    private val outcomeFor: (PlayerRequest) -> LaunchOutcome = { LaunchOutcome.Started(pid = Long.MAX_VALUE) },
    private val onLaunch: ((PlayerRequest) -> Unit)? = null,
) : PlayerProcessLauncher {

    private val launchedRequests = mutableListOf<PlayerRequest>()

    /** Every request handed to [launch], in order. */
    val launches: List<PlayerRequest> get() = launchedRequests.toList()

    override fun launch(request: PlayerRequest): LaunchOutcome {
        launchedRequests += request
        onLaunch?.invoke(request)
        return outcomeFor(request)
    }
}
