package io.github.classgraph.utils;

import org.springframework.boot.loader.jar.JarFile;

import java.io.File;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Fake {@link File} for nested jar files handled by Spring-Boot's {@link JarFile}-API.
 *
 * This class is only loaded when the Spring-Boot API is available, so it can use the
 * Spring-Boot types in it's interface.
 */
class NestedJarFile extends File {

    private final JarFile parentJarFile;
    private final ZipEntry nestedJarEntry;
    private final boolean directory;

    NestedJarFile(ZipFile parentJarFile, ZipEntry nestedJarEntry) {
        this((JarFile) parentJarFile, nestedJarEntry);
    }

    NestedJarFile(JarFile parentJarFile, ZipEntry nestedJarEntry) {
        super(parentJarFile.getName() + "!/" + nestedJarEntry.getName());
        this.parentJarFile = parentJarFile;
        this.nestedJarEntry = nestedJarEntry;
        this.directory = nestedJarEntry.isDirectory();
    }

    JarFile openAsJarFile() throws IOException {
        return parentJarFile.getNestedJarFile(nestedJarEntry);
    }

    @Override
    public boolean canRead() {
        return true;
    }

    @Override
    public boolean canWrite() {
        return false;
    }

    @Override
    public boolean isFile() {
        return !directory;
    }

    @Override
    public boolean isDirectory() {
        return directory;
    }

    @Override
    public boolean exists() {
        return true;
    }

    @Override
    public File getCanonicalFile() {
        return this;
    }
}
