package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The contents and order of the results of {@link ClassInfoList#union(ClassInfoList...)},
 * {@link ClassInfoList#intersect(ClassInfoList...)} and {@link ClassInfoList#exclude(ClassInfoList)}, and of their
 * {@link ClassInfoList#directOnly()} lists.
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

    /** Scan this test's own package. */
    private static ScanResult scan() {
        return new ClassGraph().enableClasspath()
                .acceptPackages(ClassInfoListSetOperationsTest.class.getPackage().getName()).enableClassInfo()
                .scan();
    }

    /** {@code directOnly()} never returns a class that its own list does not contain. */
    @Test
    public void excludeKeepsDirectlyRelatedClassesWithinTheList() {
        try (var scanResult = scan()) {
            // Reaches Leaf, and Leaf is its direct subclass
            final var middleSubclasses = scanResult.getClassInfo(Middle.class.getName()).getAllSubclasses();
            assertThat(middleSubclasses.getNames()).containsExactly(Leaf.class.getName());
            assertThat(middleSubclasses.directOnly().getNames()).containsExactly(Leaf.class.getName());

            // Reaches both Middle and Leaf, but only Middle is its direct subclass
            final var baseSubclasses = scanResult.getClassInfo(Base.class.getName()).getAllSubclasses();
            assertThat(baseSubclasses.getNames()).containsExactly(Leaf.class.getName(), Middle.class.getName());
            assertThat(baseSubclasses.directOnly().getNames()).containsExactly(Middle.class.getName());

            // Excluding the classes Base reaches leaves nothing, so nothing is directly related either
            final var excluded = middleSubclasses.exclude(baseSubclasses);
            assertThat(excluded).isEmpty();
            assertThat(excluded.directOnly()).isEmpty();
        }
    }

    /** A class that both lists relate to directly survives {@code union()} and {@code intersect()}. */
    @Test
    public void unionAndIntersectKeepDirectlyRelatedClassesWithinTheList() {
        try (var scanResult = scan()) {
            final var middleSubclasses = scanResult.getClassInfo(Middle.class.getName()).getAllSubclasses();
            final var baseSubclasses = scanResult.getClassInfo(Base.class.getName()).getAllSubclasses();

            final var union = middleSubclasses.union(baseSubclasses);
            assertThat(union.getNames()).containsExactly(Leaf.class.getName(), Middle.class.getName());
            assertThat(union.directOnly().getNames()).containsExactly(Leaf.class.getName(), Middle.class.getName());
            assertThat(union).containsAll(union.directOnly());

            final var intersection = middleSubclasses.intersect(baseSubclasses);
            assertThat(intersection.getNames()).containsExactly(Leaf.class.getName());
            assertThat(intersection).containsAll(intersection.directOnly());
        }
    }

    /**
     * An empty list contributes nothing to a union, so a union that starts from one is ordered as the union of the
     * other lists alone: by name when they are sorted by name, and in their own order when they are not.
     */
    @Test
    public void unionStartingFromAnEmptyListIsOrderedAsTheOtherLists() {
        try (var scanResult = scan()) {
            final var base = scanResult.getClassInfo(Base.class.getName());
            final var leaf = scanResult.getClassInfo(Leaf.class.getName());
            // Two lists sorted by name, given out of name order
            final var leafOnly = new ClassInfoList(List.of(leaf));
            final var baseOnly = new ClassInfoList(List.of(base));
            final var expected = leafOnly.union(baseOnly);
            assertThat(expected.getNames()).containsExactly(Base.class.getName(), Leaf.class.getName());

            assertThat(ClassInfoList.emptyList().union(leafOnly, baseOnly)).isEqualTo(expected);
            // A query that finds nothing returns the empty list
            assertThat(scanResult.getAllSubclasses("no.such.Class").union(leafOnly, baseOnly)).isEqualTo(expected);
            assertThat(ClassInfoList.emptyList().union(ClassInfoList.emptyList(), leafOnly, baseOnly))
                    .isEqualTo(expected);

            // A list in class hierarchy order keeps that order
            final var leafSuperclasses = leaf.getAllSuperclasses();
            assertThat(leafSuperclasses.getNames()).containsExactly(Middle.class.getName(), Base.class.getName());
            assertThat(ClassInfoList.emptyList().union(leafSuperclasses)).isEqualTo(leafSuperclasses);
        }
    }

    /**
     * The intersection keeps the order of the first list that is not sorted by name, which is this list when it is
     * not sorted by name, and its directly related classes are in the same order.
     */
    @Test
    public void intersectKeepsTheOrderOfTheFirstUnsortedList() {
        try (var scanResult = scan()) {
            final var base = scanResult.getClassInfo(Base.class.getName());
            final var middle = scanResult.getClassInfo(Middle.class.getName());
            final var leaf = scanResult.getClassInfo(Leaf.class.getName());

            // In class hierarchy order, with Middle the only direct superclass
            final var leafSuperclasses = leaf.getAllSuperclasses();
            assertThat(leafSuperclasses.getNames()).containsExactly(Middle.class.getName(), Base.class.getName());
            // The same classes, not sorted by name, in the other order, and each directly related
            final var reversed = middle.getAllSuperclasses().union(leafSuperclasses);
            assertThat(reversed.getNames()).containsExactly(Base.class.getName(), Middle.class.getName());
            assertThat(reversed.directOnly().getNames()).containsExactly(Base.class.getName(),
                    Middle.class.getName());

            assertThat(leafSuperclasses.intersect(reversed)).isEqualTo(leafSuperclasses);
            assertThat(reversed.intersect(leafSuperclasses)).isEqualTo(reversed);

            // A list sorted by name takes the order of an unsorted argument (#238), and so do its directly related
            // classes, even when this list holds them in another order
            final var sortedByName = new ClassInfoList(List.of(middle, base));
            assertThat(sortedByName.getNames()).containsExactly(Base.class.getName(), Middle.class.getName());
            final var intersection = sortedByName.intersect(leafSuperclasses);
            assertThat(intersection).isEqualTo(leafSuperclasses);
            assertThat(intersection.directOnly().getNames()).containsExactly(Middle.class.getName());
            final var intersectionWithReversed = sortedByName.intersect(reversed);
            assertThat(intersectionWithReversed).isEqualTo(reversed);
            assertThat(intersectionWithReversed.directOnly()).isEqualTo(reversed.directOnly());
        }
    }

    /** {@code exclude()} keeps this list's order whatever the order of the other list. */
    @Test
    public void excludeKeepsThisListsOrder() {
        try (var scanResult = scan()) {
            final var leaf = scanResult.getClassInfo(Leaf.class.getName());
            final var middle = scanResult.getClassInfo(Middle.class.getName());
            final var leafSuperclasses = leaf.getAllSuperclasses();
            final var reversed = middle.getAllSuperclasses().union(leafSuperclasses);
            final var all = leafSuperclasses.union(new ClassInfoList(List.of(leaf)));
            assertThat(all.getNames()).containsExactly(Middle.class.getName(), Base.class.getName(),
                    Leaf.class.getName());

            assertThat(all.exclude(reversed).getNames()).containsExactly(Leaf.class.getName());
            assertThat(all.exclude(new ClassInfoList(List.of(leaf))).getNames())
                    .containsExactly(Middle.class.getName(), Base.class.getName());
            assertThat(reversed.exclude(new ClassInfoList(List.of(leaf, middle))).getNames())
                    .containsExactly(Base.class.getName());
        }
    }
}
