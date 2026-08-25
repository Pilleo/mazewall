package io.mazewall.portal.codegen.testapi

import io.mazewall.portal.Capability

interface SampleGreeter {
    fun greet(name: String): String

    fun add(
        a: Int,
        b: Int,
    ): Int
}

interface SampleGeom {
    fun magnitude(p: SamplePoint): Int
}

interface SampleFd {
    fun checksum(fd: Capability.ReadFd): Int
}

interface BadStream {
    fun read(s: java.io.InputStream): Int
}

interface Overloads {
    fun echo(s: String): String

    fun echo(n: Int): String
}
