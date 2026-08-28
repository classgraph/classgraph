package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link ClassInfoList#exclude(ClassInfoList)} removes a class from the list whenever the other list can reach it,
 * but from the directly-related classes only when the other list relates to it directly, so the two could disagree:
 * {@link ClassInfoList#directOnly()} would return a class that the list itself no longer contained.
 */
public class ClassInfoListSetOperationsTest {
    /** The base of a three-deep class hierarchy. */
    public static class Base {
    }

    /** The middle of a three-deep class hierarchy. */
    public static class Middle extends Base {
    }

    /** The leaf of a three-deep class hierarchy. */
    public static class Leaf extends Middle {
    }

    /** {@code directOnly()} never returns a class that its own list does not contain. */
    @Test
    public void excludeKeepsDirectlyRelatedClassesWithinTheList() {
        try (ScanResult scanResult = new ClassGraph()
                .acceptClasses(Base.class.getName(), Middle.class.getName(), Leaf.class.getName())
                .enableClassInfo().scan()) {
            // Reaches Leaf, and Leaf is its direct subclass
            final ClassInfoList middleSubclasses = scanResult.getClassInfo(Middle.class.getName())
                    .getSubclasses();
            assertThat(middleSubclasses.getNames()).containsExactly(Leaf.class.getName());
            assertThat(middleSubclasses.directOnly().getNames()).containsExactly(Leaf.class.getName());

            // Reaches both Middle and Leaf, but only Middle is its direct subclass
            final ClassInfoList baseSubclasses = scanResult.getClassInfo(Base.class.getName()).getSubclasses();
            assertThat(baseSubclasses.getNames()).containsExactly(Leaf.class.getName(), Middle.class.getName());
            assertThat(baseSubclasses.directOnly().getNames()).containsExactly(Middle.class.getName());

            // Excluding the classes Base reaches leaves nothing, so nothing is directly related either
            final ClassInfoList excluded = middleSubclasses.exclude(baseSubclasses);
            assertThat(excluded).isEmpty();
            assertThat(excluded.directOnly()).isEmpty();
        }
    }
}
