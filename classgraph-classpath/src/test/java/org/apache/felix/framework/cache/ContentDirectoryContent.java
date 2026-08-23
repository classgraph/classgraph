package org.apache.felix.framework.cache;

/**
 * Stand-in for Felix' {@code ContentDirectoryContent}, a directory entry on a bundle's {@code Bundle-ClassPath}
 * within the bundle jarfile. It has no file of its own -- it is a subdirectory of the {@code Content} it is within.
 */
public class ContentDirectoryContent {
    /** The content that the directory lives inside. */
    private final Object m_content;

    /** The path of the directory within that content, always with a trailing {@code "/"}. */
    private final String m_rootPath;

    /**
     * Constructor.
     *
     * @param content
     *            the content that the directory lives inside.
     * @param path
     *            the path of the directory within that content.
     */
    public ContentDirectoryContent(final Object content, final String path) {
        this.m_content = content;
        // Felix adds a '/' to the end of the path if it has none
        this.m_rootPath = !path.isEmpty() && path.charAt(path.length() - 1) != '/' ? path + "/" : path;
    }

    /**
     * The content that the directory lives inside.
     *
     * @return the content.
     */
    public Object getContent() {
        return m_content;
    }

    /**
     * The path of the directory within that content.
     *
     * @return the path.
     */
    public String getRootPath() {
        return m_rootPath;
    }
}
