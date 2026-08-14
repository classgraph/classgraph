/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2026 Luke Hutchison
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.github.classgraph.vfs;

/**
 * Receives the entries of a {@link VfsRoot} as {@link VfsRoot#walk(VfsVisitor)} enumerates them, and decides which
 * directories are worth enumerating at all.
 *
 * <p>
 * A walk is single-threaded, and calls {@link #enterDirectory(String)} for a directory before
 * {@link #visitEntry(VfsEntry)} for any entry in it, so a visitor that needs to work something out per directory
 * can work it out in {@link #enterDirectory(String)} and keep it in a field for {@link #visitEntry(VfsEntry)} to
 * read. The point of deciding per directory rather than per entry is that the answer is usually the same for every
 * entry in a directory, and for a directory tree an unwanted directory then need not be listed at all, which is the
 * bulk of the work of walking one.
 */
public interface VfsVisitor {
    /**
     * Called for a directory before any entry directly in it is visited, to decide whether those entries are
     * wanted.
     *
     * <p>
     * Returning false means no entry directly in this directory is wanted. How much else that skips depends on what
     * the root is made of, and the difference is worth understanding:
     *
     * <ul>
     * <li>For a directory tree, this method is called for every directory in the tree, parents before children, and
     * returning false skips the whole subtree without listing it -- not listing it is the entire saving.
     * <li>For a jarfile or a module, the entry list is already in hand, so nothing is saved by pruning, and this
     * method is called only for directories that directly contain at least one entry. Returning false skips just
     * that directory's entries; the directories below it are still offered.
     * </ul>
     *
     * <p>
     * That asymmetry is deliberate rather than a shortcut. A caller walking an archive may be stripping a package
     * root prefix such as {@code "BOOT-INF/classes/"} from the names before judging them, in which case
     * {@code "BOOT-INF/"} and the directory a name below it maps to after stripping are unrelated, so pruning the
     * former would wrongly discard the latter.
     *
     * <p>
     * For a jarfile or a module, a directory whose entries are not contiguous in the root's natural order is passed
     * to this method once per run of them, so a visitor must not assume it is called at most once per directory.
     *
     * @param dirName
     *            the name of the directory, relative to the root's package root, with a trailing {@code '/'}, e.g.
     *            {@code "com/xyz/"}. The root itself is {@code "/"}.
     * @return true to visit the entries directly in this directory, or false to skip them.
     */
    boolean enterDirectory(String dirName);

    /**
     * Called for each entry of a directory that {@link #enterDirectory(String)} did not skip.
     *
     * @param entry
     *            the entry.
     * @return true to go on walking, or false to stop the walk immediately.
     */
    boolean visitEntry(VfsEntry entry);
}
