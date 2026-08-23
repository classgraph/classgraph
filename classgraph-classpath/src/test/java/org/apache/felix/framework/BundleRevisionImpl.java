package org.apache.felix.framework;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Stand-in for Felix' {@code BundleRevisionImpl}, one revision of an installed bundle. The revision's own contents
 * are its bundle jar; its content path additionally holds the jars embedded in the bundle by its
 * {@code Bundle-ClassPath} header.
 */
public class BundleRevisionImpl {
    /** The bundle's own contents. */
    private final @Nullable Object content;

    /**
     * The bundle's own contents, followed by the contents embedded in the bundle. Not typed as {@code Content},
     * because Felix puts contents of several unrelated types on the content path.
     */
    private final List<Object> contentPath = new ArrayList<>();

    /**
     * Constructor.
     *
     * @param content
     *            the bundle's own contents, or null if the bundle has none.
     */
    public BundleRevisionImpl(final @Nullable Object content) {
        this.content = content;
        if (content != null) {
            contentPath.add(content);
        }
    }

    /**
     * Add contents embedded in the bundle by its {@code Bundle-ClassPath} header.
     *
     * @param embedded
     *            the embedded contents.
     * @return this, for chaining.
     */
    public BundleRevisionImpl embedding(final Object embedded) {
        contentPath.add(embedded);
        return this;
    }

    /**
     * The bundle's own contents.
     *
     * @return the contents, or null if the bundle has none.
     */
    public @Nullable Object getContent() {
        return content;
    }

    /**
     * The bundle's own contents, followed by the contents embedded in the bundle.
     *
     * @return the content path.
     */
    public List<Object> getContentPath() {
        return contentPath;
    }
}
