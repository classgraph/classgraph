package org.springframework.boot.devtools.restart.classloader;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * Stand-in for Spring Boot devtools' {@code RestartClassLoader}, the classloader that reloads the directories of an
 * application that are being watched for changes. Like the real class, it extends {@link URLClassLoader}, and is
 * constructed with the URLs of the directories it shades.
 */
public class RestartClassLoader extends URLClassLoader {
    /**
     * Constructor.
     *
     * @param parent
     *            the parent classloader, which loads the unchanged classes.
     * @param urls
     *            the URLs of the directories that are watched for changes.
     */
    public RestartClassLoader(final ClassLoader parent, final URL... urls) {
        super(urls, parent);
    }
}
