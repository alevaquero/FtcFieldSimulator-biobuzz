package com.example.ftcfieldsimulator; // Adjust package as needed

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

import javafx.geometry.Point2D;
import javafx.geometry.VPos;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.scene.transform.Rotate;

public class FieldDisplay extends Pane {
    private final Canvas canvas;
    private final GraphicsContext gc;
    private final Label coordinateLabel;
    private final Label instructionLabel;

    // These remain the logical dimensions of the field itself.
    // fieldWidthInches is the extent along the new HORIZONTAL Field Y-axis.
    // fieldHeightInches is the extent along the new VERTICAL Field X-axis.
    private final double fieldWidthInches_HorizontalY;
    private final double fieldHeightInches_VerticalX;

    private Image fieldImage;
    private double fieldImageAlpha;
    private double backgroundAlpha;
    private Robot robot;

    private CurvePoint highlightedPoint = null;

    // Renamed scaling factors for clarity with the new coordinate system
    private double scaleForFieldX_Vertical;  // Canvas pixels (vertical) per inch of Field X (vertical)
    private double scaleForFieldY_Horizontal; // Canvas pixels (horizontal) per inch of Field Y (horizontal)

    // robotTrailDots stores points in CANVAS PIXEL coordinates
    private List<Point2D> robotTrailDots;
    private static final int MAX_TRAIL_DOTS = 5000;
    private static final double TRAIL_DOT_RADIUS_PIXELS = 4.0;

    public enum LineStyle {
        SOLID_THICK(1),  // Style 1: Solid, 3px width (default color to be decided by renderer)
        SOLID_THIN(2),   // Style 2: Solid, 1.5px width (different default color by renderer)
        DOTTED(3);       // Style 3: Dotted

        private final int styleCode;
        LineStyle(int code) { this.styleCode = code; }
        public int getStyleCode() { return styleCode; }

        public static LineStyle fromCode(int code) {
            for (LineStyle style : values()) {
                if (style.styleCode == code) {
                    return style;
                }
            }
            return SOLID_THICK; // Default if code is invalid
        }
    }

    private static class DebugCircle {
        // centerXInches is new Field X (vertical), centerYInches is new Field Y (horizontal)
        final double fieldX, fieldY, radiusInches;
        final double headingDegrees; // Assumed to be relative to new Field X (0 deg = down screen)
        final Color color;

        DebugCircle(double fieldX, double fieldY, double radiusInches, double headingDegrees, Color color) {
            this.fieldX = fieldX;
            this.fieldY = fieldY;
            this.radiusInches = radiusInches;
            this.headingDegrees = headingDegrees;
            this.color = color;
        }
    }
    private DebugCircle currentDebugCircle = null;
    private final Object debugCircleLock = new Object();

    private Map<String, UdpPositionListener.LineData> namedLinesMap = new HashMap<>();
    private final Object namedLinesMapLock = new Object();

    // allPathsToDraw stores all the paths created
    private List<PathData> allPathsToDraw = new ArrayList<>();
    // activePath points to the path currently being edited
    private PathData activePath = null;
    // currentPathToDraw stores points as (FieldX, FieldY) based on the new system
    private List<CurvePoint> currentPathToDraw = new ArrayList<>();
    private Consumer<Point2D> onFieldPointClickListener;
    private Runnable onPathFinishListener;
    private boolean isPathCreationMode = false;

    private String currentRobotTextMessage = null;
    private final Object robotTextLock = new Object();
    private static final Font ROBOT_TEXT_FONT = Font.font("Arial", 12);
    private static final Color ROBOT_TEXT_COLOR = Color.LIGHTGRAY;
    private static final Color ROBOT_TEXT_BACKGROUND_COLOR = Color.rgb(0, 0, 0, 0.5);
    private static final double ROBOT_TEXT_PADDING = 3.0;
    // This offset is in canvas pixels, its direction (e.g., "below") is relative to robot's canvas orientation
    private static final double ROBOT_TEXT_Y_CANVAS_OFFSET_PIXELS = 20;

    // Font for axis labels and numbers
    private static final Font AXIS_LABEL_FONT = Font.font("Arial", FontWeight.BOLD, 14);
    private static final Font AXIS_NUMBER_FONT = Font.font("Arial", 10);
    private static final Color AXIS_COLOR = Color.rgb(100, 100, 100, 0.75); // Medium-Dark Gray, still semi-transparent
    private static final double AXIS_LINE_WIDTH = 2;
    private static final double TICK_LENGTH_PIXELS = 5.0;
    private static final double LABEL_OFFSET_PIXELS = 15.0; // Offset for "X", "Y" labels from axis end
    private static final double NUMBER_OFFSET_PIXELS = 8.0; // Offset for numbers from ticks

    // --- State and callbacks for Draggable Points ---
    private CurvePoint hoveredPoint = null;
    private int hoveredSegmentIndex = -1;
    private CurvePoint draggedPoint = null;
    private int draggedPointIndex = -1;
    private static final double POINT_GRAB_RADIUS_PIXELS = 10.0;
    private static final double SEGMENT_GRAB_RADIUS_PIXELS = 8.0;

    private BiConsumer<Integer, Point2D> onPointDrag;
    private Consumer<Integer> onPointDragEnd;
    private Consumer<CurvePoint> onPointDelete;
    private BiConsumer<Integer, Point2D> onSegmentClick;

    public FieldDisplay(int canvasWidthPixels, int canvasHeightPixels,
                        double fieldWidthInches,  // This is for the field's HORIZONTAL extent (new Y-axis)
                        double fieldHeightInches, // This is for the field's VERTICAL extent (new X-axis)
                        String fieldImagePath, Robot robot, double backgroundAlpha,
                        double fieldImageAlphaFromApp, Label instructionLabel) {

        this.fieldWidthInches_HorizontalY = fieldWidthInches;
        this.fieldHeightInches_VerticalX = fieldHeightInches;
        this.robot = robot;
        this.instructionLabel = instructionLabel;

        // Field X (vertical on field) scales with canvas height.
        this.scaleForFieldX_Vertical = (double) canvasHeightPixels / this.fieldHeightInches_VerticalX;
        // Field Y (horizontal on field) scales with canvas width.
        this.scaleForFieldY_Horizontal = (double) canvasWidthPixels / this.fieldWidthInches_HorizontalY;

        this.fieldImageAlpha = fieldImageAlphaFromApp;
        this.backgroundAlpha = backgroundAlpha;
        this.fieldImage = ImageLoader.loadImage(
                fieldImagePath,
                canvasWidthPixels,
                canvasHeightPixels,
                "Field Image",
                Color.LIGHTSLATEGRAY
        );

        this.robotTrailDots = Collections.synchronizedList(new ArrayList<>());

        this.canvas = new Canvas(canvasWidthPixels, canvasHeightPixels);
        this.gc = this.canvas.getGraphicsContext2D();
        this.getChildren().add(this.canvas);

        this.coordinateLabel = new Label("");
        this.coordinateLabel.setFont(Font.font("Arial", 12));
        this.coordinateLabel.setTextFill(Color.LIGHTGRAY);
        this.coordinateLabel.setStyle("-fx-background-color: rgba(0, 0, 0, 0.5); -fx-padding: 3px; -fx-border-radius: 3px; -fx-background-radius: 3px;");
        this.coordinateLabel.setVisible(false);
        this.coordinateLabel.setMouseTransparent(true);
        this.getChildren().add(this.coordinateLabel);

        canvas.setFocusTraversable(true);
        setupMouseHandlers();
        setupKeyHandlers();
    }

    // --- Setter for the segment click callback ---
    public void setOnSegmentClick(BiConsumer<Integer, Point2D> handler) {
        this.onSegmentClick = handler;
    }

    // --- Method to set the delete action callback ---
    public void setOnPointDeleteAction(Consumer<CurvePoint> handler) {
        this.onPointDelete = handler;
    }

    // --- Method to set up keyboard listeners ---
    private void setupKeyHandlers() {
        this.canvas.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            // Check if DELETE key is pressed, not in path creation mode, and a point is being hovered
            if ((event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE) && !isPathCreationMode && hoveredPoint != null) {
                if (onPointDelete != null) {
                    onPointDelete.accept(hoveredPoint);
                }
                event.consume(); // Consume the event so it's not processed further
            }
        });
    }

    private void setupMouseHandlers() {
        // --- MOUSE CLICKED ---
        this.canvas.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
            if (!canvas.isFocused()) {
                canvas.requestFocus();
                // System.out.println("FieldDisplay requested focus."); // Optional log
            }

            // Handle clicking on a segment
            if (!isPathCreationMode && hoveredSegmentIndex != -1 && event.getButton() == MouseButton.PRIMARY) {
                if (onSegmentClick != null) {
                    onSegmentClick.accept(hoveredSegmentIndex, new Point2D(event.getX(), event.getY()));
                }
                event.consume();
                return; // Prevent this click from being processed further
            }

            if (isPathCreationMode) {
                if (event.getButton() == MouseButton.PRIMARY) {
                    if (event.getClickCount() == 2) {
                        if (onPathFinishListener != null) onPathFinishListener.run();
                    } else {
                        if (onFieldPointClickListener != null)
                            onFieldPointClickListener.accept(new Point2D(event.getX(), event.getY()));
                    }
                }
            }
        });

        // --- MOUSE PRESSED (To start a drag) ---
        this.canvas.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            if (hoveredPoint != null && !isPathCreationMode && event.isPrimaryButtonDown()) {
                draggedPoint = hoveredPoint;
                draggedPointIndex = currentPathToDraw.indexOf(draggedPoint);
                canvas.setCursor(Cursor.MOVE);
                event.consume();
            }
        });

        // --- MOUSE DRAGGED (To move the point) ---
        this.canvas.addEventHandler(MouseEvent.MOUSE_DRAGGED, event -> {
            if (draggedPoint != null) {
                // Update the coordinate label to follow the mouse during the drag
                updateCoordinateLabel(event);

                Point2D newInchesCoords = pixelToInches(event.getX(), event.getY());
                draggedPoint.x = newInchesCoords.getX();
                draggedPoint.y = newInchesCoords.getY();

                if (onPointDrag != null) {
                    onPointDrag.accept(draggedPointIndex, newInchesCoords);
                }
                drawCurrentState();
                event.consume();
            }
        });

        // --- MOUSE RELEASED (To end a drag) ---
        this.canvas.addEventHandler(MouseEvent.MOUSE_RELEASED, event -> {
            if (draggedPoint != null) {
                if (onPointDragEnd != null) {
                    onPointDragEnd.accept(draggedPointIndex);
                }
                draggedPoint = null;
                draggedPointIndex = -1;
                // Re-evaluate hover state after releasing
                handleMouseMoved(event);
                event.consume();
            }
        });

        // --- MOUSE MOVED ---
        this.canvas.addEventHandler(MouseEvent.MOUSE_MOVED, this::handleMouseMoved);

        // --- MOUSE EXITED ---
        this.canvas.addEventHandler(MouseEvent.MOUSE_EXITED, event -> {
            coordinateLabel.setVisible(false);
            boolean needsRedraw = false;
            if (hoveredPoint != null) {
                hoveredPoint = null;
                needsRedraw = true;
            }
            if (hoveredSegmentIndex != -1) {
                hoveredSegmentIndex = -1;
                needsRedraw = true;
            }

            canvas.setCursor(Cursor.DEFAULT);
            if (instructionLabel != null) {
                instructionLabel.setText("Hover over the field, a point, or a path segment.");
            }
            if (needsRedraw) {
                drawCurrentState();
            }
        });
    }

    private void handleMouseMoved(MouseEvent event) {
        updateCoordinateLabel(event);

        if (isPathCreationMode || draggedPoint != null) {
            return;
        }

        CurvePoint previouslyHoveredPoint = hoveredPoint;
        int previouslyHoveredSegment = hoveredSegmentIndex;

        // Reset hover states before checking again
        hoveredPoint = null;
        hoveredSegmentIndex = -1;

        // First, check for hovering over a point (points have priority)
        hoveredPoint = findNearbyPoint(event.getX(), event.getY());

        if (hoveredPoint != null) {
            // We are hovering a point, so we are not hovering a segment.
            canvas.setCursor(Cursor.MOVE);
            if (instructionLabel != null) {
                int pointIndex = currentPathToDraw.indexOf(hoveredPoint) + 1;
                instructionLabel.setText(String.format("Point %d: Click and drag to move, or press DELETE to remove.", pointIndex));
            }
        } else {
            // Not hovering a point, now check for hovering a segment
            hoveredSegmentIndex = findNearbySegment(event.getX(), event.getY());
            if (hoveredSegmentIndex != -1) {
                canvas.setCursor(Cursor.HAND); // Use hand cursor to indicate "clickable"
                if (instructionLabel != null) {
                    // 1. Get the points of the hovered segment
                    CurvePoint p1 = currentPathToDraw.get(hoveredSegmentIndex);
                    CurvePoint p2 = currentPathToDraw.get(hoveredSegmentIndex + 1);

                    // 2. Calculate the distance between them in inches
                    double segmentLength = Math.hypot(p2.x - p1.x, p2.y - p1.y);

                    // 3. Update the instruction label with the new information
                    instructionLabel.setText(String.format(
                            "Segment %d-%d (L: %.1f in): Click to insert a new point here.",
                            hoveredSegmentIndex + 1,
                            hoveredSegmentIndex + 2,
                            segmentLength
                    ));
                }
            } else {
                // Not hovering a point or a segment
                canvas.setCursor(Cursor.DEFAULT);
                if (instructionLabel != null && (previouslyHoveredPoint != null || previouslyHoveredSegment != -1)) {
                    instructionLabel.setText("Path editing finished. Select a point to modify parameters.");
                }
            }
        }

        // Redraw only if the hover state has changed for either points or segments
        if (previouslyHoveredPoint != hoveredPoint || previouslyHoveredSegment != hoveredSegmentIndex) {
            drawCurrentState();
        }
    }

//    private void handleMouseMoved(MouseEvent event) {
//        updateCoordinateLabel(event);
//
//        // Hover detection logic
//        if (isPathCreationMode || draggedPoint != null) {
//            return; // Don't check for hovers while creating path or dragging
//        }
//
//        CurvePoint previouslyHovered = hoveredPoint;
//        hoveredPoint = findNearbyPoint(event.getX(), event.getY());
//
//        if (hoveredPoint != null) {
//            canvas.setCursor(Cursor.MOVE);
//            if (instructionLabel != null) {
//                int pointIndex = currentPathToDraw.indexOf(hoveredPoint) + 1;
//                instructionLabel.setText(String.format("Point %d: Click and drag to move, or press DELETE to remove.", pointIndex));
//            }
//        } else {
//            canvas.setCursor(Cursor.DEFAULT);
//            if (instructionLabel != null && previouslyHovered != null) { // Only update if the state changed
//                instructionLabel.setText("Path editing finished. Select a point to modify parameters.");
//            }
//        }
//
//        if (previouslyHovered != hoveredPoint) {
//            drawCurrentState(); // Redraw only if hover state changes
//        }
//    }


    // --- Helper method to find a path segment near the mouse ---
    private int findNearbySegment(double mouseX, double mouseY) {
        if (currentPathToDraw == null || currentPathToDraw.size() < 2) {
            return -1; // No segments to check
        }

        for (int i = 0; i < currentPathToDraw.size() - 1; i++) {
            CurvePoint p1 = currentPathToDraw.get(i);
            CurvePoint p2 = currentPathToDraw.get(i + 1);

            Point2D start = new Point2D(fieldYtoCanvasX(p1.y), fieldXtoCanvasY(p1.x));
            Point2D end = new Point2D(fieldYtoCanvasX(p2.y), fieldXtoCanvasY(p2.x));
            Point2D mouse = new Point2D(mouseX, mouseY);

            double dist = distanceToSegment(mouse, start, end);

            if (dist <= SEGMENT_GRAB_RADIUS_PIXELS) {
                return i; // Return the index of the starting point of the segment
            }
        }
        return -1; // No segment found
    }

    // --- Geometric calculation for distance from a point to a line segment ---
    private double distanceToSegment(Point2D p, Point2D v, Point2D w) {
        double dx = w.getX() - v.getX();
        double dy = w.getY() - v.getY();
        double l2 = dx * dx + dy * dy;

        if (l2 == 0.0) return p.distance(v);

        double t = ((p.getX() - v.getX()) * (w.getX() - v.getX()) + (p.getY() - v.getY()) * (w.getY() - v.getY())) / l2;
        t = Math.max(0, Math.min(1, t));
        Point2D projection = new Point2D(v.getX() + t * (w.getX() - v.getX()), v.getY() + t * (w.getY() - v.getY()));
        return p.distance(projection);
    }

    /**
     * Updates the text and position of the coordinate label based on a mouse event.
     * @param event The MouseEvent containing the cursor's current position.
     */
    private void updateCoordinateLabel(MouseEvent event) {
        Point2D fieldCoords = pixelToInches(event.getX(), event.getY());
        coordinateLabel.setText(String.format("X: %.1f, Y: %.1f", fieldCoords.getX(), fieldCoords.getY()));

        double labelOffsetX = 15;
        double labelOffsetY = 15;
        double newX = Math.min(event.getX() + labelOffsetX, canvas.getWidth() - coordinateLabel.prefWidth(-1) - 5);
        newX = Math.max(5, newX);
        double newY = Math.min(event.getY() + labelOffsetY, canvas.getHeight() - coordinateLabel.prefHeight(-1) - 5);
        newY = Math.max(5, newY);

        coordinateLabel.setLayoutX(newX);
        coordinateLabel.setLayoutY(newY);
        coordinateLabel.setVisible(true);
    }

    // --- Setter methods for the callbacks ---
    public void setOnPointDrag(BiConsumer<Integer, Point2D> handler) {
        this.onPointDrag = handler;
    }

    public void setOnPointDragEnd(Consumer<Integer> handler) {
        this.onPointDragEnd = handler;
    }

    // --- Helper method to find a point near the mouse ---
    private CurvePoint findNearbyPoint(double mouseX, double mouseY) {
        if (currentPathToDraw == null) return null;
        for (CurvePoint point : currentPathToDraw) {
            double pointCanvasX = fieldYtoCanvasX(point.y);
            double pointCanvasY = fieldXtoCanvasY(point.x);
            double distance = Math.hypot(mouseX - pointCanvasX, mouseY - pointCanvasY);
            if (distance <= POINT_GRAB_RADIUS_PIXELS) {
                return point;
            }
        }
        return null;
    }

    public void addDebugCircle(double fieldX_inches, double fieldY_inches, double radiusInches, double headingDegrees, Color color) {
        synchronized (debugCircleLock) {
            currentDebugCircle = new DebugCircle(fieldX_inches, fieldY_inches, radiusInches, headingDegrees, color);
        }
    }

    public void setNamedLinesMap(Map<String, UdpPositionListener.LineData> linesMap) {
        synchronized(namedLinesMapLock) {
            this.namedLinesMap = linesMap;
        }
    }

    public void clearDebugCircle() {
        synchronized (debugCircleLock) { currentDebugCircle = null; }
    }

    public void setRobotTextMessage(String text) {
        synchronized (robotTextLock) { this.currentRobotTextMessage = text; }
    }

    public void clearRobotTextMessage() {
        synchronized (robotTextLock) { currentRobotTextMessage = null; }
    }

    /**
     * Converts a field X-coordinate (Vertical on field, +X UP, origin at field center)
     * to a canvas pixel Y-coordinate (Vertical on canvas, +Y DOWN, origin top-left).
     */
    private double fieldXtoCanvasY(double fieldXInches) {
        double canvasCenterY = canvas.getHeight() / 2.0;
        return canvasCenterY - (fieldXInches * this.scaleForFieldX_Vertical);
    }

    /**
     * Converts a field Y-coordinate (Horizontal on field, +Y LEFT, origin at field center)
     * to a canvas pixel X-coordinate (Horizontal on canvas, +X RIGHT, origin top-left).
     */
    private double fieldYtoCanvasX(double fieldYInches) {
        double canvasCenterX = canvas.getWidth() / 2.0;
        return canvasCenterX - (fieldYInches * this.scaleForFieldY_Horizontal);
    }

    /**
     * Converts a canvas pixel Y-coordinate (Vertical on canvas, +Y DOWN, origin top-left)
     * to a field X-coordinate (Vertical on field, +X UP, origin at field center).
     */
    public double canvasYtoFieldX(double canvasPixelY) {
        double canvasCenterY = canvas.getHeight() / 2.0;
        return -(canvasPixelY - canvasCenterY) / this.scaleForFieldX_Vertical;
    }

    /**
     * Converts a canvas pixel X-coordinate (Horizontal on canvas, +X RIGHT, origin top-left)
     * to a field Y-coordinate (Horizontal on field, +Y LEFT, origin at field center).
     */
    public double canvasXtoFieldY(double canvasPixelX) {
        double canvasCenterX = canvas.getWidth() / 2.0;
        return -(canvasPixelX - canvasCenterX) / this.scaleForFieldY_Horizontal;
    }

    /**
     * Converts canvas pixel coordinates (top-left origin) to NEW field coordinates (center origin).
     * @param canvasPixelX Horizontal pixel coordinate on canvas.
     * @param canvasPixelY Vertical pixel coordinate on canvas.
     * @return Point2D containing (FieldX, FieldY) in inches. FieldX is vertical, FieldY is horizontal.
     */
    public Point2D pixelToInches(double canvasPixelX, double canvasPixelY) {
        double fieldY_inches_horizontal = canvasXtoFieldY(canvasPixelX);
        double fieldX_inches_vertical = canvasYtoFieldX(canvasPixelY);
        return new Point2D(fieldX_inches_vertical, fieldY_inches_horizontal); // Return as (FieldX, FieldY)
    }

    private void drawFieldAxes(GraphicsContext gc) {
        gc.save(); // Save current state
        gc.setStroke(AXIS_COLOR);
        gc.setFill(AXIS_COLOR);
        gc.setLineWidth(AXIS_LINE_WIDTH);

        double canvasCenterX = canvas.getWidth() / 2.0;
        double canvasCenterY = canvas.getHeight() / 2.0;

        // --- Draw Field X-axis visual (Vertical line on canvas, representing FieldY = 0) ---
        // This line runs along where Field Y is zero.
        double fieldXAxis_canvasX = fieldYtoCanvasX(0); // Field Y = 0
        gc.strokeLine(fieldXAxis_canvasX, 0, fieldXAxis_canvasX, canvas.getHeight());

        // Label for Field X-axis (near positive end - TOP of screen)
        gc.setFont(AXIS_LABEL_FONT);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.BOTTOM); // Align text bottom to be just above the axis start
        gc.fillText("X", fieldXAxis_canvasX + LABEL_OFFSET_PIXELS, LABEL_OFFSET_PIXELS * 2);


        // --- Draw Field Y-axis visual (Horizontal line on canvas, representing FieldX = 0) ---
        // This line runs along where Field X is zero.
        double fieldYAxis_canvasY = fieldXtoCanvasY(0); // Field X = 0
        gc.strokeLine(0, fieldYAxis_canvasY, canvas.getWidth(), fieldYAxis_canvasY);

        // Label for Field Y-axis (near positive end - LEFT of screen)
        gc.setFont(AXIS_LABEL_FONT);
        gc.setTextAlign(TextAlignment.RIGHT); // Align text right to be just left of axis start
        gc.setTextBaseline(VPos.CENTER);
        gc.fillText("Y", LABEL_OFFSET_PIXELS * 2, fieldYAxis_canvasY - LABEL_OFFSET_PIXELS);

        // --- Draw Ticks and Numbers ---
        gc.setFont(AXIS_NUMBER_FONT);
        int tickIntervalInches = 12; // Draw a tick every 12 inches (1 foot)

        // Ticks and numbers for Field X-axis (along the vertical line where FieldY=0)
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.CENTER);
        for (double fieldX_val = -fieldHeightInches_VerticalX / 2.0; fieldX_val <= fieldHeightInches_VerticalX / 2.0; fieldX_val += tickIntervalInches) {
            if (Math.abs(fieldX_val) < 0.1 && tickIntervalInches > 1) continue; // Skip 0 if ticks are dense
            double tickCanvasY = fieldXtoCanvasY(fieldX_val);
            // Draw tick mark
            gc.strokeLine(
                    fieldXAxis_canvasX - TICK_LENGTH_PIXELS, tickCanvasY,
                    fieldXAxis_canvasX + TICK_LENGTH_PIXELS, tickCanvasY
            );
            // Draw number (offset to the right of the Field X-axis line)
            gc.fillText(String.format("%.0f", fieldX_val),
                    fieldXAxis_canvasX + TICK_LENGTH_PIXELS + NUMBER_OFFSET_PIXELS,
                    tickCanvasY);
        }

        // Ticks and numbers for Field Y-axis (along the horizontal line where FieldX=0)
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.TOP); // Numbers below the ticks
        for (double fieldY_val = -fieldWidthInches_HorizontalY / 2.0; fieldY_val <= fieldWidthInches_HorizontalY / 2.0; fieldY_val += tickIntervalInches) {
            if (Math.abs(fieldY_val) < 0.1 && tickIntervalInches > 1) continue; // Skip 0
            double tickCanvasX = fieldYtoCanvasX(fieldY_val);
            // Draw tick mark
            gc.strokeLine(
                    tickCanvasX, fieldYAxis_canvasY - TICK_LENGTH_PIXELS,
                    tickCanvasX, fieldYAxis_canvasY + TICK_LENGTH_PIXELS
            );
            // Draw number (offset below the Field Y-axis line)
            gc.fillText(String.format("%.0f", fieldY_val),
                    tickCanvasX,
                    fieldYAxis_canvasY + TICK_LENGTH_PIXELS + NUMBER_OFFSET_PIXELS);
        }
        gc.restore(); // Restore previous state
    }

    public void setPathsToDraw(List<PathData> paths, PathData activePath) {
        this.allPathsToDraw = (paths != null) ? paths : new ArrayList<>();
        this.activePath = activePath;
        this.currentPathToDraw = (activePath != null) ? activePath.points : new ArrayList<>();
    }

    public void setPathToDraw(List<CurvePoint> path) { // Path points are (FieldX, FieldY)
        this.currentPathToDraw = (path != null) ? (path instanceof ArrayList ? (List<CurvePoint>)path : new ArrayList<>(path)) : new ArrayList<>();
    }

//    public void setPathToDraw(List<Position> path) { // Path points are (FieldX, FieldY)
//        this.currentPathToDraw = (path != null) ? new ArrayList<>(path) : new ArrayList<>();
//    }

    public void setHighlightedPoint(CurvePoint point) {
        this.highlightedPoint = point;
        // Don't necessarily call drawCurrentState() here immediately.
        // FtcFieldSimulatorApp can call it after setting the point.
        // Or, if you want FieldDisplay to be more self-contained for this:
        // drawCurrentState();
    }

    public void setPathCreationMode(boolean isActive,
                                    Consumer<Point2D> pointClickListener, // Expects canvas pixel coords
                                    Runnable pathFinishListener) {
        this.isPathCreationMode = isActive;
        this.onFieldPointClickListener = pointClickListener;
        this.onPathFinishListener = pathFinishListener;
        canvas.setCursor(isActive ? Cursor.CROSSHAIR : Cursor.DEFAULT);
        if (isActive) {
            canvas.requestFocus();
        } else {
            coordinateLabel.setVisible(false);
        }
    }

    // robotFieldX is the new vertical field coord, robotFieldY is the new horizontal field coord
    public void addTrailDot(double robotFieldX, double robotFieldY) {
        if (robotTrailDots.size() >= MAX_TRAIL_DOTS) {
            if (!robotTrailDots.isEmpty()) robotTrailDots.remove(0);
        }
        // Convert to canvas pixel coordinates for storage
        robotTrailDots.add(new Point2D(fieldYtoCanvasX(robotFieldY), fieldXtoCanvasY(robotFieldX)));
    }

    public void clearTrail() { robotTrailDots.clear(); }


// In FieldDisplay.java, replace the entire drawCurrentState() method with this one.

    public void drawCurrentState() {
        // 1. Clear and Draw the basic field background and image
        gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        gc.setFill(Color.rgb(50, 50, 50, this.backgroundAlpha));
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        if (fieldImage != null) {
            gc.setGlobalAlpha(this.fieldImageAlpha);
            gc.drawImage(fieldImage, 0, 0, canvas.getWidth(), canvas.getHeight());
            gc.setGlobalAlpha(1.0); // Reset alpha
        }

        // 2. Draw the axes on top of the field image
        drawFieldAxes(gc);

        // --- NEW DRAWING ORDER ---
        // 3. Draw the robot image FIRST
        if (robot != null) {
            Image robotImg = robot.getRobotImage();
            double robotFieldX_vertical = robot.getXInches();
            double robotFieldY_horizontal = robot.getYInches();

            double robotCanvasPixelX = fieldYtoCanvasX(robotFieldY_horizontal);
            double robotCanvasPixelY = fieldXtoCanvasY(robotFieldX_vertical);

            double robotDisplayWidthOnCanvas = Robot.ROBOT_WIDTH_INCHES * scaleForFieldY_Horizontal;
            double robotDisplayHeightOnCanvas = Robot.ROBOT_HEIGHT_INCHES * scaleForFieldX_Vertical;

            double robotHeadingDegrees_CCW = robot.getHeadingDegrees();

            gc.save();
            Rotate rotateTransform = new Rotate(-robotHeadingDegrees_CCW, robotCanvasPixelX, robotCanvasPixelY);
            gc.setTransform(
                    rotateTransform.getMxx(), rotateTransform.getMyx(),
                    rotateTransform.getMxy(), rotateTransform.getMyy(),
                    rotateTransform.getTx(), rotateTransform.getTy()
            );
            gc.drawImage(
                    robotImg,
                    robotCanvasPixelX - robotDisplayWidthOnCanvas / 2.0,
                    robotCanvasPixelY - robotDisplayHeightOnCanvas / 2.0,
                    robotDisplayWidthOnCanvas,
                    robotDisplayHeightOnCanvas
            );
            gc.restore();
        }

        // 4. Draw the robot trail on top of the robot
        gc.setFill(Color.YELLOW);
        synchronized (robotTrailDots) {
            for (Point2D dot : robotTrailDots) {
                gc.fillOval(dot.getX() - TRAIL_DOT_RADIUS_PIXELS / 2, dot.getY() - TRAIL_DOT_RADIUS_PIXELS / 2, TRAIL_DOT_RADIUS_PIXELS, TRAIL_DOT_RADIUS_PIXELS);
            }
        }

        // 5. Draw all paths
        if (allPathsToDraw != null) {
            for (PathData pathData : allPathsToDraw) {
                boolean isActive = (pathData == activePath);
                List<CurvePoint> points = pathData.points;
                if (points == null || points.isEmpty()) continue;

                if (isActive) {
                    gc.setStroke(isPathCreationMode ? Color.CYAN : Color.MAGENTA);
                    gc.setLineWidth(3);
                } else {
                    gc.setStroke(Color.GRAY.deriveColor(0, 1, 1, 0.4));
                    gc.setLineWidth(3);
                }

                if (points.size() > 1) {
                    for (int i = 0; i < points.size() - 1; i++) {
                        if (isActive) {
                            // Highlight the hovered segment
                            if (i == hoveredSegmentIndex) {
                                gc.setStroke(Color.ORANGE);
                                gc.setLineWidth(5);
                            } else {
                                gc.setStroke(isPathCreationMode ? Color.CYAN : Color.MAGENTA);
                                gc.setLineWidth(3);
                            }
                        } else {
                            // Inactive paths drawn as gray lines
                            gc.setStroke(Color.GRAY.deriveColor(0, 1, 1, 0.5));
                            gc.setLineWidth(3);
                        }

                        CurvePoint p1 = points.get(i);
                        CurvePoint p2 = points.get(i + 1);
                        gc.strokeLine(fieldYtoCanvasX(p1.y), fieldXtoCanvasY(p1.x),
                                fieldYtoCanvasX(p2.y), fieldXtoCanvasY(p2.x));
                    }
                }

                // Draw waypoints for the active path
                if (isActive) {
                    gc.setStroke(Color.BLACK);
                    gc.setLineWidth(1);
                    int totalPoints = points.size();
                    for (int i = 0; i < totalPoints; i++) {
                        CurvePoint p = points.get(i);
                        double canvasX = fieldYtoCanvasX(p.y);
                        double canvasY = fieldXtoCanvasY(p.x);
                        double pointSize = 8.0;

                        // Requirement 1: Solid Color ORANGE
                        Color pointColor = Color.ORANGE;

                        if (p == draggedPoint) {
                            pointSize = 12.0;
                            pointColor = Color.DODGERBLUE;
                        } else if (p == hoveredPoint) {
                            pointSize = 12.0;
                            pointColor = Color.GOLD;
                        } else if (p == highlightedPoint) {
                            pointSize = 12.0;
                            pointColor = Color.YELLOW;
                        }

                        gc.setFill(pointColor);
                        gc.fillOval(canvasX - pointSize / 2, canvasY - pointSize / 2, pointSize, pointSize);

                        // Requirement 2 & 4: Alphabetical labels (A, B... Z, A1, B1...)
                        int quotient = i / 26;
                        int remainder = i % 26;
                        String label = "" + (char) ('A' + remainder) + (quotient > 0 ? quotient : "");

                        // Requirement 3: Position labels using normal vector to the path
                        double dx, dy;
                        if (totalPoints == 1) {
                            dx = 1; dy = 0;
                        } else if (i == 0) {
                            dx = fieldYtoCanvasX(points.get(1).y) - canvasX;
                            dy = fieldXtoCanvasY(points.get(1).x) - canvasY;
                        } else if (i == totalPoints - 1) {
                            dx = canvasX - fieldYtoCanvasX(points.get(i - 1).y);
                            dy = canvasY - fieldXtoCanvasY(points.get(i - 1).x);
                        } else {
                            dx = fieldYtoCanvasX(points.get(i + 1).y) - fieldYtoCanvasX(points.get(i - 1).y);
                            dy = fieldXtoCanvasY(points.get(i + 1).x) - fieldXtoCanvasY(points.get(i - 1).x);
                        }

                        double len = Math.sqrt(dx * dx + dy * dy);
                        double nx, ny;
                        if (len < 1e-6) {
                            nx = 0; ny = -1;
                        } else {
                            nx = -dy / len;
                            ny = dx / len;
                        }

                        double offset = 18.0; // Offset distance from point center
                        double labelX = canvasX + nx * offset;
                        double labelY = canvasY + ny * offset;

                        // Requirement 5: Font bold Arial 12, White color
                        gc.save();
                        gc.setFont(Font.font("Arial", FontWeight.BOLD, 12));
                        gc.setFill(Color.WHITE);
                        gc.setTextAlign(TextAlignment.CENTER);
                        gc.setTextBaseline(VPos.CENTER);
                        gc.fillText(label, labelX, labelY);
                        gc.restore();
                    }
                } else {
                    // Waypoints for inactive paths
                    gc.setFill(Color.GRAY.deriveColor(0, 1, 1, 0.6));
                    double pointSize = 8.0;
                    for (CurvePoint p : points) {
                        double canvasX = fieldYtoCanvasX(p.y);
                        double canvasY = fieldXtoCanvasY(p.x);
                        gc.fillOval(canvasX - pointSize / 2, canvasY - pointSize / 2, pointSize, pointSize);
                    }
                }
            }
        }

        // 6. Draw all other debug info (lines, circles, text) on top of everything else
        final Color styleThickColor = Color.CYAN.deriveColor(0, 1, 1, 0.9);
        final Color styleThinColor = Color.LIMEGREEN.deriveColor(0, 1, 1, 0.8);
        final Color styleDottedColor = Color.LIGHTPINK.deriveColor(0, 1, 1, 0.85);

        List<UdpPositionListener.LineData> linesToRender;
        synchronized (namedLinesMapLock) {
            linesToRender = new ArrayList<>(namedLinesMap.values());
        }

        if (linesToRender != null) {
            for (UdpPositionListener.LineData line : linesToRender) {
                if (line == null) continue;
                LineStyle style = LineStyle.fromCode(line.styleCode);
                double canvasX1 = fieldYtoCanvasX(line.y1);
                double canvasY1 = fieldXtoCanvasY(line.x1);
                double canvasX2 = fieldYtoCanvasX(line.y2);
                double canvasY2 = fieldXtoCanvasY(line.x2);

                switch (style) {
                    case SOLID_THICK:
                        gc.setStroke(styleThickColor);
                        gc.setLineWidth(3.0);
                        gc.setLineDashes(0);
                        gc.strokeLine(canvasX1, canvasY1, canvasX2, canvasY2);
                        break;
                    case SOLID_THIN:
                        gc.setStroke(styleThinColor);
                        gc.setLineWidth(3.5);
                        gc.setLineDashes(0);
                        gc.strokeLine(canvasX1, canvasY1, canvasX2, canvasY2);
                        break;
                    case DOTTED:
                        gc.setStroke(styleDottedColor);
                        gc.setLineWidth(1.5);
                        gc.setLineDashes(5, 5);
                        gc.strokeLine(canvasX1, canvasY1, canvasX2, canvasY2);
                        gc.setLineDashes(0);
                        break;
                }
            }
        }

        synchronized (robotTextLock) {
            if (currentRobotTextMessage != null && !currentRobotTextMessage.isEmpty() && robot != null) {
                gc.save();
                double robotCanvasX = fieldYtoCanvasX(robot.getYInches());
                double robotCanvasY = fieldXtoCanvasY(robot.getXInches());
                gc.setFont(ROBOT_TEXT_FONT);
                gc.setTextAlign(TextAlignment.CENTER);
                gc.setTextBaseline(VPos.TOP);
                double textWidth = new javafx.scene.text.Text(currentRobotTextMessage).getLayoutBounds().getWidth();
                double textHeight = new javafx.scene.text.Text(currentRobotTextMessage).getLayoutBounds().getHeight();
                gc.setFill(ROBOT_TEXT_BACKGROUND_COLOR);
                gc.fillRoundRect(
                        robotCanvasX - (textWidth / 2) - ROBOT_TEXT_PADDING,
                        robotCanvasY + ROBOT_TEXT_Y_CANVAS_OFFSET_PIXELS - ROBOT_TEXT_PADDING,
                        textWidth + (ROBOT_TEXT_PADDING * 2),
                        textHeight + (ROBOT_TEXT_PADDING * 2),
                        5, 5
                );
                gc.setFill(ROBOT_TEXT_COLOR);
                gc.fillText(currentRobotTextMessage, robotCanvasX, robotCanvasY + ROBOT_TEXT_Y_CANVAS_OFFSET_PIXELS);
                gc.restore();
            }
        }

        synchronized (debugCircleLock) {
            if (currentDebugCircle != null) {
                gc.save();
                double canvasX = fieldYtoCanvasX(currentDebugCircle.fieldY);
                double canvasY = fieldXtoCanvasY(currentDebugCircle.fieldX);
                double radiusPixels = currentDebugCircle.radiusInches * scaleForFieldY_Horizontal;
                Color cColor = currentDebugCircle.color != null ? currentDebugCircle.color : Color.ORANGE;
                gc.setStroke(cColor);
                gc.setLineWidth(2.0);
                gc.strokeOval(canvasX - radiusPixels, canvasY - radiusPixels, radiusPixels * 2, radiusPixels * 2);
                double headingRad_Canvas = Math.toRadians(90 + currentDebugCircle.headingDegrees);
                gc.strokeLine(
                        canvasX,
                        canvasY,
                        canvasX + radiusPixels * Math.cos(headingRad_Canvas),
                        canvasY - radiusPixels * Math.sin(headingRad_Canvas)
                );
                gc.restore();
            }
        }
    }


//    public void drawCurrentState() {
//        gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
//        gc.setFill(Color.rgb(50, 50, 50, this.backgroundAlpha));
//        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
//
//        // Draw the field image
//        if (fieldImage != null) {
//            gc.setGlobalAlpha(this.fieldImageAlpha);
//            gc.drawImage(fieldImage, 0, 0, canvas.getWidth(), canvas.getHeight());
//            gc.setGlobalAlpha(1.0); // Reset alpha
//        }
//
//        // Draw axes
//        drawFieldAxes(gc);
//
//        // --- path drawing logic ---
//        if (currentPathToDraw != null && !currentPathToDraw.isEmpty()) {
//            gc.setStroke(isPathCreationMode ? Color.CYAN : Color.MAGENTA);
//            gc.setLineWidth(2);
//            if (currentPathToDraw.size() > 1) {
//                for (int i = 0; i < currentPathToDraw.size() - 1; i++) {
//                    CurvePoint p1 = currentPathToDraw.get(i);
//                    CurvePoint p2 = currentPathToDraw.get(i + 1);
//                    gc.strokeLine(fieldYtoCanvasX(p1.y), fieldXtoCanvasY(p1.x),
//                            fieldYtoCanvasX(p2.y), fieldXtoCanvasY(p2.x));
//                }
//            }
//
//            // Draw waypoints with hover/drag effects
//            for (CurvePoint p : currentPathToDraw) {
//                double canvasX = fieldYtoCanvasX(p.y);
//                double canvasY = fieldXtoCanvasY(p.x);
//                double pointSize = 8.0;
//                Color pointColor = Color.RED;
//
//                if (p == draggedPoint) {
//                    pointSize = 12.0;
//                    pointColor = Color.DODGERBLUE;
//                } else if (p == hoveredPoint) {
//                    pointSize = 12.0;
//                    pointColor = Color.GOLD;
//                } else if (p == highlightedPoint) {
//                    pointSize = 12.0;
//                    pointColor = Color.YELLOW;
//                }
//
//                gc.setFill(pointColor);
//                gc.fillOval(canvasX - pointSize / 2, canvasY - pointSize / 2, pointSize, pointSize);
//            }
//        }
//
////        // Draw the path being created/displayed
////        if (currentPathToDraw != null && !currentPathToDraw.isEmpty()) {
////            gc.setStroke(isPathCreationMode ? Color.CYAN : Color.MAGENTA);
////            gc.setLineWidth(2);
////            // Draw lines between points
////            if (currentPathToDraw.size() > 1) { // Need at least two points to draw a line
////                for (int i = 0; i < currentPathToDraw.size() - 1; i++) {
////                    CurvePoint p1 = currentPathToDraw.get(i); // Type is CurvePoint
////                    CurvePoint p2 = currentPathToDraw.get(i + 1); // Type is CurvePoint
////                    gc.strokeLine(fieldYtoCanvasX(p1.y), fieldXtoCanvasY(p1.x),
////                            fieldYtoCanvasX(p2.y), fieldXtoCanvasY(p2.x));
////                }
////            }
////            // Draw circles at each point
////            gc.setFill(Color.RED);
////            for (CurvePoint p : currentPathToDraw) { // Iterate CurvePoint
////                double canvasX = fieldYtoCanvasX(p.y);
////                double canvasY = fieldXtoCanvasY(p.x);
////                gc.fillOval(canvasX - 4, canvasY - 4, 8, 8); // Draw a small circle for each point
////            }
////        }
//
////        // Draw the highlighted point (if any)
////        if (highlightedPoint != null) {
////            gc.setFill(Color.YELLOW); // Or another distinct highlight color
////            gc.setStroke(Color.BLACK); // Optional: add a border to the highlight
////            gc.setLineWidth(1.5);      // Optional: border width
////
////            double canvasX = fieldYtoCanvasX(highlightedPoint.y);
////            double canvasY = fieldXtoCanvasY(highlightedPoint.x);
////            double highlightRadius = 6; // Make it slightly larger than normal points
////
////            gc.fillOval(canvasX - highlightRadius, canvasY - highlightRadius, highlightRadius * 2, highlightRadius * 2);
////            // gc.strokeOval(canvasX - highlightRadius, canvasY - highlightRadius, highlightRadius * 2, highlightRadius * 2); // Optional border
////        }
//
//        // Draw Robot Image
//        double robotCanvasPixelX = 0; // robot's center X on canvas (horizontal)
//        double robotCanvasPixelY = 0; // robot's center Y on canvas (vertical)
//
//        if (robot != null) {
//            Image robotImg = robot.getRobotImage();
//            double robotFieldX_vertical = robot.getXInches();    // Robot's Field X (vertical)
//            double robotFieldY_horizontal = robot.getYInches();  // Robot's Field Y (horizontal)
//
//            robotCanvasPixelX = fieldYtoCanvasX(robotFieldY_horizontal);
//            robotCanvasPixelY = fieldXtoCanvasY(robotFieldX_vertical);
//
//            // Robot.ROBOT_WIDTH_INCHES: robot's extent along its own local Y-axis (which aligns with Field Y when heading=0)
//            // Robot.ROBOT_HEIGHT_INCHES: robot's extent along its own local X-axis (which aligns with Field X when heading=0)
//            // These become the display width/height on canvas *before* rotation.
//            double robotDisplayWidthOnCanvas = Robot.ROBOT_WIDTH_INCHES * scaleForFieldY_Horizontal;
//            double robotDisplayHeightOnCanvas = Robot.ROBOT_HEIGHT_INCHES * scaleForFieldX_Vertical;
//
////            double robotHeadingDegrees = robot.getHeadingDegrees(); // 0 degrees = points +FieldX (down screen)
//            double robotHeadingDegrees_CCW = robot.getHeadingDegrees(); // This is your CCW angle (0 deg = +FieldX/Down)
//
//            gc.save();
//            // JavaFX Rotate: positive angle is Clockwise. Pivot is robot's canvas center.
//            Rotate rotateTransform = new Rotate(-robotHeadingDegrees_CCW, robotCanvasPixelX, robotCanvasPixelY);
//            gc.setTransform(
//                    rotateTransform.getMxx(), rotateTransform.getMyx(),
//                    rotateTransform.getMxy(), rotateTransform.getMyy(),
//                    rotateTransform.getTx(), rotateTransform.getTy()
//            );
//            gc.drawImage(
//                    robotImg,
//                    robotCanvasPixelX - robotDisplayWidthOnCanvas / 2.0,
//                    robotCanvasPixelY - robotDisplayHeightOnCanvas / 2.0,
//                    robotDisplayWidthOnCanvas,
//                    robotDisplayHeightOnCanvas
//            );
//            gc.restore();
//        }
//
//        // Draw Named, Styled Lines
//        final Color styleThickColor = Color.CYAN.deriveColor(0, 1, 1, 0.9);
//        final Color styleThinColor = Color.LIMEGREEN.deriveColor(0, 1, 1, 0.8);
//        final Color styleDottedColor = Color.LIGHTPINK.deriveColor(0, 1, 1, 0.85);
//
//        // To prevent concurrent modification errors, copy the values to a temporary list before drawing.
//        List<UdpPositionListener.LineData> linesToRender;
//        synchronized (namedLinesMapLock) {
//            linesToRender = new ArrayList<>(namedLinesMap.values());
//        }
//
//        if (linesToRender != null) {
//            for (UdpPositionListener.LineData line : linesToRender) {
//                if (line == null) continue;
//
//                // Convert the integer style code from the data packet into your LineStyle enum
//                LineStyle style = LineStyle.fromCode(line.styleCode);
//
//                // Use the correct field names: x1, y1, x2, y2
//                double canvasX1 = fieldYtoCanvasX(line.y1);
//                double canvasY1 = fieldXtoCanvasY(line.x1);
//                double canvasX2 = fieldYtoCanvasX(line.y2);
//                double canvasY2 = fieldXtoCanvasY(line.x2);
//
//                // Apply styles based on the enum
//                switch (style) {
//                    case SOLID_THICK:
//                        gc.setStroke(styleThickColor);
//                        gc.setLineWidth(3.0);
//                        gc.setLineDashes(0);
//                        gc.strokeLine(canvasX1, canvasY1, canvasX2, canvasY2);
//                        break;
//                    case SOLID_THIN:
//                        gc.setStroke(styleThinColor);
//                        gc.setLineWidth(3.5);
//                        gc.setLineDashes(0);
//                        gc.strokeLine(canvasX1, canvasY1, canvasX2, canvasY2);
//                        break;
//                    case DOTTED:
//                        gc.setStroke(styleDottedColor);
//                        gc.setLineWidth(1.5);
//                        gc.setLineDashes(5, 5);
//                        gc.strokeLine(canvasX1, canvasY1, canvasX2, canvasY2);
//                        gc.setLineDashes(0); // Reset dashes for other drawing
//                        break;
//                }
//            }
//        }
//
//        // Draw robot trail
//        gc.setFill(Color.YELLOW);
//        synchronized (robotTrailDots) {
//            for (Point2D dot : robotTrailDots) {
//                gc.fillOval(dot.getX() - TRAIL_DOT_RADIUS_PIXELS / 2, dot.getY() - TRAIL_DOT_RADIUS_PIXELS / 2, TRAIL_DOT_RADIUS_PIXELS, TRAIL_DOT_RADIUS_PIXELS);
//            }
//        }
//
//        // Draw robot text message
//        synchronized (robotTextLock) {
//            if (currentRobotTextMessage != null && !currentRobotTextMessage.isEmpty() && robot != null) {
//                gc.save();
//                double robotCanvasX = fieldYtoCanvasX(robot.getYInches());
//                double robotCanvasY = fieldXtoCanvasY(robot.getXInches());
//                gc.setFont(ROBOT_TEXT_FONT);
//                gc.setTextAlign(TextAlignment.CENTER);
//                gc.setTextBaseline(VPos.TOP);
//                double textWidth = new javafx.scene.text.Text(currentRobotTextMessage).getLayoutBounds().getWidth();
//                double textHeight = new javafx.scene.text.Text(currentRobotTextMessage).getLayoutBounds().getHeight();
//                gc.setFill(ROBOT_TEXT_BACKGROUND_COLOR);
//                gc.fillRoundRect(
//                        robotCanvasX - (textWidth / 2) - ROBOT_TEXT_PADDING,
//                        robotCanvasY + ROBOT_TEXT_Y_CANVAS_OFFSET_PIXELS - ROBOT_TEXT_PADDING,
//                        textWidth + (ROBOT_TEXT_PADDING * 2),
//                        textHeight + (ROBOT_TEXT_PADDING * 2),
//                        5, 5
//                );
//                gc.setFill(ROBOT_TEXT_COLOR);
//                gc.fillText(currentRobotTextMessage, robotCanvasX, robotCanvasY + ROBOT_TEXT_Y_CANVAS_OFFSET_PIXELS);
//                gc.restore();
//            }
//        }
//
//        // --- Draw Debug Circle as an Outline with a Heading Line ---
//        synchronized (debugCircleLock) {
//            if (currentDebugCircle != null) {
//                gc.save();
//                double canvasX = fieldYtoCanvasX(currentDebugCircle.fieldY);
//                double canvasY = fieldXtoCanvasY(currentDebugCircle.fieldX);
//
//                double radiusInches = currentDebugCircle.radiusInches;
//                double radiusPixels = radiusInches * scaleForFieldY_Horizontal;
//
//                // 1. Set the STROKE color and line width for the circle
//                Color cColor = currentDebugCircle.color != null ? currentDebugCircle.color : Color.ORANGE;
//                gc.setStroke(cColor);
//                gc.setLineWidth(2.0); // A visible line width
//
//                // 2. Use strokeOval to draw the outline (circumference)
//                gc.strokeOval(canvasX - radiusPixels, canvasY - radiusPixels, radiusPixels * 2, radiusPixels * 2);
//
//                // 3. Draw the heading line from the center to the edge
//                // It will automatically use the same stroke color and width set above
////                double headingRad_Canvas = Math.toRadians(90 - currentDebugCircle.headingDegrees);
//                double headingRad_Canvas = Math.toRadians(90 + currentDebugCircle.headingDegrees);
//
//                gc.strokeLine(
//                        canvasX, // from center X
//                        canvasY, // from center Y
//                        canvasX + radiusPixels * Math.cos(headingRad_Canvas), // to edge X
//                        canvasY - radiusPixels * Math.sin(headingRad_Canvas)  // to edge Y
//                );
//
//                gc.restore();
//            }
//        }
//    }
}
