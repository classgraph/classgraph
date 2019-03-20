package com.xyz.fig.shape;

import java.awt.Graphics2D;

/**
 * Square.
 */
public class Square extends ShapeImpl {
    /** The size. */
    private final float size;

    /**
     * Constructor.
     *
     * @param x
     *            the x
     * @param y
     *            the y
     * @param size
     *            the size
     */
    public Square(final float x, final float y, final float size) {
        super(x, y);
        this.size = size;
    }

    /**
     * Get the size.
     *
     * @return the size
     */
    public float getSize() {
        return size;
    }

    /* (non-Javadoc)
     * @see com.xyz.fig.shape.ShapeImpl#draw(java.awt.Graphics2D)
     */
    @Override
    public void draw(final Graphics2D f) {
        throw new RuntimeException("Not implemented");
    }
}
