package io.mazewall

/**
 * A mock implementation of [PlatformProvider] for deterministic unit testing.
 */
public open class MockPlatformProvider : PlatformProvider {
    public var mockOsName: String = "Linux"
    public var mockOsVersion: String = "6.8.0"
    public var mockOsArch: String = "amd64"
    public var mockKernelSeccompSupport: Boolean = true
    public var mockSeccompMode: SeccompMode = SeccompMode.Disabled
    public var mockSeccompSanityCheckResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
        LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(22, -1L) // EINVAL
    public var mockNoNewPrivsEnabled: Boolean = false
    public var mockYamaPtraceScope: YamaPtraceScope = YamaPtraceScope.Classic
    public var mockLandlockAbiVersion: Int = 5
    public var mockSeccompTsyncSupported: Boolean = true
    public var mockSeccompUserNotifSupported: Boolean = true
    public var mockContainer: Boolean = false

    override fun getOsName(): String = mockOsName
    override fun getOsVersion(): String = mockOsVersion
    override fun getOsArch(): String = mockOsArch
    override fun hasKernelSeccompSupport(): Boolean = mockKernelSeccompSupport
    override fun getSeccompMode(): SeccompMode = mockSeccompMode
    override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = mockSeccompSanityCheckResult
    override fun isNoNewPrivsEnabled(): Boolean = mockNoNewPrivsEnabled
    override fun getYamaPtraceScope(): YamaPtraceScope = mockYamaPtraceScope
    override fun getLandlockAbiVersion(): Int = mockLandlockAbiVersion
    override fun probeSeccompTsync(): Boolean = mockSeccompTsyncSupported
    override fun probeSeccompUserNotif(): Boolean = mockSeccompUserNotifSupported
    override fun isContainer(): Boolean = mockContainer
}
