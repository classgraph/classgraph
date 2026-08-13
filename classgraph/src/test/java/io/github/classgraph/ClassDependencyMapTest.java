package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ScanResult#getClassDependencyMap()} and {@link ScanResult#getReverseClassDependencyMap()}, which
 * are two views of the same set of dependency edges: one from each class to what it uses, and one from each class
 * to what uses it.
 */
public class ClassDependencyMapTest {
    /** A class that nothing in this test depends on. */
    public static class Leaf {
    }

    /** A class that depends on {@link Leaf}. */
    public static class UsesLeaf {
        /** A method that refers to {@link Leaf}, making it a dependency of this class. */
        public void use() {
            new Leaf().toString();
        }
    }

    /** A second class that depends on {@link Leaf}. */
    public static class AlsoUsesLeaf {
        /** A method that refers to {@link Leaf}, making it a dependency of this class. */
        public void use() {
            new Leaf().toString();
        }
    }

    /** The scan result the maps are read from. */
    private static ScanResult scanResult;

    /** Scan the test classes with inter-class dependencies enabled. */
    @BeforeAll
    static void scan() {
        scanResult = new ClassGraph()
                .acceptClasses(Leaf.class.getName(), UsesLeaf.class.getName(), AlsoUsesLeaf.class.getName())
                .enableInterClassDependencies().scan();
    }

    /** Close the scan result. */
    @AfterAll
    static void closeScanResult() {
        scanResult.close();
    }

    /**
     * The {@link ClassInfo} for a test class.
     *
     * @param cls
     *            the class.
     * @return the {@link ClassInfo}.
     */
    private static ClassInfo classInfo(final Class<?> cls) {
        final var ci = scanResult.getClassInfo(cls.getName());
        assertThat(ci).isNotNull();
        return ci;
    }

    /** The forward map holds an entry for every accepted class, listing what that class depends on. */
    @Test
    public void theForwardMapListsWhatEachClassDependsOn() {
        final var dependencyMap = scanResult.getClassDependencyMap();
        assertThat(dependencyMap).containsOnlyKeys(classInfo(Leaf.class), classInfo(UsesLeaf.class),
                classInfo(AlsoUsesLeaf.class));
        assertThat(dependencyMap.get(classInfo(UsesLeaf.class))).contains(classInfo(Leaf.class));
        // Nothing that was scanned is used by Leaf itself
        assertThat(dependencyMap.get(classInfo(Leaf.class))).doesNotContain(classInfo(UsesLeaf.class),
                classInfo(AlsoUsesLeaf.class));
    }

    /** The reverse map lists, for each class that is depended upon, the classes that depend on it. */
    @Test
    public void theReverseMapListsWhatDependsOnEachClass() {
        final var reverseMap = scanResult.getReverseClassDependencyMap();
        assertThat(reverseMap.get(classInfo(Leaf.class))).containsExactly(classInfo(AlsoUsesLeaf.class),
                classInfo(UsesLeaf.class));
        // A class that nothing depends on has no entry at all, rather than an entry with an empty list
        assertThat(reverseMap).doesNotContainKey(classInfo(UsesLeaf.class));
    }

    /** The reverse map holds exactly the edges of the forward map, turned around. */
    @Test
    public void theTwoMapsHoldTheSameEdges() {
        final var forwardEdges = scanResult.getClassDependencyMap().entrySet().stream()
                .flatMap(ent -> ent.getValue().stream().map(dep -> List.of(ent.getKey(), dep))).toList();
        final var reverseEdges = scanResult.getReverseClassDependencyMap().entrySet().stream()
                .flatMap(ent -> ent.getValue().stream().map(dependent -> List.of(dependent, ent.getKey())))
                .toList();
        assertThat(reverseEdges).containsExactlyInAnyOrderElementsOf(forwardEdges);
    }

    /** Both maps are read-only views of the scan result, so they cannot be changed. */
    @Test
    public void bothMapsAreUnmodifiable() {
        assertThatThrownBy(() -> scanResult.getClassDependencyMap().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> scanResult.getReverseClassDependencyMap().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
