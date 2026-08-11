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
 * Copyright (c) 2019 Luke Hutchison
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

/**
 * Reads jarfiles, including jarfiles nested inside other jarfiles to any depth, without extracting them to disk.
 *
 * <p>
 * Start at {@link io.github.classgraph.vfs.ArchiveReader}, which opens an {@link io.github.classgraph.vfs.Archive}
 * of {@link io.github.classgraph.vfs.ArchiveEntry} instances:
 *
 * <pre>
 * try (ArchiveReader reader = new ArchiveReader()) {
 *     for (ArchiveEntry entry : reader.open("outer.jar!/lib/inner.jar").getEntries()) {
 *         System.out.println(entry.getName() + " (" + entry.getUncompressedSize() + " bytes)");
 *     }
 * }
 * </pre>
 *
 * <p>
 * The zipfile reader in this package is a good deal faster than {@link java.util.zip.ZipFile}, and unlike
 * {@link java.util.zip.ZipFile} it can read a jarfile that is nested inside another jarfile, as produced by Spring
 * Boot and other executable-jar formats. A nested jarfile is read in place through a slice of the enclosing
 * jarfile, rather than being extracted to a temporary directory first.
 *
 * <p>
 * An {@link io.github.classgraph.vfs.ArchiveReader} owns the file handles, memory mappings and temporary files that
 * back everything it opened, so it must be closed, and it must stay open for as long as its entries are being read.
 *
 * <p>
 * This package is {@link org.jspecify.annotations.NullMarked}: unless a type is annotated
 * {@link org.jspecify.annotations.Nullable}, it is never null. That applies in both directions -- a value returned
 * from this package is null only where the return type says so, and an argument passed to this package may be null
 * only where the parameter type says so.
 *
 * <p>
 * The {@code @NullMarked} contract is checked at compile time, and only for callers that run a null checker of
 * their own. Public methods in this package therefore also check their arguments at runtime, and throw
 * {@link NullPointerException} if a null is passed for a parameter that is not annotated
 * {@link org.jspecify.annotations.Nullable}, so that the failure happens at the call that passed the null rather
 * than deeper in the library, or silently, as a "not found" result. Individual methods do not repeat this in their
 * own documentation.
 *
 * @author Luke Hutchison
 */
@NullMarked
package io.github.classgraph.vfs;

import org.jspecify.annotations.NullMarked;
