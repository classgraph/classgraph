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

    /**
     * Elements cannot be removed. The inherited implementations look for the element to remove with
     * {@code equals()}, which is the very comparison this set exists to avoid, so they answered a request to remove
     * something by reporting that it was not there rather than by refusing.
     */
    @Test
    void elementsCannotBeRemoved() {
        final var set = new LinkedIdentitySet<String>();
        set.addAll(List.of("a", "b"));

        // Removing something that is not in the set reported that there was nothing to remove
        assertThatThrownBy(() -> set.remove("z")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> set.removeAll(List.of())).isInstanceOf(UnsupportedOperationException.class);
        // Retaining everything reported that there was nothing to remove
        assertThatThrownBy(() -> set.retainAll(List.of("a", "b")))
                .isInstanceOf(UnsupportedOperationException.class);
        // Clearing the set threw
        assertThatThrownBy(set::clear).isInstanceOf(UnsupportedOperationException.class);
        // Removing something that is in the set threw, but only because the iterator refuses removal
        assertThatThrownBy(() -> set.remove("a")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(List.copyOf(set)).containsExactly("a", "b");
    }

    /**
     * Clearing an empty set is refused too. The inherited implementation removes through the iterator, so it had
     * nothing to remove from an empty set and returned quietly, rather than refusing like every other remover.
     */
    @Test
    void anEmptySetCannotBeCleared() {
        final var set = new LinkedIdentitySet<String>();
        assertThatThrownBy(set::clear).isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("Elements cannot be removed from a LinkedIdentitySet");
    }

    /** A new set is empty. */
    @Test
    void aNewSetIsEmpty() {
        final var set = new LinkedIdentitySet<String>();
        assertThat(set).isEmpty();
        assertThat(set.contains("x")).isFalse();
    }
}
