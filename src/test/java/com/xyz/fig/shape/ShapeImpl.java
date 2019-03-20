package com.xyz.fig.shape;

import java.awt.Graphics2D;

/**
 * ShapeImpl.
 */
public abstract class ShapeImpl implements Shape {

    /** The x. */
    private final float x;

    /** The y. */
    private final float y;

    /**
     * Constructor.
     *
     * @param x
     *            the x
     * @param y
     *            the y
     */
    public ShapeImpl(final float x, final float y) {
        this.x = x;
        this.y = y;
    }

    /**
     * Get the x.
     *
     * @return the x
     */
    public float getX() {
        return x;
    }

    /**
     * Get the y.
     *
     * @return the y
     */
    public float getY() {
        return y;
    }

    /* (non-Javadoc)
     * @see com.xyz.fig.Drawable#draw(java.awt.Graphics2D)
     */
    @Override
    public abstract void draw(Graphics2D f);
}
