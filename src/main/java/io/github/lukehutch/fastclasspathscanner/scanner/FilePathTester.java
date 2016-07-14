package io.github.lukehutch.fastclasspathscanner.scanner;

import java.io.File;

/** An interface used to test whether a file's relative path matches a given specification. */
public interface FilePathTester {
    public boolean filePathMatches(final File classpathElt, final String relativePathStr);
}