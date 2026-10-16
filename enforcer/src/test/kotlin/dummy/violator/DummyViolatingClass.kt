package dummy.violator

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import java.lang.foreign.MemorySegment

public class DummyViolatingClass {
    public lateinit var segment: MemorySegment
}
