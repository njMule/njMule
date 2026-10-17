/*
 * njMule - Next generation Java Mule
 * SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See
 * the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If
 * not, see <https://www.gnu.org/licenses/>.
 */
package org.njmule.gui.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.ObjectBinding;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.css.CssMetaData;
import javafx.css.SimpleStyleableDoubleProperty;
import javafx.css.SimpleStyleableObjectProperty;
import javafx.css.StyleConverter;
import javafx.css.Styleable;
import javafx.css.StyleableDoubleProperty;
import javafx.css.StyleableObjectProperty;
import javafx.css.StyleableProperty;
import javafx.geometry.Insets;
import javafx.geometry.NodeOrientation;
import javafx.scene.Parent;
import javafx.scene.control.Labeled;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.transform.Scale;

/**
 * One of the {@link Icons}, drawn in the colour of the text it stands beside: the nearest {@link Labeled} above it
 * (button, label, cell, tab), so it follows the theme through hover, selection and disabled states. The stylesheet sets
 * {@code -nj-icon-size}, and {@code -nj-icon-color} where an icon has a colour of its own.
 */
public final class Icon extends Region {

    private static final double GRID = 24;
    private static final double STROKE = 1.75;
    private static final double DEFAULT_SIZE = 18;

    private static final CssMetaData<Icon, Number> SIZE =
            new CssMetaData<>("-nj-icon-size", StyleConverter.getSizeConverter(), DEFAULT_SIZE) {
                @Override
                public boolean isSettable(Icon icon) {
                    return !icon.size.isBound();
                }

                @Override
                public StyleableProperty<Number> getStyleableProperty(Icon icon) {
                    return icon.size;
                }
            };

    private static final CssMetaData<Icon, Paint> COLOR =
            new CssMetaData<>("-nj-icon-color", StyleConverter.getPaintConverter()) {
                @Override
                public boolean isSettable(Icon icon) {
                    return !icon.color.isBound();
                }

                @Override
                public StyleableProperty<Paint> getStyleableProperty(Icon icon) {
                    return icon.color;
                }
            };

    // For an icon with no text beside it, such as one alone in a row of a pane.
    private static final CssMetaData<Icon, Paint> FALLBACK =
            new CssMetaData<>("-nj-icon-fallback", StyleConverter.getPaintConverter(), Color.GRAY) {
                @Override
                public boolean isSettable(Icon icon) {
                    return !icon.fallback.isBound();
                }

                @Override
                public StyleableProperty<Paint> getStyleableProperty(Icon icon) {
                    return icon.fallback;
                }
            };

    private static final List<CssMetaData<? extends Styleable, ?>> CSS;

    static {
        List<CssMetaData<? extends Styleable, ?>> all = new ArrayList<>(Region.getClassCssMetaData());
        all.add(SIZE);
        all.add(COLOR);
        all.add(FALLBACK);
        CSS = List.copyOf(all);
    }

    private final SVGPath shape = new SVGPath();
    private final Scale scale = new Scale(1, 1, 0, 0);
    private final StyleableDoubleProperty size = new SimpleStyleableDoubleProperty(SIZE, this, "size", DEFAULT_SIZE);
    private final StyleableObjectProperty<Paint> color = new SimpleStyleableObjectProperty<>(COLOR, this, "color");
    private final StyleableObjectProperty<Paint> fallback =
            new SimpleStyleableObjectProperty<>(FALLBACK, this, "fallback", Color.GRAY);
    private final ObjectProperty<Paint> beside = new SimpleObjectProperty<>(this, "beside");
    private final ObjectBinding<Paint> paint;
    private Icons glyph;

    public Icon(Icons glyph) {
        getStyleClass().add("nj-icon");
        this.shape.getStyleClass().add("nj-icon-shape");
        this.shape.setManaged(false);
        this.shape.getTransforms().add(this.scale);
        this.shape.setStrokeWidth(STROKE);
        this.shape.setStrokeLineCap(StrokeLineCap.ROUND);
        this.shape.setStrokeLineJoin(StrokeLineJoin.ROUND);
        getChildren().add(this.shape);
        this.paint = Bindings.createObjectBinding(
                () -> this.color.get() != null
                        ? this.color.get()
                        : this.beside.get() != null ? this.beside.get() : this.fallback.get(),
                this.color,
                this.beside,
                this.fallback);
        this.size.addListener(o -> requestLayout());
        parentProperty().addListener(o -> follow());
        sceneProperty().addListener(o -> follow());
        setGlyph(glyph);
    }

    public Icons getGlyph() {
        return this.glyph;
    }

    public void setGlyph(Icons glyph) {
        this.glyph = Objects.requireNonNull(glyph);
        this.shape.setContent(glyph.path());
        // A right-to-left layout mirrors what it holds; an icon that points nowhere is drawn left to right again.
        setNodeOrientation(glyph.directional() ? NodeOrientation.INHERIT : NodeOrientation.LEFT_TO_RIGHT);
        this.shape.strokeProperty().unbind();
        this.shape.fillProperty().unbind();
        if (glyph.filled()) {
            this.shape.setStroke(null);
            this.shape.fillProperty().bind(this.paint);
        } else {
            this.shape.setFill(null);
            this.shape.strokeProperty().bind(this.paint);
        }
    }

    /** The colour the icon is drawn in now. */
    public Paint paint() {
        return this.paint.get();
    }

    public double getSize() {
        return this.size.get();
    }

    public void setSize(double size) {
        this.size.set(size);
    }

    @Override
    protected double computePrefWidth(double height) {
        Insets in = getInsets();
        return in.getLeft() + this.size.get() + in.getRight();
    }

    @Override
    protected double computePrefHeight(double width) {
        Insets in = getInsets();
        return in.getTop() + this.size.get() + in.getBottom();
    }

    @Override
    protected double computeMinWidth(double height) {
        return computePrefWidth(height);
    }

    @Override
    protected double computeMinHeight(double width) {
        return computePrefHeight(width);
    }

    @Override
    protected double computeMaxWidth(double height) {
        return computePrefWidth(height);
    }

    @Override
    protected double computeMaxHeight(double width) {
        return computePrefHeight(width);
    }

    @Override
    protected void layoutChildren() {
        Insets in = getInsets();
        double side = Math.min(getWidth() - in.getLeft() - in.getRight(), getHeight() - in.getTop() - in.getBottom());
        double factor = side / GRID;
        this.scale.setX(factor);
        this.scale.setY(factor);
        this.shape.setLayoutX(in.getLeft());
        this.shape.setLayoutY(in.getTop());
    }

    @Override
    public List<CssMetaData<? extends Styleable, ?>> getCssMetaData() {
        return CSS;
    }

    private void follow() {
        this.beside.unbind();
        Labeled labeled = labeledAbove();
        if (labeled == null) {
            this.beside.set(null);
        } else {
            this.beside.bind(labeled.textFillProperty());
        }
    }

    private Labeled labeledAbove() {
        for (Parent above = getParent(); above != null; above = above.getParent()) {
            if (above instanceof Labeled labeled) {
                return labeled;
            }
        }
        return null;
    }
}
