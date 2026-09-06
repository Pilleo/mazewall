package io.mazewall.portal

/** Compiled fixture with the exact shape emitted by portal-codegen. */
public interface GeneratedServiceFixture {
    public fun echo(value: String): String
}

public class GeneratedServiceFixturePortalStub(
    private val client: PortalClient,
) : GeneratedServiceFixture {
    override fun echo(value: String): String =
        PortalCodec.Reader(client.invoke(METHOD_ID_ECHO, PortalCodec.encodeString(value))).string()
}

public class GeneratedServiceFixtureImpl : GeneratedServiceFixture {
    override fun echo(value: String): String = "guest:$value"
}

public object GeneratedServiceFixturePortalDispatcher {
    public val METHOD_IDS: IntArray = intArrayOf(METHOD_ID_ECHO)

    public fun handle(
        impl: GeneratedServiceFixture,
        methodId: Int,
        payload: ByteArray,
        granted: List<Capability.ReadFd>,
    ): ByteArray {
        return when (methodId) {
            METHOD_ID_ECHO -> PortalCodec.encodeString(impl.echo(PortalCodec.Reader(payload).string()))
            else -> error("unknown portal method $methodId")
        }
    }
}

/** `PortalStubGenerator.methodId(GeneratedServiceFixture, echo)`. */
private const val METHOD_ID_ECHO: Int = 51_523_398
