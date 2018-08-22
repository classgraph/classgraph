package io.github.classgraph.utils;

import java.io.File;
import java.io.IOException;
import java.util.Map.Entry;
import java.util.Set;
import java.util.zip.ZipFile;

import io.github.classgraph.ModuleReaderProxy;
import io.github.classgraph.ModuleRef;

public class NoopNestedJarHandler implements NestedJarHandler {

    @Override
    public Recycler<ZipFile, IOException> getZipFileRecycler(File zipFile, LogNode log) throws Exception {
        return null;
    }

    @Override
    public JarfileMetadataReader getJarfileMetadataReader(File zipFile, String jarfilePackageRoot, LogNode log)
            throws Exception {
        return null;
    }

    @Override
    public Recycler<ModuleReaderProxy, IOException> getModuleReaderProxyRecycler(ModuleRef moduleRef, LogNode log)
            throws Exception {
        return null;
    }

    @Override
    public Entry<File, Set<String>> getInnermostNestedJar(String nestedJarPath, LogNode log) throws Exception {
        return null;
    }

    @Override
    public File getOutermostJar(File jarFile) {
        return null;
    }

    @Override
    public File unzipToTempDir(File jarFile, String packageRoot, LogNode log) throws IOException {
        return null;
    }

    @Override
    public void closeRecyclers() {
    }

    @Override
    public void close(LogNode log) {
    }
}
