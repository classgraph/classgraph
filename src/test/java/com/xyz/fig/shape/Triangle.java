package com.xyz.fig.shape;

import java.awt.Graphics2D;

/**
 * Triangle.
 */
public class Triangle extends ShapeImpl {
    /** The edge len. */
    private final float edgeLen;

    /** The rotation. */
    private final float rotation;

    /**
     * Constructor.
     *
     * @param x
     *            the x
     * @param y
     *            the y
     * @param edgeLen
     *            the edge len
     * @param rotation
     *            the rotation
     */
    public Triangle(final float x, final float y, final float edgeLen, final float rotation) {
        super(x, y);
        this.edgeLen = edgeLen;
        this.rotation = rotation;
    }

    /**
     * Get the edge len.
     *
     * @return the edge len
     */
    public float getEdgeLen() {
        return edgeLen;
    }

    /**
     * Get the rotation.
     *
     * @return the rotation
     */
    public float getRotation() {
        return rotation;
    }

    /* (non-Javadoc)
     * @see com.xyz.fig.shape.ShapeImpl#draw(java.awt.Graphics2D)
     */
    @Override
    public void draw(final Graphics2D f) {
        throw new RuntimeException("Not implemented");
    }
}
