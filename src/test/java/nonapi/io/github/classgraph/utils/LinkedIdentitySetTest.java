package nonapi.io.github.classgraph.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;

import org.junit.jupiter.api.Test;

/** Tests for {@link LinkedIdentitySet}. */
public class LinkedIdentitySetTest {
    /** An object that claims to be equal to every other object of its own class. */
    private static class EqualsEverything {
        private final String name;

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
    public void twoObjectsThatClaimToBeEqualAreBothKept() {
        final EqualsEverything first = new EqualsEverything("first");
        final EqualsEverything second = new EqualsEverything("second");
        final LinkedIdentitySet<EqualsEverything> set = new LinkedIdentitySet<>();

        assertThat(set.add(first)).isTrue();
        assertThat(set.add(second)).isTrue();
        assertThat(set).hasSize(2);
        assertThat(new ArrayList<>(set)).containsExactly(first, second);
        assertThat(set.contains(first)).isTrue();
        assertThat(set.contains(second)).isTrue();
        assertThat(set.contains(new EqualsEverything("third"))).isFalse();
    }

    /** The very same object is only added once, and does not move to the end when it is added again. */
    @Test
    public void theSameObjectIsOnlyAddedOnce() {
        final EqualsEverything first = new EqualsEverything("first");
        final EqualsEverything second = new EqualsEverything("second");
        final LinkedIdentitySet<EqualsEverything> set = new LinkedIdentitySet<>();
        set.add(first);
        set.add(second);

        assertThat(set.add(first)).isFalse();
        assertThat(set).hasSize(2);
        assertThat(new ArrayList<>(set)).containsExactly(first, second);
    }

    /** The elements are iterated in the order they were added, and cannot be removed through the iterator. */
    @Test
    public void theElementsAreIteratedInTheOrderTheyWereAdded() {
        final LinkedIdentitySet<String> set = new LinkedIdentitySet<>();
        set.addAll(Arrays.asList("c", "a", "b"));

        assertThat(new ArrayList<>(set)).containsExactly("c", "a", "b");
        final Iterator<String> iterator = set.iterator();
        iterator.next();
        assertThatThrownBy(iterator::remove).isInstanceOf(UnsupportedOperationException.class);
    }

    /** A new set is empty. */
    @Test
    public void aNewSetIsEmpty() {
        final LinkedIdentitySet<String> set = new LinkedIdentitySet<>();
        assertThat(set).isEmpty();
        assertThat(set.contains("x")).isFalse();
    }
}
