package com.xyz.fig;

import java.awt.Graphics2D;
import java.util.ArrayList;

import com.xyz.fig.shape.Shape;

/**
 * SceneGraph.
 */
public class SceneGraph implements Drawable {
    /** The shapes. */
    ArrayList<Shape> shapes = new ArrayList<>();

    /**
     * Adds the shape.
     *
     * @param shape
     *            the shape
     */
    public void addShape(final Shape shape) {
        shapes.add(shape);
    }

    /**
     * Draw.
     *
     * @param g
     *            the g
     */
    /* (non-Javadoc)
     * @see com.xyz.fig.Drawable#draw(java.awt.Graphics2D)
     */
    @Override
    public void draw(final Graphics2D g) {
        for (final Shape shape : shapes) {
            shape.draw(g);
        }
    }
}
