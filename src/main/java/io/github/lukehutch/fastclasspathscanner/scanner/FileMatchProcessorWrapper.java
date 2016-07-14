package io.github.lukehutch.fastclasspathscanner.scanner;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/** An interface called when the corresponding FilePathTester returns true. */
interface FileMatchProcessorWrapper {
    public void processMatch(final File classpathElt, final String relativePath, final InputStream inputStream,
            final long fileSize) throws IOException;
}