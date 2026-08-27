package com.example.ftcfieldsimulator;

import java.util.Locale;

public class AutonomousStep {
    public enum Type {
        PATH_HEADER,
        POINT,
        WAIT,
        CMD,
        COMMENT,
        EMPTY
    }

    public Type type;
    public String rawLine;

    // References for sync
    public PathData pathRef;
    public CurvePoint pointRef;

    public String name;
    public double value; // For WAIT time or Heading or Transition dist
    public String commandClass;
    public String commandArgs;
    public String transition; // IMMEDIATE, DIST

    public AutonomousStep(Type type, String rawLine) {
        this.type = type;
        this.rawLine = rawLine;
    }

    public void updatePoint(CurvePoint p) {
        if (type != Type.POINT) return;
        this.rawLine = String.format(Locale.US, "P: %.2f, %.2f, %.2f, %.2f, %.1f, %.1f, %.2f",
                p.x, p.y, p.moveSpeed, p.turnSpeed, p.followDistance,
                Math.toDegrees(p.slowDownTurnRadians), p.slowDownTurnAmount);
    }

    public void updatePathHeader(String name, String headingExpr, String transition) {
        if (type != Type.PATH_HEADER) return;
        this.rawLine = String.format(Locale.US, "PATH: name=\"%s\" | heading=%s | transition=%s",
                name, headingExpr, transition);
    }

    public void updatePathHeader(String name, double fieldHeading, String transition) {
        this.rawLine = String.format(Locale.US, "PATH: name=\"%s\" | heading=%.2f | transition=%s",
                name, fieldHeading, transition);
    }

    public static AutonomousStep pathHeader(String name, String headingExpr, String transition) {
        String line = String.format(Locale.US, "PATH: name=\"%s\" | heading=%s | transition=%s",
                name, headingExpr, transition);
        return new AutonomousStep(Type.PATH_HEADER, line);
    }

    public static AutonomousStep pathHeader(String name, double fieldHeading, String transition) {
        String line = String.format(Locale.US, "PATH: name=\"%s\" | heading=%.2f | transition=%s",
                name, fieldHeading, transition);
        return new AutonomousStep(Type.PATH_HEADER, line);
    }

    public static AutonomousStep point(CurvePoint p) {
        String line = String.format(Locale.US, "P: %.2f, %.2f, %.2f, %.2f, %.1f, %.1f, %.2f",
                p.x, p.y, p.moveSpeed, p.turnSpeed, p.followDistance,
                Math.toDegrees(p.slowDownTurnRadians), p.slowDownTurnAmount);
        return new AutonomousStep(Type.POINT, line);
    }

    public static AutonomousStep waitStep(double seconds) {
        String line = String.format(Locale.US, "WAIT: %.1f", seconds);
        return new AutonomousStep(Type.WAIT, line);
    }

    public static AutonomousStep command(String className, String args, String name) {
        StringBuilder sb = new StringBuilder("CMD: ").append(className);
        if (args != null && !args.isEmpty()) {
            sb.append(" | args=[").append(args).append("]");
        }
        if (name != null && !name.isEmpty()) {
            sb.append(" | name=\"").append(name).append("\"");
        }
        return new AutonomousStep(Type.CMD, sb.toString());
    }

    public static AutonomousStep init(double x, double y, double h) {
        String line = String.format(Locale.US, "CMD: SetInitialPoseCommand | args=[%.2f, %.2f, %.2f] | name=\"Start Pose\"", x, y, h);
        return new AutonomousStep(Type.CMD, line);
    }

    @Override
    public String toString() {
        return rawLine;
    }
}
