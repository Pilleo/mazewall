package io.mazewall.portal

/** Compiled fixture with the exact shape emitted by portal-codegen. */
public interface GeneratedServiceFixture {
    public fun echo(value: String): String
}

public class GeneratedServiceFixturePortalStub(
    private val client: PortalClient,
) : GeneratedServiceFixture {
    override fun echo(value: String): String =
        PortalCodec.Reader(client.invoke(1000, PortalCodec.encodeString(value))).string()
}

public class GeneratedServiceFixtureImpl : GeneratedServiceFixture {
    override fun echo(value: String): String = "guest:$value"
}

public object GeneratedServiceFixturePortalDispatcher {
    public val METHOD_IDS: IntArray = intArrayOf(1000)

    public fun handle(
        impl: GeneratedServiceFixture,
        methodId: Int,
        payload: ByteArray,
        granted: List<Capability.ReadFd>,
    ): ByteArray {
        require(methodId == 1000)
        require(granted.isEmpty())
        return PortalCodec.encodeString(impl.echo(PortalCodec.Reader(payload).string()))
    }
}
