package org.apache.felix.framework.util;

/**
 * Stand-in for Felix' {@code MultiReleaseContent}, which Felix wraps around every content of a bundle whose
 * manifest says {@code Multi-Release: true}. It serves the whole of the content it wraps, and has no file of its
 * own.
 */
public class MultiReleaseContent {
    /** The content that this content serves a Java-version-specific view of. */
    private final Object m_content;

    /**
     * Constructor.
     *
     * @param content
     *            the content to wrap.
     */
    public MultiReleaseContent(final Object content) {
        this.m_content = content;
    }

    /**
     * The content that this content serves a Java-version-specific view of.
     *
     * @return the wrapped content.
     */
    public Object getContent() {
        return m_content;
    }
}
