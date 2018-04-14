package com.xyz.fig;

import java.awt.Graphics2D;

import com.xyz.fig.shape.Shape;

@UIWidget
public class Figure implements Drawable {
    SceneGraph sceneGraph = new SceneGraph();

    public void addShape(final Shape shape) {
        sceneGraph.addShape(shape);
    }

    @Override
    public void draw(final Graphics2D g) {
        sceneGraph.draw(g);
    }
}
