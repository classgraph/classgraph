package com.xyz.fig;

import java.util.ArrayList;

import com.xyz.fig.shape.Shape;

@UIWidget
public class Figure implements Renderable, Saveable {
    ArrayList<? extends Shape> shape;
}
