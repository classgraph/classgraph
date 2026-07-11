package io.github.classgraph.issues.issue931;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Issue 931: the default {@code SimpleThreadFactory} used to allocate a new
 * {@code ThreadGroup("ClassGraph-thread-group")} for every worker thread. Repeated
 * {@link ClassGraph#scan()} calls therefore accumulated millions of {@code ThreadGroup}
 * instances (and their internal {@code Thread[]} arrays) that were not GC'd.
 *
 * <p>
 * After the fix, worker threads join the security manager's thread group (when present)
 * or the calling thread's group — the same pattern as
 * {@link java.util.concurrent.Executors#defaultThreadFactory()} — and no
 * {@code ClassGraph-thread-group} groups are created.
 */
public class Issue931Test {
    private static final String LEAKED_THREAD_GROUP_NAME = "ClassGraph-thread-group";

    /** Count live thread groups with the given name, starting from the root group. */
    private static int countThreadGroupsNamed(final String name) {
        ThreadGroup root = Thread.currentThread().getThreadGroup();
        while (root.getParent() != null) {
            root = root.getParent();
        }
        // activeGroupCount() is only an estimate; oversize the array and re-enumerate if needed.
        ThreadGroup[] groups = new ThreadGroup[Math.max(root.activeGroupCount() * 2, 16)];
        int n;
        while ((n = root.enumerate(groups, /* recurse = */ true)) == groups.length) {
            groups = new ThreadGroup[groups.length * 2];
        }
        int count = 0;
        for (int i = 0; i < n; i++) {
            if (name.equals(groups[i].getName())) {
                count++;
            }
        }
        return count;
    }

    @Test
    public void repeatedDefaultScansDoNotCreateClassGraphThreadGroups() {
        final String pkg = Issue931Test.class.getPackage().getName();
        final int before = countThreadGroupsNamed(LEAKED_THREAD_GROUP_NAME);

        // Each scan builds a fresh AutoCloseableExecutorService with the default factory.
        // Before the fix this created one ThreadGroup per worker thread per scan.
        for (int i = 0; i < 20; i++) {
            try (ScanResult scanResult = new ClassGraph().acceptPackages(pkg).scan()) {
                assertThat(scanResult.getAllResources()).isNotNull();
            }
        }

        final int after = countThreadGroupsNamed(LEAKED_THREAD_GROUP_NAME);
        assertThat(after)
                .as("default SimpleThreadFactory must not allocate ClassGraph-thread-group "
                        + "ThreadGroups (issue #931); before=%s after=%s", before, after)
                .isEqualTo(before);
        assertThat(after)
                .as("no ClassGraph-thread-group should exist at all after the fix")
                .isZero();
    }
}
