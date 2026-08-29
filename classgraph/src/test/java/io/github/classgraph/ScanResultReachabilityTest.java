package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * Every {@link ScanResultObject} reachable from a {@link ScanResult} must have been given that {@code ScanResult}.
 * An object that was missed silently loses the ability to reach its own {@link ClassInfo}, so accessors that need
 * it (annotation default values, {@code isInherited()}, classloading) misreport rather than fail, and
 * {@link ScanResultObject#scanResult()} throws {@link NullPointerException}.
 */
public class ScanResultReachabilityTest {
    /**
     * The one field that may legitimately hold objects that have not been given the {@link ScanResult} yet: a type
     * annotation captured by a decorator that has not run. The decorator gives the annotation the
     * {@link ScanResult} when it applies it to the type signature, so such an annotation is never handed to the
     * caller with a null {@code ScanResult}.
     */
    private static final String LAZY_FIELD = "typeAnnotationDecorators";

    /** Walk everything reachable from a {@link ScanResult}, and check that no object was missed. */
    @Test
    public void everyReachableObjectHasTheScanResult() throws ReflectiveOperationException {
        final Field scanResultFld = ScanResultObject.class.getDeclaredField("scanResult");
        scanResultFld.setAccessible(true);

        try (var scanResult = new ClassGraph().enableClasspath().enableAllInfo().enableInterClassDependencies()
                .enableExternalClasses().acceptPackages("io.github.classgraph", "com.xyz").scan()) {

            final Map<Object, Boolean> visited = new IdentityHashMap<>();
            final Deque<Object[]> queue = new ArrayDeque<>();
            queue.add(new Object[] { scanResult, "ScanResult" });
            final var missing = new TreeSet<String>();
            while (!queue.isEmpty()) {
                final var entry = queue.poll();
                final var obj = entry[0];
                final var path = (String) entry[1];
                if (obj == null || visited.put(obj, Boolean.TRUE) != null) {
                    continue;
                }
                if (obj instanceof Collection || obj instanceof Map || obj.getClass().isArray()) {
                    // Containers are transparent -- keep the path of the field that holds the container
                    for (final Object elt : obj instanceof final Map<?, ?> map ? map.values()
                            : obj instanceof final Collection<?> coll ? coll : arrayElements(obj)) {
                        queue.add(new Object[] { elt, path });
                    }
                    continue;
                }
                // Only ClassGraph's own objects (including its lambdas) can hold a ScanResult
                if (!obj.getClass().getName().startsWith("io.github.classgraph.")) {
                    continue;
                }
                if (obj instanceof final ScanResultObject scanResultObject
                        && scanResultFld.get(scanResultObject) == null && !path.contains(LAZY_FIELD)) {
                    missing.add(path + " -> " + obj.getClass().getSimpleName());
                }
                for (var cls = obj.getClass(); cls != null
                        && cls.getName().startsWith("io.github.classgraph."); cls = cls.getSuperclass()) {
                    for (final Field field : cls.getDeclaredFields()) {
                        if (field.getType().isPrimitive() || Modifier.isStatic(field.getModifiers())) {
                            continue;
                        }
                        field.setAccessible(true);
                        final var value = field.get(obj);
                        if (value != null) {
                            queue.add(new Object[] { value,
                                    path + "." + obj.getClass().getSimpleName() + "#" + field.getName() });
                        }
                    }
                }
            }
            // Check that the walk actually reached the object graph, so that an empty result means something
            assertThat(visited).hasSizeGreaterThan(10_000);
            assertThat(missing).isEmpty();
        }
    }

    /** Get the elements of an array of references, or nothing at all for an array of primitives. */
    private static Iterable<?> arrayElements(final Object array) {
        final List<Object> elements = new ArrayList<>();
        if (!array.getClass().getComponentType().isPrimitive()) {
            for (var i = 0; i < Array.getLength(array); i++) {
                elements.add(Array.get(array, i));
            }
        }
        return elements;
    }
}
