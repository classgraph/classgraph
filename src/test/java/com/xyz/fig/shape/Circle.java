package com.xyz.fig.shape;

import java.awt.Graphics2D;

/**
 * Circle.
 */
public class Circle extends ShapeImpl {
    /** The r. */
    private final float r;

    /**
     * Constructor.
     *
     * @param x
     *            the x
     * @param y
     *            the y
     * @param r
     *            the r
     */
    public Circle(final float x, final float y, final float r) {
        super(x, y);
        this.r = r;
    }

    /**
     * Get the r.
     *
     * @return the r
     */
    public float getR() {
        return r;
    }

    /* (non-Javadoc)
     * @see com.xyz.fig.shape.ShapeImpl#draw(java.awt.Graphics2D)
     */
    @Override
    public void draw(final Graphics2D f) {
        throw new RuntimeException("Not implemented");
    }
}
