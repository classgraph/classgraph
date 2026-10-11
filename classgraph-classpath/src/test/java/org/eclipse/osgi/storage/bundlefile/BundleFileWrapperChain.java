package org.eclipse.osgi.storage.bundlefile;

import org.jspecify.annotations.Nullable;

/**
 * Stand-in for Equinox' {@code BundleFileWrapperChain}, one link of the chain that {@code Storage#wrapBundleFile}
 * builds when a framework extension installs hooks that wrap bundle files. The link wraps either the original
 * bundle file or the wrapper that one hook returned, and the next link is the chain as it was before that hook ran,
 * so every link leads back to the same original bundle file.
 */
public class BundleFileWrapperChain extends BundleFileWrapper {
    /** The bundle file that this link wraps, which is also the bundle file it delegates to. */
    private final BundleFile wrapped;

    /**
     * The rest of the chain, or null if this is the last link. Not final, unlike in Equinox, only so that a test
     * can build a chain that loops back on itself.
     */
    private @Nullable BundleFileWrapperChain next;

    /**
     * Constructor.
     *
     * @param wrapped
     *            the bundle file that this link wraps.
     * @param next
     *            the rest of the chain, or null if this is the last link.
     */
    public BundleFileWrapperChain(final BundleFile wrapped, final @Nullable BundleFileWrapperChain next) {
        super(wrapped);
        this.wrapped = wrapped;
        this.next = next;
    }

    /**
     * The bundle file that this link wraps.
     *
     * @return the wrapped bundle file.
     */
    public BundleFile getWrapped() {
        return wrapped;
    }

    /**
     * The rest of the chain.
     *
     * @return the rest of the chain, or null if this is the last link.
     */
    public @Nullable BundleFileWrapperChain getNext() {
        return next;
    }

    /**
     * Set the rest of the chain.
     *
     * @param next
     *            the rest of the chain, or null if this is the last link.
     */
    public void setNext(final @Nullable BundleFileWrapperChain next) {
        this.next = next;
    }
}
