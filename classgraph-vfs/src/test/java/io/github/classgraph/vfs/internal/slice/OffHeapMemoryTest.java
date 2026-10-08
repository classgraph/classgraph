package io.github.classgraph.vfs.internal.slice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.foreign.Arena;

import org.junit.jupiter.api.Test;

/** Tests for {@link OffHeapMemory}. */
// #939
public class OffHeapMemoryTest {
    /** Closing an arena that has already been closed reports failure, rather than throwing. */
    @Test
    public void closingAnArenaTwiceReportsFailureRatherThanThrowing() {
        final var arena = Arena.ofShared();
        assertThat(OffHeapMemory.closeArena(arena, /* log = */ null)).isTrue();
        assertThat(OffHeapMemory.closeArena(arena, /* log = */ null)).isFalse();
    }

    /**
     * Loading the classes needed to free off-heap memory works, and works more than once -- it runs on every scan,
     * but must only do the work the first time.
     */
    @Test
    public void theClassesNeededToFreeOffHeapMemoryCanBeLoadedAheadOfTime() {
        assertThatCode(OffHeapMemory::warmUpDirectByteBufferClosing).doesNotThrowAnyException();
        assertThatCode(OffHeapMemory::warmUpDirectByteBufferClosing).doesNotThrowAnyException();
    }
}
