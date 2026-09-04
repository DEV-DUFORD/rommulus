package com.romm.desktop.controller

import com.romm.desktop.platform.HostOs
import net.java.games.input.Controller
import net.java.games.input.ControllerEnvironment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ControllerEnvironmentPolicyTest {

    @Test
    fun `policy is selected from the normalized host`() {
        assertThat(ControllerEnvironmentPolicy.forHostOs(HostOs.LINUX))
            .isInstanceOf(LinuxControllerEnvironmentPolicy::class.java)
        assertThat(ControllerEnvironmentPolicy.forHostOs(HostOs.WINDOWS))
            .isInstanceOf(DefaultControllerEnvironmentPolicy::class.java)
        assertThat(ControllerEnvironmentPolicy.forHostOs(HostOs.MACOS))
            .isInstanceOf(DefaultControllerEnvironmentPolicy::class.java)
        assertThat(ControllerEnvironmentPolicy.forHostOs(HostOs.UNKNOWN))
            .isInstanceOf(DefaultControllerEnvironmentPolicy::class.java)
    }

    @Test
    fun `default policy has no Linux topology signal or diagnostics`() {
        val policy = DefaultControllerEnvironmentPolicy(FakeEnvironment(emptyList()))

        assertThat(policy.topologySnapshot()).isNull()
        assertThat(policy.diagnostics(listOf("pad"))).isEmpty()
        assertThat(policy.diagnostics(emptyList())).isEmpty()
    }

    @Test
    fun `default policy re-enumerates on first poll failure then cools down`() {
        val fake = FakeEnvironment(emptyList())
        val policy = DefaultControllerEnvironmentPolicy(fake)

        // First attempt: a meaningful re-enumeration (clears the cached controller list).
        assertThat(policy.refresh()).isTrue()
        assertThat(fake.controllers).isNull()

        // Within the cooldown window the attempt is suppressed — no re-scan, no warning.
        assertThat(policy.refresh()).isFalse()
        assertThat(policy.refresh()).isFalse()
    }

    @Test
    fun `default policy uses the injected environment and never touches the Linux plugin`() {
        val fake = FakeEnvironment(emptyList())
        val policy = DefaultControllerEnvironmentPolicy(fake)

        assertThat(policy.environment).isSameAs(fake)
    }

    /**
     * A stand-in [ControllerEnvironment] carrying a settable `controllers` field (the same field
     * `DefaultControllerEnvironment` caches) so the Default policy's reflection can clear it without
     * loading any JInput native.
     */
    private class FakeEnvironment(initial: List<Controller>) : ControllerEnvironment() {
        var controllers: Any? = initial

        override fun getControllers(): Array<Controller> =
            (controllers as? List<Controller>)?.toTypedArray() ?: emptyArray()

        override fun isSupported(): Boolean = true
    }
}
