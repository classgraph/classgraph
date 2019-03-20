package com.xyz.fig;

import java.awt.Graphics2D;

import com.xyz.fig.shape.Shape;

/**
 * Figure.
 */
@UIWidget
public class Figure implements Drawable {
    /** The scene graph. */
    SceneGraph sceneGraph = new SceneGraph();

    /**
     * Adds the shape.
     *
     * @param shape
     *            the shape
     */
    public void addShape(final Shape shape) {
        sceneGraph.addShape(shape);
    }

    /* (non-Javadoc)
     * @see com.xyz.fig.Drawable#draw(java.awt.Graphics2D)
     */
    @Override
    public void draw(final Graphics2D g) {
        sceneGraph.draw(g);
    }
}
