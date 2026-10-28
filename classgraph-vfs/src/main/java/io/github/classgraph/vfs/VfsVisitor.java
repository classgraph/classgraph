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
 * Visitor for entries enumerated by {@link VfsRoot#walk(VfsVisitor)}.
 *
 * <p>
 * Walks are single-threaded and invoke {@link #enterDirectory(String)} for a directory before invoking
 * {@link #visitEntry(VfsEntry)} for any entry in it.
 */
public interface VfsVisitor {
    /**
     * Called for a directory before visiting any entry directly in it.
     *
     * <ul>
     * <li>For a directory tree, returning {@code false} skips both the directory's entries and all subdirectories
     * beneath it without listing them.
     * <li>For an archive or module, returning {@code false} skips only the entries directly in this directory;
     * subdirectories are still offered (so callers that strip a package root prefix such as
     * {@code "BOOT-INF/classes/"} can skip {@code "BOOT-INF/"} without skipping its subdirectories). Only
     * directories that directly contain at least one entry are offered, and a directory whose entries are not
     * contiguous may be offered more than once.
     * </ul>
     *
     * @param dirName
     *            the directory path relative to the package root, with a trailing {@code '/'} (e.g.
     *            {@code "com/xyz/"}, or {@code "/"} for the root itself).
     * @return true to visit the entries directly in this directory, or false to skip them.
     */
    boolean enterDirectory(String dirName);

    /**
     * Called for each entry in a directory accepted by {@link #enterDirectory(String)}.
     *
     * @param entry
     *            the entry.
     * @return true to continue walking, or false to stop the walk immediately.
     */
    boolean visitEntry(VfsEntry entry);
}
