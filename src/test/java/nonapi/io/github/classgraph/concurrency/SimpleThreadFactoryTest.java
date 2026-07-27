package nonapi.io.github.classgraph.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Issue 931: {@link SimpleThreadFactory#newThread(Runnable)} allocated a new {@link ThreadGroup} for every thread
 * it created. A {@link ThreadGroup} registers itself with its parent on construction, and a non-daemon
 * {@link ThreadGroup} is only unregistered when it is explicitly destroyed, so every scan leaked one
 * {@link ThreadGroup} (plus its {@code Thread[]} array) that was reachable forever from the parent group.
 */
public class SimpleThreadFactoryTest {
    /** Threads created by the factory must not each get their own new {@link ThreadGroup}. */
    @Test
    public void threadsDoNotEachAllocateANewThreadGroup() {
        final ThreadGroup currentThreadGroup = Thread.currentThread().getThreadGroup();
        final int numGroupsBefore = currentThreadGroup.activeGroupCount();

        final SimpleThreadFactory threadFactory = new SimpleThreadFactory("ClassGraph-test-", true);
        final Runnable noOp = new Runnable() {
            @Override
            public void run() {
                // No-op -- the threads are never started, only constructed.
            }
        };
        final Thread thread0 = threadFactory.newThread(noOp);
        final Thread thread1 = threadFactory.newThread(noOp);

        // Both threads share one ThreadGroup, rather than each getting a fresh one
        assertThat(thread0.getThreadGroup()).isSameAs(thread1.getThreadGroup());

        // No new ThreadGroup was registered under the current thread group
        assertThat(currentThreadGroup.activeGroupCount()).isEqualTo(numGroupsBefore);
    }
}
