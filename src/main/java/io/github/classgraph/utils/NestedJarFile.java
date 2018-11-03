package io.github.classgraph.utils;

import org.springframework.boot.loader.jar.JarFile;

import java.io.File;
import java.io.IOException;
import java.util.zip.ZipEntry;

public class NestedJarFile extends File {

    private final JarFile parentJarFile;
    private final ZipEntry nestedJarEntry;

    public NestedJarFile(JarFile parentJarFile, ZipEntry nestedJarEntry) {
        super(parentJarFile.getName() + "!" + nestedJarEntry.getName());
        this.parentJarFile = parentJarFile;
        this.nestedJarEntry = nestedJarEntry;
    }

    public JarFile openAsJarFile() throws IOException {
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
        return !nestedJarEntry.isDirectory();
    }

    @Override
    public boolean isDirectory() {
        return nestedJarEntry.isDirectory();
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
