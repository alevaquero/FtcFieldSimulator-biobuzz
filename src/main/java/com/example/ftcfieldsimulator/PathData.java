package com.example.ftcfieldsimulator;

import java.util.ArrayList;
import java.util.List;

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
    public String toString() {
        return name;
    }
}
