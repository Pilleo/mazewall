package dummy.violator

import java.lang.foreign.MemorySegment

public class DummyViolatingClass {
    public lateinit var segment: MemorySegment
}
