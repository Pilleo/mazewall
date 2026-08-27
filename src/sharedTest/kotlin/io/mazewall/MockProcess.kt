package io.mazewall

import java.io.ByteArrayInputStream
import java.io.InputStream

public open class MockProcess(
    private val pid: Long,
    private val stdout: String = "",
    private val exitVal: Int = 0,
    @Volatile private var alive: Boolean = true
) : Process() {
    override fun destroy() { alive = false }
    override fun exitValue(): Int = exitVal
    override fun waitFor(): Int = exitVal
    override fun getOutputStream(): java.io.OutputStream = java.io.ByteArrayOutputStream()
    override fun getInputStream(): InputStream = ByteArrayInputStream(stdout.toByteArray())
    override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
    override fun pid(): Long = pid
    override fun isAlive(): Boolean = alive

    public fun setAlive(value: Boolean) {
        alive = value
    }
}
