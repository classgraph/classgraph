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
 * read. The decision is made per directory rather than per entry because the answer is usually the same for every
 * entry in a directory, and because a directory tree then need not list an unwanted directory at all, which is most
 * of the work of walking one.
 */
public interface VfsVisitor {
    /**
     * Called for a directory before any entry directly in it is visited, to decide whether those entries are
     * wanted.
     *
     * <p>
     * Returning false means no entry directly in this directory is wanted. What else it skips depends on the kind
     * of root:
     *
     * <ul>
     * <li>For a directory tree, returning false also skips every directory below this one, without listing them.
     * This method is called for a directory after it is called for the directory's parent, and only if the parent
     * was not skipped.
     * <li>For a jarfile or a module, the entry list has already been read, so skipping a subtree would save
     * nothing. Returning false skips only this directory's own entries, and the directories below it are still
     * offered. This method is called only for directories that directly contain at least one entry.
     * </ul>
     *
     * <p>
     * An archive walk does not skip the directories below a skipped one because a caller may strip a package root
     * prefix such as {@code "BOOT-INF/classes/"} from the names before judging them. {@code "BOOT-INF/"} is then
     * unrelated to the directories below it once they are stripped, so skipping it must not skip them.
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
