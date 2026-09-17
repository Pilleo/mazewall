package io.mazewall.profiler.tierE.engine

import kotlin.test.Test
import kotlin.test.assertFailsWith

class RawSyscallTracepointTest {
    @Test
    fun `accepts the stable raw syscalls enter payload`() {
        RawSyscallTracepoint.validateFormat(
            """
            name: sys_enter
            ID: 123
            format:
                field:long int id;\toffset:8;\tsize:8;\tsigned:1;
                field:unsigned long args[6];\toffset:16;\tsize:48;\tsigned:0;
            """.trimIndent(),
        )
    }

    @Test
    fun `rejects a sys enter payload without the stable argument array`() {
        assertFailsWith<IllegalArgumentException> {
            RawSyscallTracepoint.validateFormat(
                """
                field:long int id;\toffset:8;\tsize:8;\tsigned:1;
                field:unsigned long args[4];\toffset:16;\tsize:32;\tsigned:0;
                """.trimIndent(),
            )
        }
    }
}
