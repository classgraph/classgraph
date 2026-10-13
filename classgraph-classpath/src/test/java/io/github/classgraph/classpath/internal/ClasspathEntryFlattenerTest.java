package io.github.classgraph.classpath.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Deep nesting, cycle detection, and atomic values share the same flattening contract. */
public class ClasspathEntryFlattenerTest {
    /** Expansion consumes heap frames, not the Java call stack, for both arrays and collections. */
    @Test
    public void deeplyNestedContainersPreserveOrder() throws Exception {
        Object nested = Arrays.asList("first", null, "last");
        for (var i = 0; i < 12000; i++) {
            nested = i % 2 == 0 ? new Object[] { nested } : Collections.singletonList(nested);
        }
        final var input = nested;
        final var task = new FutureTask<>(() -> {
            final List<@Nullable Object> values = new ArrayList<>();
            assertThat(ClasspathEntryFlattener.forEachEntry(input, value -> {
                values.add(value);
                return "first".equals(value);
            })).isTrue();
            return values;
        });
        final var thread = new Thread(null, task, "flatten-small-stack", 256 * 1024);
        thread.setDaemon(true);
        thread.start();
        try {
            assertThat(task.get(30, TimeUnit.SECONDS)).containsExactly("first", null, "last");
        } finally {
            thread.interrupt();
            thread.join(5000);
        }
    }

    /** Only ancestors are cycle guards, so repeated references to the same container still expand. */
    @Test
    public void cyclesAreSkippedButAliasesAreExpandedAgain() {
        final var array = new Object[2];
        final List<Object> collection = new ArrayList<>();
        array[0] = collection;
        array[1] = "array-leaf";
        collection.add(array);
        collection.add(collection);
        collection.add("collection-leaf");
        final List<@Nullable Object> values = new ArrayList<>();
        assertThat(ClasspathEntryFlattener.forEachEntry(new Object[] { array, array }, value -> {
            values.add(value);
            return false;
        })).isFalse();
        assertThat(values).containsExactly("collection-leaf", "array-leaf", "collection-leaf", "array-leaf");
    }

    /** Paths, primitive arrays, nulls, and self-yielding resources are leaf entries. */
    @Test
    public void specialLeafValuesRemainAtomic() {
        final var path = Path.of("a", "b");
        final var primitiveArray = new int[] { 1, 2 };
        final Iterable<Object> resource = new Iterable<>() {
            @Override
            public java.util.Iterator<Object> iterator() {
                return Collections.<Object> singletonList(this).iterator();
            }
        };
        final List<@Nullable Object> values = new ArrayList<>();
        ClasspathEntryFlattener.forEachEntry(new Object[] { path, primitiveArray, null, resource }, value -> {
            values.add(value);
            return true;
        });
        assertThat(values).containsExactly(path, primitiveArray, null, resource);
    }

    /** Failing actions propagate immediately, without contaminating a subsequent expansion. */
    @Test
    public void anActionFailureDoesNotLeaveCycleState() {
        final var input = List.of(List.of("entry"));
        final var failure = new IllegalStateException("action failure");
        assertThatThrownBy(() -> ClasspathEntryFlattener.forEachEntry(input, value -> {
            assertThat(value).isEqualTo("entry");
            throw failure;
        })).isSameAs(failure);
        final List<@Nullable Object> values = new ArrayList<>();
        ClasspathEntryFlattener.forEachEntry(input, value -> values.add(value));
        assertThat(values).containsExactly("entry");
    }
}
