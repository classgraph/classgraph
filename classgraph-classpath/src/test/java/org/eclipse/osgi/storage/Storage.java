package org.eclipse.osgi.storage;

import org.eclipse.osgi.container.ModuleContainer;
import org.jspecify.annotations.Nullable;

/** Stand-in for Equinox' {@code Storage}, which holds the module container and the path of the install area. */
public class Storage {
    /** The module container. */
    public final ModuleContainer moduleContainer;

    /** The path of the install area, against which a bundle location of the form "reference:file:x" resolves. */
    public final @Nullable String installPath;

    /**
     * Constructor.
     *
     * @param moduleContainer
     *            the module container.
     */
    public Storage(final ModuleContainer moduleContainer) {
        this(moduleContainer, /* installPath = */ null);
    }

    /**
     * Constructor.
     *
     * @param moduleContainer
     *            the module container.
     * @param installPath
     *            the path of the install area.
     */
    public Storage(final ModuleContainer moduleContainer, final @Nullable String installPath) {
        this.moduleContainer = moduleContainer;
        this.installPath = installPath;
    }
}
