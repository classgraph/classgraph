package io.github.classgraph.base.internal.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Tests for {@link LinkedIdentitySet}. */
class LinkedIdentitySetTest {
    /** A value that claims to be equal to every other value of this class, and that shares one hash code. */
    private static final class EqualsEverything {
        /** The name, so that the values can be told apart in an assertion failure. */
        private final String name;

        /**
         * Constructor.
         *
         * @param name
         *            the name.
         */
        EqualsEverything(final String name) {
            this.name = name;
        }

        @Override
        public boolean equals(final Object obj) {
            return obj instanceof EqualsEverything;
        }

        @Override
        public int hashCode() {
            return 0;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** Two distinct objects are both kept, however equal they claim to be. */
    @Test
    void twoObjectsThatClaimToBeEqualAreBothKept() {
        final var first = new EqualsEverything("first");
        final var second = new EqualsEverything("second");
        final var set = new LinkedIdentitySet<EqualsEverything>();

        assertThat(set.add(first)).isTrue();
        assertThat(set.add(second)).isTrue();
        assertThat(set).hasSize(2);
        assertThat(List.copyOf(set)).containsExactly(first, second);
        assertThat(set.contains(first)).isTrue();
        assertThat(set.contains(second)).isTrue();
        assertThat(set.contains(new EqualsEverything("third"))).isFalse();
    }

    /** The very same object is only added once, and does not move to the end when it is added again. */
    @Test
    void theSameObjectIsOnlyAddedOnce() {
        final var first = new EqualsEverything("first");
        final var second = new EqualsEverything("second");
        final var set = new LinkedIdentitySet<EqualsEverything>();
        set.add(first);
        set.add(second);

        assertThat(set.add(first)).isFalse();
        assertThat(set).hasSize(2);
        assertThat(List.copyOf(set)).containsExactly(first, second);
    }

    /** The elements are iterated in the order they were added, and cannot be removed through the iterator. */
    @Test
    void theElementsAreIteratedInTheOrderTheyWereAdded() {
        final var set = new LinkedIdentitySet<String>();
        set.addAll(List.of("c", "a", "b"));

        assertThat(List.copyOf(set)).containsExactly("c", "a", "b");
        final var iterator = set.iterator();
        iterator.next();
        assertThatThrownBy(iterator::remove).isInstanceOf(UnsupportedOperationException.class);
    }

    /** A new set is empty. */
    @Test
    void aNewSetIsEmpty() {
        final var set = new LinkedIdentitySet<String>();
        assertThat(set).isEmpty();
        assertThat(set.contains("x")).isFalse();
    }
}
