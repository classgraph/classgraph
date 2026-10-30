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
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions
 * of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
 * TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
 * CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */
package io.github.classgraph.vfs.perf;

import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.apache.commons.vfs2.CacheStrategy;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.jboss.vfs.TempFileProvider;
import org.jboss.vfs.VirtualFile;
import org.openide.filesystems.FileUtil;
import org.openide.filesystems.JarFileSystem;
import org.openide.filesystems.LocalFileSystem;
import org.springframework.boot.loader.jar.NestedJarFile;

import io.github.classgraph.vfs.Vfs;
import io.github.classgraph.vfs.perf.BenchmarkFixtures.Content;
import io.github.classgraph.vfs.perf.BenchmarkFixtures.Layout;
import net.java.truevfs.access.TPath;
import net.java.truevfs.access.TVFS;

/**
 * The libraries the benchmarks compare, each with the way it is normally used to read an entry by name. Each
 * library supports only some of the {@link Layout}s; see {@link #supports(Layout)}.
 */
public enum Library {
    /** ClassGraph's virtual filesystem, through {@code VfsRoot.load(name)}. */
    CGVFS("classgraph-vfs", EnumSet.allOf(Layout.class)) {
        @Override
        public Reader open(final Path archive, final Layout layout) throws IOException {
            final var vfs = new Vfs();
            final var root = vfs
                    .open(layout.isNested() ? archive + "!/" + BenchmarkFixtures.INNER_JAR : archive.toString());
            return new Reader() {
                @Override
                public byte[] read(final String name) throws IOException {
                    final var content = root.load(name);
                    if (content == null) {
                        throw new FileNotFoundException(name);
                    }
                    return content;
                }

                @Override
                public void close() {
                    vfs.close();
                }
            };
        }
    },
    /** ClassGraph's virtual filesystem, through {@code Files.readAllBytes} on {@code VfsRoot.asFileSystem()}. */
    CGVFS_NIO("classgraph-vfs (nio)", EnumSet.allOf(Layout.class)) {
        @Override
        public Reader open(final Path archive, final Layout layout) throws IOException {
            final var vfs = new Vfs();
            final var fs = vfs
                    .open(layout.isNested() ? archive + "!/" + BenchmarkFixtures.INNER_JAR : archive.toString())
                    .asFileSystem();
            return new Reader() {
                @Override
                public byte[] read(final String name) throws IOException {
                    return Files.readAllBytes(fs.getPath("/" + name));
                }

                @Override
                public void close() {
                    vfs.close();
                }
            };
        }
    },
    /**
     * The plain JDK API for the layout: {@code Files.readAllBytes} for a directory, and one shared
     * {@code java.util.zip.ZipFile} for a jarfile.
     */
    JDK("JDK Files / ZipFile", EnumSet.of(Layout.DIR, Layout.STORED, Layout.DEFLATED)) {
        @Override
        public Reader open(final Path archive, final Layout layout) throws IOException {
            if (layout == Layout.DIR) {
                return new Reader() {
                    @Override
                    public byte[] read(final String name) throws IOException {
                        return Files.readAllBytes(archive.resolve(name));
                    }

                    @Override
                    public void close() {
                        // Nothing to close
                    }
                };
            }
            final var zipFile = new ZipFile(archive.toFile());
            return new Reader() {
                @Override
                public byte[] read(final String name) throws IOException {
                    final var entry = zipFile.getEntry(name);
                    if (entry == null) {
                        throw new FileNotFoundException(name);
                    }
                    try (var in = zipFile.getInputStream(entry)) {
                        return in.readAllBytes();
                    }
                }

                @Override
                public void close() throws IOException {
                    zipFile.close();
                }
            };
        }
    },
    /** The JDK's zip filesystem provider, with a nested jarfile opened as a zipfs within a zipfs. */
    ZIPFS("JDK zipfs", EnumSet.complementOf(EnumSet.of(Layout.DIR))) {
        @Override
        public Reader open(final Path archive, final Layout layout) throws IOException {
            final var outer = FileSystems.newFileSystem(archive);
            final FileSystem fs = layout.isNested()
                    ? FileSystems.newFileSystem(outer.getPath(BenchmarkFixtures.INNER_JAR))
                    : outer;
            return new Reader() {
                @Override
                public byte[] read(final String name) throws IOException {
                    return Files.readAllBytes(fs.getPath(name));
                }

                @Override
                public void close() throws IOException {
                    if (fs != outer) {
                        fs.close();
                    }
                    outer.close();
                }
            };
        }
    },
    /**
     * {@code URL.openStream()} on a {@code file:} or {@code jar:} URL. The JDK caches the {@code JarFile} behind a
     * {@code jar:} URL for the life of the JVM, so closing does nothing.
     */
    URL("JDK URL.openStream", EnumSet.of(Layout.DIR, Layout.STORED, Layout.DEFLATED)) {
        @Override
        public Reader open(final Path archive, final Layout layout) throws IOException {
            final var base = layout == Layout.DIR ? archive.toUri().toString() : "jar:" + archive.toUri() + "!/";
            return new Reader() {
                @Override
                public byte[] read(final String name) throws IOException {
                    try (var in = URI.create(base + name).toURL().openStream()) {
                        return in.readAllBytes();
                    }
                }

                @Override
                public void close() {
                    // The JarFile stays in the JDK's cache
                }
            };
        }
    },
    /**
     * {@code java.util.zip.ZipInputStream}, which can only read an archive from front to back, so is used only by
     * {@link WholeArchiveBenchmark}. A nested jarfile is read by wrapping a second stream around the entry.
     */
    ZIP_INPUT_STREAM("JDK ZipInputStream", EnumSet.noneOf(Layout.class)) {
        @Override
        public Reader open(final Path archive, final Layout layout) {
            throw new UnsupportedOperationException("ZipInputStream cannot read an entry by name");
        }

        @Override
        public boolean supportsWholeArchive(final Layout layout) {
            return layout != Layout.DIR;
        }

        @Override
        public long readWholeArchive(final Path archive, final Layout layout, final Iterable<String> names)
                throws IOException {
            try (var outer = new ZipInputStream(Files.newInputStream(archive))) {
                if (!layout.isNested()) {
                    return readAll(outer);
                }
                for (ZipEntry entry; (entry = outer.getNextEntry()) != null;) {
                    if (entry.getName().equals(BenchmarkFixtures.INNER_JAR)) {
                        return readAll(new ZipInputStream(outer));
                    }
                }
                throw new FileNotFoundException(BenchmarkFixtures.INNER_JAR);
            }
        }

        /**
         * Read every entry of a stream.
         *
         * @param zipInputStream
         *            the stream.
         * @return the number of bytes read.
         * @throws IOException
         *             if the stream could not be read.
         */
        private long readAll(final ZipInputStream zipInputStream) throws IOException {
            var total = 0L;
            while (zipInputStream.getNextEntry() != null) {
                total += zipInputStream.readAllBytes().length;
            }
            return total;
        }
    },
    /**
     * Apache Commons VFS, with every entry resolved against the archive's root {@code FileObject}.
     *
     * <p>
     * The manager is set to {@link CacheStrategy#MANUAL}, and the entries' {@code FileObject}s are not closed. A
     * {@code ZipFileObject} that is closed or refreshed closes its whole {@code ZipFileSystem}, including the
     * shared {@code ZipFile}, if no stream on that file system is open at that instant
     * ({@code ZipFileObject.doDetach} in 2.10.0), and the default strategy, {@link CacheStrategy#ON_RESOLVE},
     * refreshes a {@code FileObject} every time it is resolved. Under concurrency that check races with other
     * threads' reads, which then fail with {@code ZipFile closed}. Closing the manager closes every file system it
     * opened.
     */
    COMMONS_VFS("Apache Commons VFS", EnumSet.allOf(Layout.class)) {
        @Override
        public Reader open(final Path archive, final Layout layout) throws IOException {
            final var manager = new StandardFileSystemManager();
            manager.setCacheStrategy(CacheStrategy.MANUAL);
            manager.init();
            final FileObject root = switch (layout) {
            case DIR -> manager.resolveFile(archive.toUri());
            case STORED, DEFLATED -> manager.resolveFile("jar:" + archive + "!/");
            case NESTED_STORED, NESTED_DEFLATED ->
                manager.resolveFile("jar:jar:" + archive + "!/" + BenchmarkFixtures.INNER_JAR + "!/");
            };
            return new Reader() {
                @Override
                public byte[] read(final String name) throws IOException {
                    return root.resolveFile(name).getContent().getByteArray();
                }

                @Override
                public void close() {
                    manager.close();
                }
            };
        }
    },
    /** TrueVFS, through {@code Files.readAllBytes} on a {@code TPath}. */
    TRUEVFS("TrueVFS", EnumSet.allOf(Layout.class)) {
        @Override
        public Reader open(final Path archive, final Layout layout) throws IOException {
            // TPath(first, more...) joins its arguments with no separator, so each path is built as one string
            final var base = layout.isNested() ? archive + "/" + BenchmarkFixtures.INNER_JAR + "/" : archive + "/";
            // Mount the archive now, as the other libraries open theirs, rather than on the first read
            try (var dir = Files.newDirectoryStream(new TPath(base))) {
                dir.iterator().hasNext();
            }
            return new Reader() {
                @Override
                public byte[] read(final String name) throws IOException {
                    return Files.readAllBytes(new TPath(base + name));
                }

                @Override
                public void close() throws IOException {
                    TVFS.umount();
                }
            };
        }
    },
    /** JBoss VFS, with the jarfile mounted (and the nested jarfile mounted over its own entry). */
    JBOSS_VFS("JBoss VFS", EnumSet.allOf(Layout.class)) {
        @Override
        public Reader open(final Path archive, final Layout layout) throws IOException {
            if (layout == Layout.DIR) {
                final var root = org.jboss.vfs.VFS.getChild(archive.toString());
                return reader(root, () -> {
                    // Nothing to close
                });
            }
            // Each mount needs a mount point of its own
            final var mountPoint = org.jboss.vfs.VFS
                    .getChild("/cgvfs-bench/" + JBOSS_MOUNTS.incrementAndGet() + "/" + archive.getFileName());
            final var outerMount = org.jboss.vfs.VFS.mountZip(archive.toFile(), mountPoint,
                    jbossTempFileProvider());
            if (!layout.isNested()) {
                return reader(mountPoint, outerMount);
            }
            final var inner = mountPoint.getChild(BenchmarkFixtures.INNER_JAR);
            final var innerMount = org.jboss.vfs.VFS.mountZip(inner, inner, jbossTempFileProvider());
            return reader(inner, () -> {
                innerMount.close();
                outerMount.close();
            });
        }

        /**
         * A reader of the children of a {@code VirtualFile}.
         *
         * @param root
         *            the root to read entries relative to.
         * @param closeable
         *            closed when the reader is closed.
         * @return the reader.
         */
        private Reader reader(final VirtualFile root, final Closeable closeable) {
            return new Reader() {
                @Override
                public byte[] read(final String name) throws IOException {
                    try (var in = root.getChild(name).openStream()) {
                        return in.readAllBytes();
                    }
                }

                @Override
                public void close() throws IOException {
                    closeable.close();
                }
            };
        }
    },
    /** Okio's {@code ZipFileSystem}, opened with {@code Okio.openZip} over {@code FileSystem.SYSTEM}. */
    OKIO("Okio", EnumSet.of(Layout.DIR, Layout.STORED, Layout.DEFLATED)) {
        @Override
        public Reader open(final Path archive, final Layout layout) throws IOException {
            final var fs = layout == Layout.DIR ? okio.FileSystem.SYSTEM
                    : okio.Okio.openZip(okio.FileSystem.SYSTEM, okio.Path.get(archive.toString()));
            final var base = layout == Layout.DIR ? archive + "/" : "/";
            return new Reader() {
                @Override
                public byte[] read(final String name) throws IOException {
                    try (var source = okio.Okio.buffer(fs.source(okio.Path.get(base + name)))) {
                        return source.readByteArray();
                    }
                }

                @Override
                public void close() throws IOException {
                    if (fs != okio.FileSystem.SYSTEM) {
                        fs.close();
                    }
                }
            };
        }
    },
    /**
     * Spring Boot's {@code NestedJarFile}, which reads a stored nested jarfile in place. Spring Boot opens a
     * top-level jarfile with the JDK's {@code JarFile}, and requires nested jarfiles to be stored.
     */
    SPRING_BOOT("Spring Boot loader", EnumSet.of(Layout.NESTED_STORED)) {
        @Override
        public Reader open(final Path archive, final Layout layout) throws IOException {
            final var jarFile = new NestedJarFile(archive.toFile(), BenchmarkFixtures.INNER_JAR);
            return new Reader() {
                @Override
                public byte[] read(final String name) throws IOException {
                    final var entry = jarFile.getEntry(name);
                    if (entry == null) {
                        throw new FileNotFoundException(name);
                    }
                    try (var in = jarFile.getInputStream(entry)) {
                        return in.readAllBytes();
                    }
                }

                @Override
                public void close() throws IOException {
                    jarFile.close();
                }
            };
        }
    },
    /** Apache Commons Compress's {@code ZipFile}. */
    COMMONS_COMPRESS("Apache Commons Compress", EnumSet.of(Layout.STORED, Layout.DEFLATED)) {
        @Override
        public Reader open(final Path archive, final Layout layout) throws IOException {
            final var zipFile = org.apache.commons.compress.archivers.zip.ZipFile.builder().setPath(archive).get();
            return new Reader() {
                @Override
                public byte[] read(final String name) throws IOException {
                    final var entry = zipFile.getEntry(name);
                    if (entry == null) {
                        throw new FileNotFoundException(name);
                    }
                    try (var in = zipFile.getInputStream(entry)) {
                        return in.readAllBytes();
                    }
                }

                @Override
                public void close() throws IOException {
                    zipFile.close();
                }
            };
        }
    },
    /** The NetBeans Platform's {@code LocalFileSystem} and {@code JarFileSystem}. */
    NETBEANS("NetBeans filesystems", EnumSet.of(Layout.DIR, Layout.STORED, Layout.DEFLATED)) {
        /**
         * {@code JarFileSystem} up to at least RELEASE310 copies an entry over 100000 bytes to a temporary file
         * whose name depends only on the jarfile and entry, and each read copies it again, truncating the file
         * while streams returned to other threads are still reading it, so concurrent reads of
         * {@link Content#BINARY} come back short (apache/netbeans#9761). The fix (apache/netbeans#9762) removes
         * {@code temporaryName}, so {@link Content#BINARY} is benchmarked only when a fixed {@code JarFileSystem}
         * is ahead of the NetBeans jar on the classpath.
         */
        @Override
        public boolean supports(final Content content) {
            return content != Content.BINARY || Arrays.stream(JarFileSystem.class.getDeclaredMethods())
                    .noneMatch(method -> method.getName().equals("temporaryName"));
        }

        @Override
        public Reader open(final Path archive, final Layout layout) throws IOException {
            final org.openide.filesystems.FileSystem fs;
            if (layout == Layout.DIR) {
                final var localFs = new LocalFileSystem();
                try {
                    localFs.setRootDirectory(archive.toFile());
                } catch (final java.beans.PropertyVetoException e) {
                    throw new IOException(e);
                }
                fs = localFs;
            } else {
                // JarFileSystem rejects a file that is not normalized, such as one under a Windows 8.3 short name
                fs = new JarFileSystem(FileUtil.normalizeFile(new File(archive.toString())));
            }
            return new Reader() {
                @Override
                public byte[] read(final String name) throws IOException {
                    final var file = fs.findResource(name);
                    if (file == null) {
                        throw new FileNotFoundException(name);
                    }
                    return file.asBytes();
                }

                @Override
                public void close() {
                    // NetBeans has no close(); removeNotify() is what it calls when a filesystem is removed
                    fs.removeNotify();
                }
            };
        }
    };

    /** Reads entries from one archive by name. */
    public interface Reader extends Closeable {
        /**
         * Read an entry.
         *
         * @param name
         *            the entry's path relative to the root of the archive.
         * @return the entry's content.
         * @throws IOException
         *             if the entry could not be read.
         */
        byte[] read(String name) throws IOException;
    }

    /** How many JBoss VFS mounts have been made, to give each one a distinct mount point. */
    private static final AtomicInteger JBOSS_MOUNTS = new AtomicInteger();

    /** The temporary file provider JBoss VFS mounts use, created on first use. */
    private static TempFileProvider jbossTempFileProvider;

    /** The name the library is reported under. */
    private final String displayName;

    /** The layouts {@link #open(Path, Layout)} supports. */
    private final Set<Layout> layouts;

    /**
     * Constructor.
     *
     * @param displayName
     *            the name the library is reported under.
     * @param layouts
     *            the layouts {@link #open(Path, Layout)} supports.
     */
    Library(final String displayName, final Set<Layout> layouts) {
        this.displayName = displayName;
        this.layouts = layouts;
    }

    /** @return the name the library is reported under. */
    public String displayName() {
        return displayName;
    }

    /**
     * Whether entries can be read by name from a layout.
     *
     * @param layout
     *            the layout.
     * @return true if {@link #open(Path, Layout)} supports the layout.
     */
    public boolean supports(final Layout layout) {
        return layouts.contains(layout);
    }

    /**
     * Whether entries of a content type can be read correctly.
     *
     * @param content
     *            the content type.
     * @return true if the library can be benchmarked on the content type.
     */
    public boolean supports(final Content content) {
        return true;
    }

    /**
     * Whether a whole archive can be read in one of the layouts.
     *
     * @param layout
     *            the layout.
     * @return true if {@link #readWholeArchive(Path, Layout, Iterable)} supports the layout.
     */
    public boolean supportsWholeArchive(final Layout layout) {
        return supports(layout);
    }

    /**
     * Open an archive for reading entries by name.
     *
     * @param archive
     *            the directory or outer jarfile, from {@link BenchmarkFixtures#path(Content, Layout)}.
     * @param layout
     *            the layout of the archive.
     * @return a reader, which must be closed.
     * @throws IOException
     *             if the archive could not be opened.
     */
    public abstract Reader open(Path archive, Layout layout) throws IOException;

    /**
     * Open an archive, read every entry, and close it.
     *
     * @param archive
     *            the directory or outer jarfile.
     * @param layout
     *            the layout of the archive.
     * @param names
     *            the names of the entries.
     * @return the number of bytes read.
     * @throws IOException
     *             if the archive could not be read.
     */
    public long readWholeArchive(final Path archive, final Layout layout, final Iterable<String> names)
            throws IOException {
        var total = 0L;
        try (var reader = open(archive, layout)) {
            for (final var name : names) {
                total += reader.read(name).length;
            }
        }
        return total;
    }

    /**
     * The temporary file provider JBoss VFS mounts use.
     *
     * @return the provider.
     * @throws IOException
     *             if it could not be created.
     */
    private static synchronized TempFileProvider jbossTempFileProvider() throws IOException {
        if (jbossTempFileProvider == null) {
            final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
                final var thread = new Thread(r, "jboss-vfs-temp-cleanup");
                thread.setDaemon(true);
                return thread;
            });
            jbossTempFileProvider = TempFileProvider.create("cgvfs-bench", executor);
        }
        return jbossTempFileProvider;
    }
}
