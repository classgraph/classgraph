package com.xyz.fig;

import java.awt.Graphics2D;
import java.util.ArrayList;

import com.xyz.fig.shape.Shape;

public class SceneGraph implements Drawable {
    ArrayList<Shape> shapes = new ArrayList<>();

    public void addShape(final Shape shape) {
        shapes.add(shape);
    }

    @Override
    public void draw(final Graphics2D g) {
        for (final Shape shape : shapes) {
            shape.draw(g);
        }
    }
}
