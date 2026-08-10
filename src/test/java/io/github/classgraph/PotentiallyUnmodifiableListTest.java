package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;

/**
 * Tests for the iterators and views of {@link PotentiallyUnmodifiableList}. The mutators of the list itself are
 * covered by {@link ReturnedListsAreUnmodifiableTest}.
 */
public class PotentiallyUnmodifiableListTest {
    /**
     * A list of the given elements.
     *
     * @param elements
     *            the elements.
     * @return the list.
     */
    private static PotentiallyUnmodifiableList<String> listOf(final String... elements) {
        return new PotentiallyUnmodifiableList<>(List.of(elements));
    }

    /** A list iterator walks forwards and backwards through the list, reporting the index at each step. */
    @Test
    public void aListIteratorWalksForwardsAndBackwards() {
        final var iterator = listOf("a", "b").listIterator();
        assertThat(iterator.hasPrevious()).isFalse();
        assertThat(iterator.nextIndex()).isZero();
        assertThat(iterator.previousIndex()).isEqualTo(-1);

        assertThat(iterator.hasNext()).isTrue();
        assertThat(iterator.next()).isEqualTo("a");
        assertThat(iterator.next()).isEqualTo("b");
        assertThat(iterator.hasNext()).isFalse();
        assertThat(iterator.nextIndex()).isEqualTo(2);
        assertThat(iterator.previousIndex()).isEqualTo(1);

        assertThat(iterator.hasPrevious()).isTrue();
        assertThat(iterator.previous()).isEqualTo("b");
        assertThat(iterator.previous()).isEqualTo("a");
        assertThat(iterator.hasPrevious()).isFalse();
    }

    /** A list iterator can be started part-way through the list. */
    @Test
    public void aListIteratorCanStartPartWayThroughTheList() {
        final var iterator = listOf("a", "b", "c").listIterator(2);
        assertThat(iterator.nextIndex()).isEqualTo(2);
        assertThat(iterator.next()).isEqualTo("c");
        assertThat(iterator.hasNext()).isFalse();
    }

    /** The plain iterator walks forwards through the list. */
    @Test
    public void thePlainIteratorWalksForwards() {
        final var iterator = listOf("a", "b").iterator();
        assertThat(iterator.hasNext()).isTrue();
        assertThat(iterator.next()).isEqualTo("a");
        assertThat(iterator.next()).isEqualTo("b");
        assertThat(iterator.hasNext()).isFalse();
        assertThatThrownBy(iterator::next).isInstanceOf(NoSuchElementException.class);
    }

    /**
     * An iterator over an empty list reports that there is nothing to iterate over without consulting the iterator
     * of the underlying list, so that a thread sorting the empty list at the same time cannot cause a
     * {@link java.util.ConcurrentModificationException} here.
     */
    // #334
    @Test
    public void anIteratorOverAnEmptyListReportsNothingToIterateOver() {
        final var emptyList = listOf();
        assertThat(emptyList.iterator().hasNext()).isFalse();

        final var listIterator = emptyList.listIterator();
        assertThat(listIterator.hasNext()).isFalse();
        assertThat(listIterator.hasPrevious()).isFalse();
        assertThat(listIterator.nextIndex()).isZero();
        assertThat(listIterator.previousIndex()).isEqualTo(-1);
    }

    /**
     * The same check also stops an iterator that was created before the list was emptied from throwing
     * {@link java.util.ConcurrentModificationException}: it reports that there is nothing left instead.
     */
    // #334
    @Test
    public void anIteratorCreatedBeforeTheListWasEmptiedReportsNothingLeft() {
        final var list = listOf("a", "b");
        final var iterator = list.iterator();
        final var listIterator = list.listIterator(1);
        list.clear();

        assertThat(iterator.hasNext()).isFalse();
        assertThat(listIterator.hasNext()).isFalse();
        assertThat(listIterator.hasPrevious()).isFalse();
        assertThat(listIterator.nextIndex()).isZero();
        assertThat(listIterator.previousIndex()).isEqualTo(-1);
    }

    /** While the list is still modifiable, its iterators can change it. */
    @Test
    public void aModifiableListCanBeChangedThroughItsIterators() {
        final var list = listOf("a", "b");
        final var listIterator = list.listIterator();
        listIterator.next();
        listIterator.set("A");
        listIterator.add("a and a half");
        listIterator.next();
        listIterator.remove();
        assertThat(list).containsExactly("A", "a and a half");

        final var iterator = list.iterator();
        iterator.next();
        iterator.remove();
        assertThat(list).containsExactly("a and a half");
    }

    /** Once the list has been made unmodifiable, its iterators cannot change it. */
    @Test
    public void anUnmodifiableListCannotBeChangedThroughItsIterators() {
        final var list = listOf("a", "b");
        assertThat(PotentiallyUnmodifiableList.unmodifiable(list)).isSameAs(list);

        final var listIterator = list.listIterator();
        listIterator.next();
        assertThatThrownBy(() -> listIterator.set("A")).isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("List is immutable");
        assertThatThrownBy(() -> listIterator.add("c")).isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("List is immutable");
        assertThatThrownBy(listIterator::remove).isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("List is immutable");

        final var iterator = list.iterator();
        iterator.next();
        assertThatThrownBy(iterator::remove).isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("List is immutable");
        assertThat(list).containsExactly("a", "b");
    }

    /**
     * A sublist of a modifiable list is a writable view of it, but a sublist of an unmodifiable list is
     * unmodifiable too -- {@link java.util.ArrayList#subList} returns a view that writes straight to the backing
     * array, bypassing the checks in this class, so the view has to be wrapped.
     */
    @Test
    public void aSublistIsOnlyModifiableIfTheListItselfIs() {
        final var modifiableList = listOf("a", "b", "c");
        modifiableList.subList(0, 1).clear();
        assertThat(modifiableList).containsExactly("b", "c");

        final var unmodifiableList = PotentiallyUnmodifiableList.unmodifiable(listOf("a", "b", "c"));
        final var subList = unmodifiableList.subList(0, 2);
        assertThat(subList).containsExactly("a", "b");
        assertThatThrownBy(subList::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThat(unmodifiableList).containsExactly("a", "b", "c");
    }
}
