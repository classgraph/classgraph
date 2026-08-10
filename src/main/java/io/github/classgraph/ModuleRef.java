/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 Luke Hutchison
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.github.classgraph;

import java.io.File;
import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import nonapi.io.github.classgraph.utils.Assert;
import nonapi.io.github.classgraph.utils.CollectionUtils;
import org.jspecify.annotations.Nullable;

/**
 * Information about a module: its {@link ModuleReference}, its {@link ModuleLayer}, and its classloader.
 */
public class ModuleRef implements Comparable<ModuleRef> {
    /** The name of the module. */
    private final String name;

    /** The ModuleReference for the module. */
    private final ModuleReference reference;

    /** The ModuleLayer for the module. */
    private final ModuleLayer layer;

    /** The ModuleDescriptor for the module. */
    private final ModuleDescriptor descriptor;

    /** The packages in the module. */
    private final List<String> packages;

    /** The location URI for the module (may be null). */
    private final @Nullable URI location;

    /** The location URI for the module, as a cached string (may be null). */
    private @Nullable String locationStr;

    /**
     * A file formed from the location URI. The file will not exist if the location URI is a "jrt:" URI.
     */
    private @Nullable File locationFile;

    /** The raw module version, or null if none. */
    private final @Nullable String rawVersion;

    /**
     * The ClassLoader that loads classes in the module. May be null, to represent the bootstrap classloader.
     */
    private final @Nullable ClassLoader classLoader;

    /**
     * Wrap a {@link ModuleReference} and the {@link ModuleLayer} it was resolved in, reading the module's
     * descriptor, package list, location and classloader eagerly.
     *
     * @param moduleReference
     *            The {@link ModuleReference} for the module.
     * @param moduleLayer
     *            The {@link ModuleLayer} that the module was resolved in.
     */
    public ModuleRef(final ModuleReference moduleReference, final ModuleLayer moduleLayer) {
        Assert.notNull(moduleReference, "moduleReference");
        Assert.notNull(moduleLayer, "moduleLayer");
        this.reference = moduleReference;
        this.layer = moduleLayer;

        this.descriptor = moduleReference.descriptor();
        if (this.descriptor == null) {
            // Should not happen
            throw new IllegalArgumentException("moduleReference.descriptor() should not return null");
        }
        this.name = this.descriptor.name();
        final var modulePackages = this.descriptor.packages();
        if (modulePackages == null) {
            // Should not happen
            throw new IllegalArgumentException("moduleReference.descriptor().packages() should not return null");
        }
        this.packages = new ArrayList<>(modulePackages);
        CollectionUtils.sortIfNotEmpty(this.packages);
        this.rawVersion = this.descriptor.rawVersion().orElse(null);
        final var moduleLocationOptional = moduleReference.location();
        if (moduleLocationOptional == null) {
            // Should not happen
            throw new IllegalArgumentException("moduleReference.location() should not return null");
        }
        this.location = moduleLocationOptional.orElse(null);

        // Find the classloader for the module
        this.classLoader = moduleLayer.findLoader(this.name);
    }

    /**
     * Get the module name, i.e. {@code getReference().descriptor().name()}.
     *
     * @return The module name, i.e. {@code getReference().descriptor().name()}. May be empty for an unnamed
     *         (automatic) module.
     */
    public String getName() {
        return name;
    }

    /**
     * Get the {@link ModuleReference} that this {@link ModuleRef} wraps.
     *
     * @return The {@link ModuleReference} for the module.
     */
    public ModuleReference getReference() {
        return reference;
    }

    /**
     * Get the {@link ModuleLayer} that the module was resolved in.
     *
     * @return The {@link ModuleLayer} that the module was resolved in.
     */
    public ModuleLayer getLayer() {
        return layer;
    }

    /**
     * Get the module descriptor, i.e. {@code getReference().descriptor()}.
     *
     * @return The module descriptor, i.e. {@code getReference().descriptor()}.
     */
    public ModuleDescriptor getDescriptor() {
        return descriptor;
    }

    /**
     * Get a list of packages in the module. (Does not include non-package directories.)
     *
     * @return The list of packages in the module. (Does not include non-package directories.)
     */
    public List<String> getPackages() {
        return packages;
    }

    /**
     * Get the module location, i.e. {@code getReference().location()}. Returns null for modules that do not have a
     * location.
     *
     * @return The module location, i.e. {@code getReference().location()}. Returns null for modules that do not
     *         have a location.
     */
    public @Nullable URI getLocation() {
        return location;
    }

    /**
     * Get the module location as a string, i.e. {@code getReference().location().toString()}. Returns null for
     * modules that do not have a location.
     *
     * @return The module location as a string, i.e. {@code getReference().location().toString()}. Returns null for
     *         modules that do not have a location.
     */
    public @Nullable String getLocationString() {
        var str = locationStr;
        if (str == null && location != null) {
            locationStr = str = location.toString();
        }
        return str;
    }

    /**
     * Get the module location as a File, i.e. {@code new File(getReference().location())}. Returns null for modules
     * that do not have a location, or for system (or jlinked) modules, which have "jrt:" location URIs that include
     * only the module name and not the module jar location.
     *
     * @return The module location as a File, i.e. {@code new File(getReference().location())}. Returns null for
     *         modules that do not have a location, or for modules whole location is a "jrt:" URI.
     */
    public @Nullable File getLocationFile() {
        var file = locationFile;
        if (file == null && location != null && "file".equals(location.getScheme())) {
            locationFile = file = new File(location);
        }
        return file;
    }

    /**
     * Get the raw version string of the module, or null if the module did not provide one.
     *
     * @return The raw version of the module, obtained by {@code ModuleReference#rawVersion().orElse(null)}.
     */
    public @Nullable String getRawVersion() {
        return rawVersion;
    }

    /**
     * Checks if this module is a system module.
     *
     * @return true if this module is a system module.
     */
    public boolean isSystemModule() {
        if (name.isEmpty()) {
            return false;
        }
        return name.startsWith("java.") || name.startsWith("jdk.") || name.startsWith("javafx.")
                || name.startsWith("oracle.");
    }

    /**
     * Get the class loader for the module.
     *
     * @return The classloader for the module, i.e.
     *         {@code moduleLayer.findLoader(getReference().descriptor().name())}.
     */
    public @Nullable ClassLoader getClassLoader() {
        return classLoader;
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final @Nullable Object obj) {
        return obj == this || obj instanceof final ModuleRef modRef && modRef.reference.equals(this.reference)
                && modRef.layer.equals(this.layer);
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return reference.hashCode() * layer.hashCode();
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return reference.toString();
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Comparable#compareTo(java.lang.Object)
     */
    @Override
    public int compareTo(final ModuleRef o) {
        final var diff = this.name.compareTo(o.name);
        // Compare hashcodes rather than subtracting them, since the subtraction can overflow, which would break the
        // transitivity that the Comparable contract requires
        return diff != 0 ? diff : Integer.compare(this.hashCode(), o.hashCode());
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Open the module, returning a {@link ModuleReader}.
     *
     * @return A {@link ModuleReader} for the module.
     * @throws IOException
     *             If the module cannot be opened.
     */
    public ModuleReader open() throws IOException {
        final ModuleReader moduleReader;
        try {
            moduleReader = reference.open();
        } catch (final SecurityException e) {
            throw new IOException("Could not open module " + name, e);
        }
        if (moduleReader == null) {
            throw new IllegalArgumentException("moduleReference.open() should not return null");
        }
        return moduleReader;
    }
}
