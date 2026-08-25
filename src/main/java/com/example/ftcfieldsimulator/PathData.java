package com.example.ftcfieldsimulator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class PathData {
    public String name;
    public List<CurvePoint> points;
    public String followAngle;

    public PathData(String name) {
        this.name = name;
        this.points = new ArrayList<>();
        this.followAngle = "90.0";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PathData pathData = (PathData) o;
        return Objects.equals(name, pathData.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public String toString() {
        return name;
    }
}
