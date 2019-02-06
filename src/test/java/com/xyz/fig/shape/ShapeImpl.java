package com.xyz.fig.shape;

import java.awt.Graphics2D;

/**
 * The Class ShapeImpl.
 */
public abstract class ShapeImpl implements Shape {

    /** The x. */
    private final float x;

    /** The y. */
    private final float y;

    /**
     * Instantiates a new shape impl.
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
     * Gets the x.
     *
     * @return the x
     */
    public float getX() {
        return x;
    }

    /**
     * Gets the y.
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
