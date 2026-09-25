package com.example.ftcfieldsimulator;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox; // NEW IMPORT
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.scene.control.Alert;
import javafx.scene.control.TextArea;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.text.Font;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ComboBox;
import javafx.scene.control.CheckBox;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;

import com.example.ftcfieldsimulator.UdpPositionListener.CircleData;
import com.example.ftcfieldsimulator.UdpPositionListener.KeyValueData;
import com.example.ftcfieldsimulator.UdpPositionListener.LineData;
import com.example.ftcfieldsimulator.UdpPositionListener.PositionData;
import com.example.ftcfieldsimulator.UdpPositionListener.TextData;
import com.example.ftcfieldsimulator.UdpPositionListener.UdpMessageData;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FtcFieldSimulatorApp extends Application {

    // --- UI and State Components ---
    private FieldDisplay fieldDisplay;
    private ControlPanel controlPanel;
    private FieldKeyValueTable keyValueTable;
    private FieldStatusDisplay fieldStatusDisplay;
    private Robot robot;
    private RecordingManager recordingManager;
    private UdpPositionListener udpListener;
    private Thread udpListenerThread;
    private Label instructionLabel;
    private Stage primaryStage;
    private PlotDisplayWindow plotDisplayWindow;
    private UdpPlotListener udpPlotListener;
    private Thread udpPlotListenerThread;
    private List<PathData> allPaths = new ArrayList<>();
    private List<AutonomousStep> missionSteps = new ArrayList<>(); // Sequential mission steps
    private PathData selectedPath = null;
    private boolean isCreatingPath = false;
    private boolean isUpdatingFromScript = false; // Flag to prevent recursion
    private boolean isNavigating = false; // Flag to prevent redundant snaps during point navigation
    private Map<String, LineData> namedLinesToDraw = new HashMap<>();
    private final Object namedLinesLock = new Object();
    private Map<TextField, String> textFieldPreviousValues = new HashMap<>();
    private boolean isRedAlliance = false; // State to track current alliance view
    private Stage missionEditorStage = null;
    private TextArea popupScriptArea = null;

    // --- Configuration Constants ---
    public static final double FIELD_WIDTH_INCHES = 144.0;
    public static final double FIELD_HEIGHT_INCHES = 144.0;
    private static final int FIELD_DISPLAY_WIDTH_PIXELS = 800;
    private static final int FIELD_DISPLAY_HEIGHT_PIXELS = 800;
    private static final String FIELD_IMAGE_PATH = "/biobuzz_field.png";
    private static final String ROBOT_IMAGE_PATH = "/robot.png";
    public static final double FIELD_IMAGE_ALPHA = 0.3;
    public static final double BACKGROUND_ALPHA = 0.1;
    public static final double ROBOT_START_FIELD_X = 0.0;
    public static final double ROBOT_START_FIELD_Y = 0.0;
    public static final double ROBOT_START_HEADING_DEGREES = 0.0;
    private static final int UDP_LISTENER_PORT = 7777;
    private static final int ROBOT_LISTENER_PORT = 6666;
    private static final double ROBOT_MOVE_INCREMENT_INCHES = 2.0;
    private static final double ROBOT_TURN_INCREMENT_DEGREES = 5.0;
    private static final double MASTER_DEFAULT_MOVE_SPEED = 0.4;
    private static final double MASTER_DEFAULT_TURN_SPEED = 0.4;
    private static final double MASTER_DEFAULT_FOLLOW_DISTANCE = 10.0;
    private static final double MASTER_DEFAULT_SLOW_DOWN_TURN_RADIANS = Math.toRadians(60);
    private static final double MASTER_DEFAULT_SLOW_DOWN_TURN_AMOUNT = 0.6;


    public static void main(String[] args) {
        Application.launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        primaryStage.setTitle("FTC Field Simulator");

        double controlPanelWidth = FIELD_DISPLAY_WIDTH_PIXELS * ControlPanel.PREFERRED_WIDTH_RATIO_TO_FIELD;
        double rightPanelWidth = controlPanelWidth * 0.9; // Make right panel slightly smaller
        double totalAppWidth = FIELD_DISPLAY_WIDTH_PIXELS + controlPanelWidth + rightPanelWidth;
        double totalAppHeight = FIELD_DISPLAY_HEIGHT_PIXELS;

        // --- Init core components ---
        this.recordingManager = new RecordingManager(this::processUdpDataAndUpdateUI, this::onPlaybackFinished);
        recordingManager.setOnProgressUpdate(index -> {
            if (controlPanel != null && controlPanel.getTimelineSlider() != null && !controlPanel.getTimelineSlider().isValueChanging()) {
                controlPanel.getTimelineSlider().setValue(index);
            }
            updateTimeLapsedDisplay();
        });

        instructionLabel = new Label("Create a new path or select a point to edit its parameters.");
        instructionLabel.setPadding(new Insets(5));
        instructionLabel.setMaxWidth(Double.MAX_VALUE);
        instructionLabel.setAlignment(Pos.CENTER);
        HBox instructionPane = new HBox(instructionLabel);
        instructionPane.setAlignment(Pos.CENTER);
        instructionPane.setStyle("-fx-background-color: #CFD8DC;");

        this.robot = new Robot(ROBOT_START_FIELD_X, ROBOT_START_FIELD_Y, ROBOT_START_HEADING_DEGREES, ROBOT_IMAGE_PATH);
        fieldDisplay = new FieldDisplay(FIELD_DISPLAY_WIDTH_PIXELS, FIELD_DISPLAY_HEIGHT_PIXELS, FIELD_WIDTH_INCHES, FIELD_HEIGHT_INCHES, FIELD_IMAGE_PATH, robot, BACKGROUND_ALPHA, FIELD_IMAGE_ALPHA, instructionLabel);
        fieldDisplay.setNamedLinesMap(namedLinesToDraw);
        fieldDisplay.setRobotTextMessage(null);

        // --- Init UI Panels ---
        controlPanel = new ControlPanel(controlPanelWidth);
        keyValueTable = new FieldKeyValueTable(rightPanelWidth);
        fieldStatusDisplay = new FieldStatusDisplay();

        // --- Assemble the right-side panel ---
        VBox rightPanel = new VBox();
        VBox.setVgrow(keyValueTable, Priority.ALWAYS);
        rightPanel.getChildren().addAll(keyValueTable, fieldStatusDisplay);

        // --- Assemble the main layout ---
        BorderPane mainLayout = new BorderPane();
        mainLayout.setLeft(controlPanel);
        mainLayout.setCenter(fieldDisplay);
        mainLayout.setRight(rightPanel);
        mainLayout.setBottom(instructionPane);

        double instructionPaneHeight = 60;
        Scene scene = new Scene(mainLayout, totalAppWidth, totalAppHeight + instructionPaneHeight);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::handleSceneKeyPress);

        // --- Wire up everything ---
        setupControlPanelActions(primaryStage);
        setupRecordingControlActions();
        setupParameterFieldListeners();
        setupFieldDisplayMouseHandlers();
        setupFieldDisplayKeyHandlers();
        setupMissionScriptListeners();

        startUdpPositionListener();
        startUdpPlotListener();
        primaryStage.setScene(scene);
        primaryStage.setResizable(true);
        primaryStage.show();
        primaryStage.setOnCloseRequest(event -> stopApp());

        updateUIFromRobotState();
        updateControlPanelForPathState();
        updateMissionScriptFromState();
        updateTimeLapsedDisplay();
    }

    private void setupFieldDisplayKeyHandlers() {
        if (fieldDisplay == null) return;
        fieldDisplay.setOnPointDeleteAction(p -> {
            handleDeletePoint(p);
            updateMissionScriptFromState();
        });
    }

    private void setupMissionScriptListeners() {
        controlPanel.getMissionScriptArea().textProperty().addListener((obs, oldVal, newVal) -> {
            if (!isUpdatingFromScript && !isCreatingPath) {
                updateStateFromMissionScript(newVal);
            }
            if (popupScriptArea != null && !popupScriptArea.isFocused()) {
                popupScriptArea.setText(newVal);
            }
        });
    }

    private void updateMissionScriptFromState() {
        if (isUpdatingFromScript) return;
        isUpdatingFromScript = true;

        // 1. Sync Initial Pose command
        syncInitialPoseStep();

        // 2. Sync Paths from allPaths into missionSteps
        syncPathsIntoSteps();

        // 2. Reconstruct script from missionSteps
        StringBuilder sb = new StringBuilder();
        for (AutonomousStep step : missionSteps) {
            sb.append(step.rawLine).append("\n");
        }
        
        controlPanel.setMissionScript(sb.toString().trim());
        isUpdatingFromScript = false;
    }

    private void syncInitialPoseStep() {
        double startX = 0, startY = 0, startHeading = 0;
        try {
            startX = Double.parseDouble(controlPanel.getStartXField().getText());
            startY = Double.parseDouble(controlPanel.getStartYField().getText());
            startHeading = Double.parseDouble(controlPanel.getStartHeadingField().getText());
        } catch (Exception ignored) {}

        String args = String.format(Locale.US, "%.2f, %.2f, %.2f", startX, startY, startHeading);
        AutonomousStep poseStep = AutonomousStep.command("SetInitialPoseCommand", args, "Start Pose");
        
        for (int i = 0; i < missionSteps.size(); i++) {
            AutonomousStep s = missionSteps.get(i);
            if (s.type == AutonomousStep.Type.CMD && s.rawLine.contains("SetInitialPoseCommand")) {
                missionSteps.set(i, poseStep);
                return;
            }
        }
        // Add at the top if missing
        missionSteps.add(0, poseStep);
    }

    private void syncPathsIntoSteps() {
        // We need to make sure every PathData in allPaths has a corresponding block in missionSteps.
        // And we remove blocks for paths no longer in allPaths.
        
        // Find all current path blocks in missionSteps
        List<PathData> pathsInSteps = new ArrayList<>();
        for (AutonomousStep s : missionSteps) {
            if (s.type == AutonomousStep.Type.PATH_HEADER && s.pathRef != null) {
                pathsInSteps.add(s.pathRef);
            }
        }

        // Remove steps for deleted paths
        missionSteps.removeIf(s -> (s.type == AutonomousStep.Type.PATH_HEADER || s.type == AutonomousStep.Type.POINT) 
                                     && s.pathRef != null && !allPaths.contains(s.pathRef));

        // Update existing blocks or add new ones
        for (PathData path : allPaths) {
            if (pathsInSteps.contains(path)) {
                updatePathInSteps(path);
            } else {
                appendPathToSteps(path);
            }
        }
    }

    private void updatePathInSteps(PathData path) {
        // Find header
        int headerIdx = -1;
        for (int i = 0; i < missionSteps.size(); i++) {
            if (missionSteps.get(i).type == AutonomousStep.Type.PATH_HEADER && missionSteps.get(i).pathRef == path) {
                headerIdx = i;
                break;
            }
        }
        if (headerIdx == -1) return;

        double hField = parseHeadingExpression(path.followAngle, isRedAlliance);

        // Update header string (in case name or heading changed)
        missionSteps.get(headerIdx).updatePathHeader(path.name, hField, "END");

        // Remove old points for this path
        int idx = headerIdx + 1;
        while (idx < missionSteps.size() && missionSteps.get(idx).type == AutonomousStep.Type.POINT && missionSteps.get(idx).pathRef == path) {
            missionSteps.remove(idx);
        }

        // Add current points
        for (int i = 0; i < path.points.size(); i++) {
            AutonomousStep pStep = AutonomousStep.point(path.points.get(i));
            pStep.pathRef = path;
            pStep.pointRef = path.points.get(i);
            missionSteps.add(headerIdx + 1 + i, pStep);
        }
    }

    private void appendPathToSteps(PathData path) {
        // Add at the end
        missionSteps.add(new AutonomousStep(AutonomousStep.Type.EMPTY, ""));
        AutonomousStep header = AutonomousStep.pathHeader(path.name, path.followAngle, "END");
        header.pathRef = path;
        missionSteps.add(header);
        for (CurvePoint p : path.points) {
            AutonomousStep pStep = AutonomousStep.point(p);
            pStep.pathRef = path;
            pStep.pointRef = p;
            missionSteps.add(pStep);
        }
    }

    private void updateStateFromMissionScript(String script) {
        isUpdatingFromScript = true;
        missionSteps.clear();
        allPaths.clear();
        
        boolean hasInitialPose = false;
        PathData currentPath = null;
        String[] lines = script.split("\\r?\\n");
        
        for (String line : lines) {
            String trimmed = line.trim();
            AutonomousStep step;
            
            if (trimmed.startsWith("PATH:")) {
                step = new AutonomousStep(AutonomousStep.Type.PATH_HEADER, line);
                String name = "Path";
                Matcher m = Pattern.compile("name=\"(.*?)\"").matcher(line);
                if (m.find()) name = m.group(1);
                currentPath = new PathData(name);
                m = Pattern.compile("heading=([^\\s|]+)").matcher(line);
                if (m.find()) currentPath.followAngle = m.group(1).trim();
                
                step.pathRef = currentPath;
                allPaths.add(currentPath);
            } else if (trimmed.startsWith("P:")) {
                step = new AutonomousStep(AutonomousStep.Type.POINT, line);
                if (currentPath != null) {
                    CurvePoint cp = parsePointLine(line);
                    if (cp != null) {
                        currentPath.points.add(cp);
                        step.pathRef = currentPath;
                        step.pointRef = cp;
                    }
                }
            } else if (trimmed.startsWith("WAIT:")) {
                step = new AutonomousStep(AutonomousStep.Type.WAIT, line);
            } else if (trimmed.startsWith("CMD:")) {
                step = new AutonomousStep(AutonomousStep.Type.CMD, line);
                if (trimmed.contains("SetInitialPoseCommand")) {
                    parseSetInitialPose(line);
                    hasInitialPose = true;
                }
            } else if (trimmed.isEmpty()) {
                step = new AutonomousStep(AutonomousStep.Type.EMPTY, "");
            } else {
                step = new AutonomousStep(AutonomousStep.Type.COMMENT, line);
            }
            missionSteps.add(step);
        }

        if (hasInitialPose) {
            handleRobotStartFieldFocusLost();
        }

        boolean forceSnap = (selectedPath == null);
        if (selectedPath == null && !allPaths.isEmpty()) {
            selectedPath = allPaths.get(0);
        } else if (!allPaths.contains(selectedPath)) {
            selectedPath = allPaths.isEmpty() ? null : allPaths.get(allPaths.size() - 1);
            forceSnap = true;
        }

        if (forceSnap && selectedPath != null) {
            snapRobotToPath(selectedPath);
        } else if (selectedPath != null) {
            updateRobotHeadingIfAtWaypoint(selectedPath);
        }

        if (fieldDisplay != null) {
            fieldDisplay.setPathsToDraw(allPaths, selectedPath);
            fieldDisplay.drawCurrentState();
        }
        updateControlPanelForPathState();
        isUpdatingFromScript = false;
    }

    private CurvePoint parsePointLine(String line) {
        String content = line.substring(2).trim();
        String[] p = content.split(",");
        if (p.length >= 7) {
            try {
                return new CurvePoint(
                    evalScriptExpr(p[0], isRedAlliance),
                    evalScriptExpr(p[1], isRedAlliance),
                    evalScriptExpr(p[2], isRedAlliance),
                    evalScriptExpr(p[3], isRedAlliance),
                    evalScriptExpr(p[4], isRedAlliance),
                    Math.toRadians(evalScriptExpr(p[5], isRedAlliance)),
                    evalScriptExpr(p[6], isRedAlliance)
                );
            } catch (Exception e) {
                System.err.println("Error parsing point line: " + line);
            }
        }
        return null;
    }

    private void parseSetInitialPose(String line) {
        Pattern argsPattern = Pattern.compile("args=\\[(.*?)\\]");
        Matcher m = argsPattern.matcher(line);
        if (m.find()) {
            String[] parts = m.group(1).split(",");
            if (parts.length >= 3) {
                try {
                    double x = evalScriptExpr(parts[0], isRedAlliance);
                    double y = evalScriptExpr(parts[1], isRedAlliance);
                    double h = evalScriptExpr(parts[2], isRedAlliance);
                    
                    controlPanel.getStartXField().setText(String.format(Locale.US, "%.2f", x));
                    controlPanel.getStartYField().setText(String.format(Locale.US, "%.2f", y));
                    controlPanel.getStartHeadingField().setText(String.format(Locale.US, "%.2f", h));
                    
                    handleRobotStartFieldFocusLost();
                } catch (Exception e) {
                    System.err.println("Error parsing SetInitialPoseCommand args: " + line);
                }
            }
        }
    }

    private String wrapY(String expr, double allianceMultiplier) {
        expr = expr.trim();
        if (expr.startsWith("y(")) return expr;
        try {
            double val = Double.parseDouble(expr);
            if (Math.abs(allianceMultiplier - 1.0) < 1e-9) {
                return String.format(Locale.US, "%.2f", val);
            }
            return String.format(Locale.US, "y(%.2f)", val * allianceMultiplier);
        } catch (Exception e) {
            return expr;
        }
    }



    private double evalScriptExpr(String expr, boolean isRed) {
        expr = expr.trim();
        if (expr.startsWith("y(")) {
            double allianceMultiplier = isRed ? -1.0 : 1.0;
            Matcher m = Pattern.compile("y\\(\\s*([\\d\\.\\-]+)\\s*\\)").matcher(expr);
            if (m.find()) {
                return Double.parseDouble(m.group(1)) * allianceMultiplier;
            }
        }
        if (expr.startsWith("fa(") || expr.startsWith("h(") || expr.startsWith("reflectH(")) {
            return parseHeadingExpression(expr, isRed);
        }
        try {
            return Double.parseDouble(expr);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private double normalizeDegrees(double deg) {
        while (deg > 180) deg -= 360;
        while (deg <= -180) deg += 360;
        return deg;
    }

    private void handlePathSelectionChanged(ObservableValue<? extends PathData> obs, PathData oldVal, PathData newVal) {
        // If we're currently creating a path, we ignore selection changes from the ComboBox
        // to prevent programmatic updates from resetting the selected path.
        if (isCreatingPath) return;
        
        if (newVal != null && newVal != selectedPath) {
            selectedPath = newVal;
            // Update follow angle field for the new selected path
            controlPanel.getFollowAngleField().setText(selectedPath.followAngle);
            updateControlPanelForPathState();

            // Snap robot to the start of the selected path (only if not doing bulk navigation)
            if (!isNavigating) {
                snapRobotToPath(selectedPath);
            }

            if (fieldDisplay != null) {
                fieldDisplay.setPathsToDraw(allPaths, selectedPath);
                fieldDisplay.drawCurrentState();
            }
        }
    }

    private void snapRobotToPath(PathData path) {
        snapRobotToPoint(path, 0);
    }

    private void snapRobotToPoint(PathData path, int pointIndex) {
        if (path == null || path.points.isEmpty() || pointIndex < 0 || pointIndex >= path.points.size()) return;

        CurvePoint cp = path.points.get(pointIndex);
        double pathDirectionDeg = getPathDirectionAtPoint(path, pointIndex);
        double followAngleValue = parseHeadingExpression(path.followAngle, isRedAlliance);
        double targetHeading = pathDirectionDeg + (followAngleValue - 90.0);

        robot.setPosition(cp.x, cp.y, targetHeading);
        updateUIFromRobotState();
    }

    private void navigatePaths(int offset) {
        if (allPaths.isEmpty() || isCreatingPath) return;
        
        // Find current index by instance, or by name if instances changed (e.g. script update)
        int currentIndex = allPaths.indexOf(selectedPath);
        if (currentIndex == -1 && selectedPath != null) {
            for (int i = 0; i < allPaths.size(); i++) {
                if (allPaths.get(i).name.equals(selectedPath.name)) {
                    currentIndex = i;
                    break;
                }
            }
        }
        
        int nextIndex = currentIndex + offset;
        if (nextIndex >= 0 && nextIndex < allPaths.size()) {
            PathData nextPath = allPaths.get(nextIndex);
            // Setting the value on the ComboBox triggers handlePathSelectionChanged
            controlPanel.getPathSelectionComboBox().setValue(nextPath);
        }
    }

    private void navigatePoints(int offset) {
        if (allPaths.isEmpty() || isCreatingPath) return;
        if (selectedPath == null) {
            selectedPath = allPaths.get(0);
        }

        Object currentSelection = controlPanel.getSelectedPointFromComboBox();
        int currentIndex = -1; // -1 represents ALL_POINTS_MARKER
        if (currentSelection instanceof CurvePoint) {
            currentIndex = selectedPath.points.indexOf(currentSelection);
        }

        isNavigating = true;
        try {
            if (offset > 0) { // Next
                if (currentIndex < selectedPath.points.size() - 1) {
                    // Next point in same path
                    controlPanel.getPointSelectionComboBox().setValue(selectedPath.points.get(currentIndex + 1));
                } else {
                    // Go to next path, first point
                    int pathIdx = allPaths.indexOf(selectedPath);
                    if (pathIdx < allPaths.size() - 1) {
                        PathData nextPath = allPaths.get(pathIdx + 1);
                        if (!nextPath.points.isEmpty()) {
                            // Change path and explicitly select first point
                            controlPanel.getPathSelectionComboBox().setValue(nextPath);
                            controlPanel.getPointSelectionComboBox().setValue(nextPath.points.get(0));
                        }
                    }
                }
            } else if (offset < 0) { // Previous
                if (currentIndex > 0) {
                    // Previous point in same path
                    controlPanel.getPointSelectionComboBox().setValue(selectedPath.points.get(currentIndex - 1));
                } else if (currentIndex == 0 || currentIndex == -1) {
                    // At P1 or ALL, go to previous path, last point
                    int pathIdx = allPaths.indexOf(selectedPath);
                    if (pathIdx > 0) {
                        PathData prevPath = allPaths.get(pathIdx - 1);
                        if (!prevPath.points.isEmpty()) {
                            // Change path and explicitly select last point
                            controlPanel.getPathSelectionComboBox().setValue(prevPath);
                            controlPanel.getPointSelectionComboBox().setValue(prevPath.points.get(prevPath.points.size() - 1));
                        }
                    }
                }
            }
        } finally {
            isNavigating = false;
        }
    }

    private void handleDeletePoint(CurvePoint pointToDelete) {
        if (selectedPath == null || pointToDelete == null || !selectedPath.points.contains(pointToDelete)) return;

        int deletedIndex = selectedPath.points.indexOf(pointToDelete);
        selectedPath.points.remove(pointToDelete);

        if (deletedIndex == 0 && allPaths.indexOf(selectedPath) == 0 && !selectedPath.points.isEmpty()) {
            CurvePoint newFirstPoint = selectedPath.points.get(0);
            robot.setPosition(newFirstPoint.x, newFirstPoint.y);
        }

        fieldDisplay.setHighlightedPoint(null);
        fieldDisplay.setPathsToDraw(allPaths, selectedPath);
        updateControlPanelForPathState();
        updateUIFromRobotState();
        updateMissionScriptFromState(); // Add this line

        instructionLabel.setText("Deleted Point " + (deletedIndex + 1) + ".");
    }

    private void setupFieldDisplayMouseHandlers() {
        if (fieldDisplay == null) return;
        fieldDisplay.setOnSegmentClick(this::handleInsertPoint);
        fieldDisplay.setOnPointDrag((index, newCoords) -> {
            if (selectedPath == null) return;
            
            // Move robot to the point as it's being dragged
            snapRobotToPoint(selectedPath, index);

            // Chaining logic: if this is the last point, and there's a next path, update next path's first point
            if (index == selectedPath.points.size() - 1) {
                int pathIndex = allPaths.indexOf(selectedPath);
                if (pathIndex < allPaths.size() - 1) {
                    PathData nextPath = allPaths.get(pathIndex + 1);
                    if (!nextPath.points.isEmpty()) {
                        nextPath.points.get(0).x = newCoords.getX();
                        nextPath.points.get(0).y = newCoords.getY();
                    }
                }
            }
            // If this is the first point and not the first path, we shouldn't really be dragging it independently
            // but for now let's just update the previous path's last point
            if (index == 0) {
                int pathIndex = allPaths.indexOf(selectedPath);
                if (pathIndex > 0) {
                    PathData prevPath = allPaths.get(pathIndex - 1);
                    if (!prevPath.points.isEmpty()) {
                        prevPath.points.get(prevPath.points.size() - 1).x = newCoords.getX();
                        prevPath.points.get(prevPath.points.size() - 1).y = newCoords.getY();
                    }
                }
            }

            if (controlPanel.getSelectedPointFromComboBox() != selectedPath.points.get(index)) {
                controlPanel.updatePointSelectionComboBox(selectedPath.points, selectedPath.points.get(index));
            }
            controlPanel.loadParametersForPoint(selectedPath.points.get(index));
            instructionLabel.setText(String.format(Locale.US, "Dragging Point %d to (X:%.1f, Y:%.1f)",
                    index + 1, newCoords.getX(), newCoords.getY()));
        });

        fieldDisplay.setOnPointDragEnd(index -> {
            if (selectedPath != null && index >= 0 && index < selectedPath.points.size()) {
                CurvePoint point = selectedPath.points.get(index);
                instructionLabel.setText(String.format(Locale.US, "Moved Point %d.", index + 1));
                controlPanel.updatePointSelectionComboBox(selectedPath.points, point);
                updateMissionScriptFromState();
            }
        });
    }

    private void handleInsertPoint(int segmentIndex, Point2D clickCoordsPixels) {
        if (selectedPath == null || segmentIndex < 0 || segmentIndex >= selectedPath.points.size() - 1) return;

        Point2D clickCoordsInches = fieldDisplay.pixelToInches(clickCoordsPixels.getX(), clickCoordsPixels.getY());
        CurvePoint startPoint = selectedPath.points.get(segmentIndex);
        CurvePoint endPoint = selectedPath.points.get(segmentIndex + 1);

        double newMoveSpeed = (startPoint.moveSpeed + endPoint.moveSpeed) / 2.0;
        double newTurnSpeed = (startPoint.turnSpeed + endPoint.turnSpeed) / 2.0;
        double newFollowDistance = (startPoint.followDistance + endPoint.followDistance) / 2.0;
        double newSlowDownTurnRadians = (startPoint.slowDownTurnRadians + endPoint.slowDownTurnRadians) / 2.0;
        double newSlowDownTurnAmount = (startPoint.slowDownTurnAmount + endPoint.slowDownTurnAmount) / 2.0;

        CurvePoint newPoint = new CurvePoint(clickCoordsInches.getX(), clickCoordsInches.getY(), newMoveSpeed, newTurnSpeed, newFollowDistance, newSlowDownTurnRadians, newSlowDownTurnAmount);
        selectedPath.points.add(segmentIndex + 1, newPoint);
        
        // Move robot to the newly inserted point
        snapRobotToPoint(selectedPath, segmentIndex + 1);

        fieldDisplay.setPathsToDraw(allPaths, selectedPath);
        fieldDisplay.setHighlightedPoint(newPoint);
        updateControlPanelForPathState();
        updateUIFromRobotState();
        updateMissionScriptFromState();

        instructionLabel.setText("Inserted new point " + (segmentIndex + 2) + ".");
    }

    private void updateUIFromRobotState() {
        if (robot != null && fieldDisplay != null) {
            double displayHeading = robot.getHeadingDegrees() % 360;
            if (displayHeading < 0) displayHeading += 360;

            if (fieldStatusDisplay != null) {
                fieldStatusDisplay.updateRobotStatus(robot.getXInches(), robot.getYInches(), displayHeading);
            }

            fieldDisplay.drawCurrentState();
        }
    }

    private void showPlotDisplay() {
        if (plotDisplayWindow == null) {
            plotDisplayWindow = new PlotDisplayWindow(primaryStage);
        }
        plotDisplayWindow.show();
    }

    private void handleUdpPlotData(PlotDataEvent dataEvent) {
        if (dataEvent == null) return;
        Platform.runLater(() -> {
            if (plotDisplayWindow != null && plotDisplayWindow.isShowing()) {
                PlotDisplay display = plotDisplayWindow.getPlotDisplay();
                if (display != null) {
                    display.addPlotEvent(dataEvent);
                }
            }
        });
    }

    private void setupControlPanelActions(Stage ownerStage) {
        controlPanel.setOnNewPathAction(event -> startNewPathCreation());
        controlPanel.setOnDeletePathAction(event -> deleteCurrentPath());
        controlPanel.setOnPrevPathAction(event -> navigatePaths(-1));
        controlPanel.setOnNextPathAction(event -> navigatePaths(1));
        controlPanel.setOnPathSelectionAction(this::handlePathSelectionChanged);
        controlPanel.setOnImportCodeAction(event -> showImportMissionDialog());
        controlPanel.setOnExportCodeAction(event -> exportMissionToCode());
        controlPanel.setOnEditMissionAction(event -> showMissionScriptEditor());
        controlPanel.setOnSendMissionAction(event -> handleSendMissionToRobot());
        controlPanel.setOnClearTrailAction(event -> {
            fieldDisplay.clearTrail();
            fieldDisplay.drawCurrentState();
            instructionLabel.setText("Robot trail cleared.");
        });
        controlPanel.setOnClearNamedLinesAction(event -> {
            clearAllNamedLines();
            instructionLabel.setText("All custom lines cleared.");
        });
        controlPanel.setOnPointSelectionAction(this::handlePointSelectionChanged);
        controlPanel.setOnPrevPointAction(event -> navigatePoints(-1));
        controlPanel.setOnNextPointAction(event -> navigatePoints(1));
        controlPanel.setOnShowPlotAction(event -> showPlotDisplay());
    }

    private static class ImportResult {
        String code;
        boolean isRed;
        ImportResult(String code, boolean isRed) {
            this.code = code;
            this.isRed = isRed;
        }
    }

    private void showMissionScriptEditor() {
        if (missionEditorStage == null) {
            missionEditorStage = new Stage();
            missionEditorStage.setTitle("Mission Script Editor");
            missionEditorStage.initOwner(primaryStage);

            popupScriptArea = new TextArea(controlPanel.getMissionScript());
            popupScriptArea.setFont(Font.font("Consolas", 14));
            popupScriptArea.setPrefSize(800, 600);
            popupScriptArea.setWrapText(false); // Line numbers only work well without wrapping

            RadioButton blueBtn = new RadioButton("Blue Alliance");
            RadioButton redBtn = new RadioButton("Red Alliance");
            ToggleGroup group = new ToggleGroup();
            blueBtn.setToggleGroup(group);
            redBtn.setToggleGroup(group);
            
            if (isRedAlliance) redBtn.setSelected(true);
            else blueBtn.setSelected(true);

            group.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
                boolean targetRed = (newToggle == redBtn);
                if (targetRed != isRedAlliance) {
                    boolean oldRed = isRedAlliance;
                    this.isRedAlliance = targetRed;
                    // Automatically transform numeric values to maintain field positions
                    transformScriptAlliance(oldRed, targetRed);
                    // Refresh display and state
                    updateStateFromMissionScript(popupScriptArea.getText());
                }
            });

            HBox allianceBox = new HBox(15, new Label("Context:"), blueBtn, redBtn);
            allianceBox.setAlignment(Pos.CENTER_LEFT);
            allianceBox.setPadding(new Insets(0, 0, 5, 0));

            // Line numbers gutter
            TextArea lineNumbersArea = new TextArea("1");
            lineNumbersArea.setFont(Font.font("Consolas", 14));
            lineNumbersArea.setEditable(false);
            lineNumbersArea.setFocusTraversable(false);
            lineNumbersArea.setPrefWidth(50);
            lineNumbersArea.setMinWidth(50);
            lineNumbersArea.setMaxWidth(50);
            // Prevent scroll bar from appearing on gutter and make it non-interactive
            lineNumbersArea.setStyle("-fx-background-color: #EEE; -fx-text-fill: #888; -fx-opacity: 1.0; -fx-control-inner-background: #EEE;");
            lineNumbersArea.setMouseTransparent(true);

            // Hide scrollbars specifically for the gutter
            lineNumbersArea.skinProperty().addListener((obs, oldSkin, newSkin) -> {
                if (newSkin != null) {
                    ScrollPane sp = (ScrollPane) lineNumbersArea.lookup(".scroll-pane");
                    if (sp != null) {
                        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
                        sp.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
                    }
                }
            });

            // Hide horizontal scrollbar for the main editor as requested
            popupScriptArea.skinProperty().addListener((obs, oldSkin, newSkin) -> {
                if (newSkin != null) {
                    ScrollPane sp = (ScrollPane) popupScriptArea.lookup(".scroll-pane");
                    if (sp != null) {
                        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
                    }
                }
            });

            Runnable updateLineNumbers = () -> {
                int lineCount = popupScriptArea.getText().split("\n", -1).length;
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= lineCount; i++) {
                    sb.append(i).append("\n");
                }
                lineNumbersArea.setText(sb.toString().trim());
                // After setting text, force scroll sync
                lineNumbersArea.setScrollTop(popupScriptArea.getScrollTop());
            };

            // Sync: Popup -> ControlPanel (which then syncs to state)
            popupScriptArea.textProperty().addListener((obs, oldVal, newVal) -> {
                if (popupScriptArea.isFocused()) {
                    controlPanel.setMissionScript(newVal);
                }
                updateLineNumbers.run();
            });

            // Very hacky sync of scrollbars in standard JavaFX
            popupScriptArea.scrollTopProperty().addListener((obs, oldVal, newVal) -> {
                lineNumbersArea.setScrollTop(newVal.doubleValue());
            });

            updateLineNumbers.run();

            HBox editorBox = new HBox(lineNumbersArea, popupScriptArea);
            HBox.setHgrow(popupScriptArea, Priority.ALWAYS);

            // Hint section
            TextArea hintArea = new TextArea();
            hintArea.setEditable(false);
            hintArea.setPrefHeight(130);
            hintArea.setFont(Font.font("Consolas", 12));
            hintArea.setText(
                "# Quick Reference (Copy & Paste templates):\n" +
                "CMD: SetInitialPoseCommand | args=[-62.04, 14.00, 0.00] | name=\"Start\"\n" +
                "PATH: name=\"Drive to Shoot\" | heading=90.0 | transition=END\n" +
                "P: -60.0, 10.0, 1.0, 0.4, 10.0, 60.0, 0.60\n" +
                "WAIT: 1.5\n" +
                "CMD: Shoot3BallsCommand | args=[] | name=\"Action Name\""
            );
            hintArea.setStyle("-fx-control-inner-background: #F5F5F5; -fx-text-fill: #555;");

            VBox root = new VBox(10, new Label("Edit Mission Script:"), allianceBox, editorBox, new Label("Available Step Formats:"), hintArea);
            VBox.setVgrow(editorBox, Priority.ALWAYS);
            root.setPadding(new Insets(10));

            Scene scene = new Scene(root, 900, 800);
            missionEditorStage.setScene(scene);
        } else {
            popupScriptArea.setText(controlPanel.getMissionScript());
            // Update selection if state changed elsewhere (like Import)
            VBox root = (VBox) missionEditorStage.getScene().getRoot();
            HBox allianceRow = (HBox) root.getChildren().get(1);
            RadioButton blueBtn = (RadioButton) allianceRow.getChildren().get(1);
            RadioButton redBtn = (RadioButton) allianceRow.getChildren().get(2);
            if (isRedAlliance) redBtn.setSelected(true);
            else blueBtn.setSelected(true);
        }

        missionEditorStage.show();
        missionEditorStage.toFront();
    }

    private void transformScriptAlliance(boolean fromRed, boolean toRed) {
        String currentText = popupScriptArea.getText();
        if (currentText == null || currentText.isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        String[] lines = currentText.split("\\r?\\n");
        for (String line : lines) {
            String transformed = transformLineAlliance(line, fromRed, toRed);
            sb.append(transformed).append("\n");
        }
        popupScriptArea.setText(sb.toString().trim());
        controlPanel.setMissionScript(popupScriptArea.getText());
    }

    private String transformLineAlliance(String line, boolean fromRed, boolean toRed) {
        String trimmed = line.trim();
        
        // 1. Transform raw numeric values for known simulator structures
        
        // P: x, y, ms, ts, fd, slowTurnDeg, sa
        if (trimmed.startsWith("P:")) {
            String content = trimmed.substring(2).trim();
            String[] p = content.split(",\\s*");
            if (p.length >= 7) {
                try {
                    double y = Double.parseDouble(p[1]);
                    p[1] = String.format(Locale.US, "%.2f", -y);
                    return "P: " + String.join(", ", p);
                } catch (Exception ignored) {}
            }
        }

        // PATH: name="..." | heading=val | ...
        if (trimmed.startsWith("PATH:")) {
            Pattern hPat = Pattern.compile("heading=([\\d\\.\\-]+)");
            Matcher m = hPat.matcher(line);
            if (m.find()) {
                double h = Double.parseDouble(m.group(1));
                // Path heading is a "Follow Angle" -> fa() logic: 180 - degrees
                return line.replaceFirst("heading=[\\d\\.\\-]+", "heading=" + String.format(Locale.US, "%.2f", normalizeDegrees(180.0 - h)));
            }
        }

        // CMD: SetInitialPoseCommand | args=[x, y, h]
        if (trimmed.contains("SetInitialPoseCommand")) {
            Pattern argsPat = Pattern.compile("args=\\[(.*?)\\]");
            Matcher m = argsPat.matcher(line);
            if (m.find()) {
                String[] args = splitArgsIgnoreParens(m.group(1));
                if (args.length >= 3) {
                    try {
                        double y = Double.parseDouble(args[1]);
                        double h = Double.parseDouble(args[2]);
                        args[1] = String.format(Locale.US, "%.2f", -y);
                        // Initial Pose heading -> h() logic: 360 - degrees
                        args[2] = String.format(Locale.US, "%.2f", normalizeDegrees(360.0 - h));
                        return line.replaceFirst("args=\\[.*?\\]", "args=[" + String.join(", ", args) + "]");
                    } catch (Exception ignored) {}
                }
            }
        }

        // Generic Command special cases
        if (trimmed.contains("AutoIntakeCommand")) {
            Pattern argsPat = Pattern.compile("args=\\[(.*?)\\]");
            Matcher m = argsPat.matcher(line);
            if (m.find()) {
                String[] args = splitArgsIgnoreParens(m.group(1));
                if (args.length >= 1) {
                    try {
                        double h = Double.parseDouble(args[0]);
                        // AutoIntake heading uses h() logic -> 360 - degrees
                        args[0] = String.format(Locale.US, "%.2f", normalizeDegrees(360.0 - h));
                        return line.replaceFirst("args=\\[.*?\\]", "args=[" + String.join(", ", args) + "]");
                    } catch (Exception ignored) {}
                }
            }
        }
        
        return line;
    }

    private void showImportMissionDialog() {
        Dialog<ImportResult> dialog = new Dialog<>();
        dialog.setTitle("Import Mission from Java Code");
        dialog.setHeaderText("Paste your robot autonomous code snippet below.");
        dialog.setResizable(true);

        TextArea textArea = new TextArea();
        textArea.setPromptText("public void buildAuto() {\n  scheduler.add(...);\n  ...\n}");
        textArea.setFont(Font.font("Consolas", 14));
        textArea.setPrefHeight(400);
        textArea.setPrefWidth(800);

        RadioButton blueBtn = new RadioButton("Blue Alliance");
        RadioButton redBtn = new RadioButton("Red Alliance");
        ToggleGroup group = new ToggleGroup();
        blueBtn.setToggleGroup(group);
        redBtn.setToggleGroup(group);
        
        if (isRedAlliance) redBtn.setSelected(true);
        else blueBtn.setSelected(true);

        HBox allianceBox = new HBox(15, new Label("Alliance Context:"), blueBtn, redBtn);
        allianceBox.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(10, new Label("Java Code Snippet:"), textArea, allianceBox);
        VBox.setVgrow(textArea, Priority.ALWAYS);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefSize(800, 600);

        ButtonType importButtonType = new ButtonType("Import", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(importButtonType, ButtonType.CANCEL);

        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == importButtonType) {
                return new ImportResult(textArea.getText(), redBtn.isSelected());
            }
            return null;
        });

        Optional<ImportResult> result = dialog.showAndWait();
        result.ifPresent(res -> {
            this.isRedAlliance = res.isRed;
            String missionScript = parseJavaToMission(res.code, res.isRed);
            this.selectedPath = null; // Ensure the first path of the new mission is selected
            controlPanel.setMissionScript(missionScript);
            // updateStateFromMissionScript(missionScript); // This is already triggered by setMissionScript listener
        });
    }

    private String parseJavaToMission(String code, boolean isRed) {
        StringBuilder script = new StringBuilder();
        double allianceMultiplier = isRed ? -1.0 : 1.0;

        // Remove comments
        code = code.replaceAll("//.*", "");
        code = code.replaceAll("(?s)/\\*.*?\\*/", "");

        // Pre-process: Join multi-line method chaining and constructor calls
        code = code.replaceAll("\\r?\\n\\s*\\.", ".");
        code = code.replaceAll(",\\s*\\r?\\n", ", ");
        code = code.replaceAll("\\(\\s*\\r?\\n", "(");

        // 1. Detect start position (INIT)
        // Look for Pose2D(world, x, y, h) or Pose2D(x, y, h) or inside SetInitialPoseCommand
        // We use a pattern that correctly handles balanced parentheses for nested calls like y() or h()
        Pattern posePattern = Pattern.compile("(?:new\\s+Pose2D|new\\s+SetInitialPoseCommand|SetInitialPoseCommand)\\s*\\(([^()]*(?:\\([^()]*\\)[^()]*)*)\\)");
        Matcher poseMatcher = posePattern.matcher(code);
        String initialPoseLine = null;
        if (poseMatcher.find()) {
            String allArgs = poseMatcher.group(1).trim();
            String[] argParts = splitArgsIgnoreParens(allArgs);
            if (argParts.length >= 3) {
                // If 4 args, first is world. If 3, first is X.
                int offset = (argParts.length == 4) ? 1 : 0;
                try {
                    double x = Double.parseDouble(argParts[offset]);
                    double y = evalScriptExpr(argParts[offset + 1], isRed);
                    double hField = parseHeadingExpression(argParts[offset + 2], isRed);
                    initialPoseLine = AutonomousStep.init(x, y, hField).rawLine;
                } catch (Exception ignored) {}
            }
        }

        // 2. Multi-pass parsing for paths and commands
        boolean sequenceHasInitialPose = false;
        Map<String, List<CurvePoint>> pathsFound = new HashMap<>();
        Pattern pointPattern = Pattern.compile("(\\w+)\\.add\\(new\\s+CurvePoint\\(\\s*([\\d\\.\\-]+),\\s*(?:y\\(\\s*([\\d\\.\\-]+)\\s*\\)|([\\d\\.\\-]+)),\\s*([\\d\\.\\-]+),\\s*([\\d\\.\\-]+),\\s*([\\d\\.\\-]+),\\s*Math\\.toRadians\\(\\s*(.*?)\\s*\\),\\s*([\\d\\.\\-]+)\\)\\)");
        
        String[] lines = code.split("\\r?\\n");
        for (String line : lines) {
            line = line.trim();
            
            // Collect points into path buffers
            Matcher pm = pointPattern.matcher(line);
            if (pm.find()) {
                String pathVar = pm.group(1);
                double px = Double.parseDouble(pm.group(2));
                
                String yGroup3 = pm.group(3);
                String yGroup4 = pm.group(4);
                double py;
                if (yGroup3 != null) {
                    py = Double.parseDouble(yGroup3) * allianceMultiplier;
                } else {
                    py = Double.parseDouble(yGroup4);
                }
                
                double ms = Double.parseDouble(pm.group(5));
                double ts = Double.parseDouble(pm.group(6));
                double fd = Double.parseDouble(pm.group(7));
                String slowTurnDegStr = pm.group(8).trim();
                double slowTurnDeg = Double.parseDouble(slowTurnDegStr);
                
                double sAmt = Double.parseDouble(pm.group(9));
                
                pathsFound.computeIfAbsent(pathVar, k -> new ArrayList<>()).add(new CurvePoint(px, py, ms, ts, fd, Math.toRadians(slowTurnDeg), sAmt));
                continue;
            }

            // Detect scheduler.add() calls
            if (line.contains("scheduler.add(")) {
                if (line.contains("FollowPathCommand")) {
                    // Match: new FollowPathCommand(pathVar, heading, debug [, dist])
                    Pattern fpcPattern = Pattern.compile("new\\s+FollowPathCommand\\(\\s*(\\w+)\\s*,\\s*(.*?)\\s*,\\s*[^,)]+(?:\\s*,\\s*([^,)]+))?");
                    Matcher fm = fpcPattern.matcher(line);
                    if (fm.find()) {
                        String pathVar = fm.group(1);
                        String hExprStr = fm.group(2).trim(); 
                        double hField = parseHeadingExpression(hExprStr, isRed);
                        
                        String stepName = "Drive";
                        Matcher nm = Pattern.compile("\\.withName\\(\"(.*?)\"\\)").matcher(line);
                        if (nm.find()) stepName = nm.group(1);
                        
                        String trans = "END";
                        // If 4th constructor argument exists, use it as default transition
                        if (fm.group(3) != null) {
                            trans = "DIST(" + fm.group(3).trim() + ")";
                        }
                        
                        // Chained method overrides constructor argument
                        if (line.contains("transitionWhenDistancetoEndIsLessThan")) {
                            Matcher tm = Pattern.compile("transitionWhenDistancetoEndIsLessThan\\((.*?)\\)").matcher(line);
                            if (tm.find()) trans = "DIST(" + tm.group(1).trim() + ")";
                        }
                        if (line.contains("transitionImmediately()")) {
                            trans = "IMMEDIATE";
                        }

                        script.append(AutonomousStep.pathHeader(stepName, hField, trans)).append("\n");
                        List<CurvePoint> points = pathsFound.get(pathVar);
                        if (points != null) {
                            for (CurvePoint p : points) {
                                script.append(AutonomousStep.point(p).rawLine).append("\n");
                            }
                        }
                        script.append("\n");
                    }
                } else if (line.contains("SetInitialPoseCommand")) {
                    sequenceHasInitialPose = true;
                    // Already handled by INIT detection at the top, or capture here if it appears in scheduler
                    Pattern sipc = Pattern.compile("(?:new\\s+Pose2D|new\\s+SetInitialPoseCommand|SetInitialPoseCommand)\\s*\\(([^()]*(?:\\([^()]*\\)[^()]*)*)\\)");
                    Matcher sm = sipc.matcher(line);
                    if (sm.find()) {
                        String allArgs = sm.group(1).trim();
                        String[] argParts = splitArgsIgnoreParens(allArgs);
                        if (argParts.length >= 3) {
                            int offset = (argParts.length == 4) ? 1 : 0;
                            try {
                                double x = Double.parseDouble(argParts[offset]);
                                double y = evalScriptExpr(argParts[offset + 1], isRed);
                                double hField = parseHeadingExpression(argParts[offset + 2], isRed);
                                script.append(AutonomousStep.init(x, y, hField).rawLine).append("\n");
                            } catch (Exception ignored) {}
                        }
                    }
                } else if (line.contains("WaitCommand")) {
                    Matcher wm = Pattern.compile("WaitCommand\\((.*?)\\)").matcher(line);
                    if (wm.find()) {
                        script.append(AutonomousStep.waitStep(Double.parseDouble(wm.group(1).trim())).rawLine).append("\n");
                    }
                } else {
                    // Generic command - handles nested parentheses for y(), fa(), h() calls
                    Pattern cmPattern = Pattern.compile("new\\s+(\\w+)\\s*\\(([^()]*(?:\\([^()]*\\)[^()]*)*)\\)");
                    Matcher cm = cmPattern.matcher(line);
                    if (cm.find()) {
                        String className = cm.group(1);
                        String argsStr = cm.group(2).trim();
                        
                        // Resolve mirror functions to raw field values for the script
                        argsStr = resolveMirrorFunctionsToFieldValues(argsStr, isRed);
                        
                        String stepName = "";
                        Matcher nm = Pattern.compile("\\.withName\\s*\\(\\s*\"(.*?)\"\\s*\\)").matcher(line);
                        if (nm.find()) stepName = nm.group(1);
                        script.append(AutonomousStep.command(className, argsStr, stepName).rawLine).append("\n");
                    }
                }
            }
        }
        
        if (!sequenceHasInitialPose && initialPoseLine != null) {
            script.insert(0, initialPoseLine + "\n\n");
        }

        return script.toString();
    }

    private String[] splitArgsIgnoreParens(String args) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;

            if (c == ',' && depth == 0) {
                parts.add(sb.toString().trim());
                sb = new StringBuilder();
            } else {
                sb.append(c);
            }
        }
        parts.add(sb.toString().trim());
        return parts.toArray(new String[0]);
    }

    private String formatYForJava(double fieldY, double allianceMultiplier) {
        double blueY = fieldY * allianceMultiplier;
        return String.format(Locale.US, "y(%.2f)", blueY);
    }

    private String formatFAForJava(double fieldHeading, boolean isRed) {
        double blueFA = isRed ? normalizeDegrees(180.0 - fieldHeading) : fieldHeading;
        return String.format(Locale.US, "fa(%.2f)", blueFA);
    }

    private String formatHForJava(double fieldHeading, boolean isRed) {
        double blueH = isRed ? normalizeDegrees(360.0 - fieldHeading) : fieldHeading;
        return String.format(Locale.US, "h(%.2f)", blueH);
    }

    private String resolveMirrorFunctionsToFieldValues(String args, boolean isRed) {
        double allianceMultiplier = isRed ? -1.0 : 1.0;
        
        // Resolve y(...)
        Pattern yPattern = Pattern.compile("y\\s*\\(\\s*([\\d\\.\\-]+)\\s*\\)");
        Matcher ym = yPattern.matcher(args);
        StringBuilder sb = new StringBuilder();
        int lastEnd = 0;
        while (ym.find()) {
            sb.append(args, lastEnd, ym.start());
            double val = Double.parseDouble(ym.group(1));
            sb.append(String.format(Locale.US, "%.2f", val * allianceMultiplier));
            lastEnd = ym.end();
        }
        sb.append(args.substring(lastEnd));
        args = sb.toString();

        // Resolve fa(...) - Follow Angle logic (180 - val)
        Pattern faPattern = Pattern.compile("fa\\s*\\(\\s*([\\d\\.\\-]+)\\s*\\)");
        Matcher fam = faPattern.matcher(args);
        sb = new StringBuilder();
        lastEnd = 0;
        while (fam.find()) {
            sb.append(args, lastEnd, fam.start());
            double val = Double.parseDouble(fam.group(1));
            double res = isRed ? normalizeDegrees(180.0 - val) : val;
            sb.append(String.format(Locale.US, "%.2f", res));
            lastEnd = fam.end();
        }
        sb.append(args.substring(lastEnd));
        args = sb.toString();

        // Resolve h(...) - Heading logic (360 - val)
        Pattern hPattern = Pattern.compile("h\\s*\\(\\s*([\\d\\.\\-]+)\\s*\\)");
        Matcher hm = hPattern.matcher(args);
        sb = new StringBuilder();
        lastEnd = 0;
        while (hm.find()) {
            sb.append(args, lastEnd, hm.start());
            double val = Double.parseDouble(hm.group(1));
            double res = isRed ? normalizeDegrees(360.0 - val) : val;
            sb.append(String.format(Locale.US, "%.2f", res));
            lastEnd = hm.end();
        }
        sb.append(args.substring(lastEnd));
        args = sb.toString();

        // Resolve reflectH(...)
        Pattern rhPattern = Pattern.compile("reflectH\\s*\\(\\s*([\\d\\.\\-]+)\\s*,\\s*([\\d\\.\\-]+)\\s*\\)");
        Matcher rhm = rhPattern.matcher(args);
        sb = new StringBuilder();
        lastEnd = 0;
        while (rhm.find()) {
            sb.append(args, lastEnd, rhm.start());
            double val = Double.parseDouble(rhm.group(1));
            double axis = Double.parseDouble(rhm.group(2));
            double res = isRed ? normalizeDegrees(2 * axis - val) : val;
            sb.append(String.format(Locale.US, "%.2f", res));
            lastEnd = rhm.end();
        }
        sb.append(args.substring(lastEnd));
        return sb.toString();
    }

    private double parseHeadingExpression(String expr, boolean isRed) {
        expr = expr.trim();
        // Handle "isRed ? 75 : 115"
        if (expr.contains("isRed")) {
            Pattern expPattern = Pattern.compile("isRed\\s*\\?\\s*([\\d\\.\\-]+)\\s*:\\s*([\\d\\.\\-]+)");
            Matcher m = expPattern.matcher(expr);
            if (m.find()) {
                return isRed ? Double.parseDouble(m.group(1)) : Double.parseDouble(m.group(2));
            }
        }
        // Handle "fa(90)" - follow angle
        if (expr.contains("fa(")) {
            Matcher m = Pattern.compile("fa\\(\\s*([\\d\\.\\-]+)\\s*\\)").matcher(expr);
            if (m.find()) {
                double blueDeg = Double.parseDouble(m.group(1));
                if (!isRed) return blueDeg;
                return normalizeDegrees(180.0 - blueDeg);
            }
        }
        // Handle "h(90)" - initial heading
        if (expr.contains("h(")) {
            Matcher m = Pattern.compile("h\\(\\s*([\\d\\.\\-]+)\\s*\\)").matcher(expr);
            if (m.find()) {
                double blueDeg = Double.parseDouble(m.group(1));
                if (!isRed) return blueDeg;
                return normalizeDegrees(360.0 - blueDeg);
            }
        }
        // Handle "reflectH(90, 0)"
        if (expr.contains("reflectH(")) {
            Matcher m = Pattern.compile("reflectH\\(\\s*([\\d\\.\\-]+)\\s*,\\s*([\\d\\.\\-]+)\\s*\\)").matcher(expr);
            if (m.find()) {
                double blueDeg = Double.parseDouble(m.group(1));
                double axis = Double.parseDouble(m.group(2));
                if (!isRed) return blueDeg;
                return normalizeDegrees(2 * axis - blueDeg);
            }
        }
        // Handle "mirroredHeading(180)"
        if (expr.contains("mirroredHeading")) {
            Matcher m = Pattern.compile("mirroredHeading\\(\\s*([\\d\\.\\-]+)\\s*\\)").matcher(expr);
            if (m.find()) return Double.parseDouble(m.group(1));
        }
        // Handle "Math.toRadians(90)"
        if (expr.contains("Math.toRadians")) {
            Matcher m = Pattern.compile("Math\\.toRadians\\(\\s*(.*?)\\s*\\)").matcher(expr);
            if (m.find()) return parseHeadingExpression(m.group(1), isRed);
        }
        try { 
            double val = Double.parseDouble(expr); 
            return val;
        } catch (Exception e) { return 0.0; }
    }

    private void exportMissionToCode() {
        String script = controlPanel.getMissionScript();
        if (script == null || script.isEmpty()) {
            instructionLabel.setText("No mission script to export.");
            return;
        }

        try {
            StringBuilder code = new StringBuilder();
            double allianceMultiplier = isRedAlliance ? -1.0 : 1.0;
            
            code.append("public void buildAutonomous() {\n");

            String[] lines = script.split("\\r?\\n");
            int pathCount = 1;
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                if (line.startsWith("INIT:")) continue; 

                if (line.startsWith("PATH:")) {
                    String name = "Path " + pathCount;
                    String hExpr = "0.0";
                    String trans = "END";
                    
                    Matcher m = Pattern.compile("name=\"(.*?)\"").matcher(line);
                    if (m.find()) name = m.group(1);
                    m = Pattern.compile("heading=([^\\s|]+)").matcher(line);
                    if (m.find()) hExpr = m.group(1).trim();
                    m = Pattern.compile("transition=([^\\s|]+)").matcher(line); 
                    if (m.find()) trans = m.group(1);

                    String varName = "path" + pathCount;
                    code.append("    ArrayList<CurvePoint> ").append(varName).append(" = new ArrayList<>();\n");
                    
                    int j = i + 1;
                    while (j < lines.length && (lines[j].trim().startsWith("P:") || lines[j].trim().isEmpty())) {
                        String pLine = lines[j].trim();
                        if (pLine.startsWith("P:")) {
                            String[] p = pLine.substring(2).split(",");
                            if (p.length < 7) { j++; continue; }
                            
                            double px = Double.parseDouble(p[0].trim());
                            double py = Double.parseDouble(p[1].trim());
                            double ms = Double.parseDouble(p[2].trim());
                            double ts = Double.parseDouble(p[3].trim());
                            double fd = Double.parseDouble(p[4].trim());
                            double slowTurnDeg = Double.parseDouble(p[5].trim());
                            double sa = Double.parseDouble(p[6].trim());

                            String yExpr = formatYForJava(py, allianceMultiplier);
                            
                            code.append(String.format(Locale.US, "    %s.add(new CurvePoint(%.2f, %s, %.2f, %.2f, %.2f, Math.toRadians(%.1f), %.2f));\n",
                                    varName, px, yExpr, ms, ts, fd, slowTurnDeg, sa));
                        }
                        j++;
                    }
                    double hPathField = Double.parseDouble(hExpr.trim());
                    String hPathExpr = formatFAForJava(hPathField, isRedAlliance);

                    code.append("    scheduler.add(new FollowPathCommand(").append(varName).append(", ").append(hPathExpr).append(", debug)\n");
                    if ("IMMEDIATE".equals(trans)) code.append("            .transitionImmediately()\n");
                    else if (trans.startsWith("DIST")) {
                        String dist = trans.substring(5, trans.length()-1);
                        code.append("            .transitionWhenDistancetoEndIsLessThan(").append(dist).append(")\n");
                    }
                    code.append("            .withName(\"").append(name).append("\"));\n\n");
                    pathCount++;
                } else if (line.startsWith("WAIT:")) {
                    code.append("    scheduler.add(new WaitCommand(").append(line.substring(5).trim()).append("));\n");
                } else if (line.startsWith("CMD:")) {
                    String content = line.substring(4).trim();
                    String className = content;
                    String args = "";
                    String stepName = "";
                    
                    if (content.contains("|")) {
                        String[] parts = content.split("\\|");
                        className = parts[0].trim();
                        for (int k = 1; k < parts.length; k++) {
                            String part = parts[k].trim();
                            if (part.startsWith("args=[")) args = part.substring(6, part.length()-1);
                            if (part.startsWith("name=\"")) stepName = part.substring(6, part.length()-1);
                        }
                    }
                    
                    // Special case for SetInitialPoseCommand: wrap the second and third arguments
                    if ("SetInitialPoseCommand".equals(className)) {
                        String[] argParts = splitArgsIgnoreParens(args);
                        if (argParts.length >= 3) {
                            double py = Double.parseDouble(argParts[1].trim());
                            double hField = Double.parseDouble(argParts[2].trim());
                            argParts[1] = formatYForJava(py, allianceMultiplier);
                            argParts[2] = formatHForJava(hField, isRedAlliance);
                            args = String.join(", ", argParts);
                        }
                    } else if ("AutoIntakeCommand".equals(className)) {
                        // Special case for AutoIntakeCommand (Husky Drive)
                        String[] argParts = splitArgsIgnoreParens(args);
                        if (argParts.length >= 1) {
                            try {
                                double hField = Double.parseDouble(argParts[0].trim());
                                argParts[0] = formatHForJava(hField, isRedAlliance);
                                args = String.join(", ", argParts);
                            } catch (Exception ignored) {}
                        }
                    }
                    
                    code.append("    scheduler.add(new ").append(className).append("(").append(args).append(")");
                    if (!stepName.isEmpty()) code.append(".withName(\"").append(stepName).append("\")");
                    code.append(");\n");
                }
            }
            
            code.append("}\n");

            showCodePopup(code.toString());
        } catch (Exception e) {
            instructionLabel.setText("Export failed! Check mission script syntax.");
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Export Error");
            alert.setHeaderText("Failed to generate Java code");
            alert.setContentText("There is likely a syntax error in your mission script.\n\nError details: " + e.getMessage());
            alert.showAndWait();
            e.printStackTrace();
        }
    }

    private void handleSendMissionToRobot() {
        String script = controlPanel.getMissionScript();
        if (script == null || script.trim().isEmpty()) {
            instructionLabel.setText("No mission script to send.");
            return;
        }

        String robotIpAddress = controlPanel.getSelectedIpAddress();
        if (robotIpAddress == null || robotIpAddress.trim().isEmpty()) {
            instructionLabel.setText("No Robot IP Address selected!");
            return;
        }

        instructionLabel.setText("Sending full mission script to robot...");
        try (DatagramSocket socket = new DatagramSocket()) {
            InetAddress address = InetAddress.getByName(robotIpAddress);

            // Start signal
            byte[] startBuffer = "MISSION_START".getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(startBuffer, startBuffer.length, address, ROBOT_LISTENER_PORT));

            byte[] scriptBuffer = script.getBytes(StandardCharsets.UTF_8);
            int offset = 0;
            while (offset < scriptBuffer.length) {
                int length = Math.min(scriptBuffer.length - offset, 512);
                socket.send(new DatagramPacket(scriptBuffer, offset, length, address, ROBOT_LISTENER_PORT));
                offset += length;
                try { Thread.sleep(10); } catch (InterruptedException ignored) {} 
            }

            // End signal
            byte[] endBuffer = "MISSION_END".getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(endBuffer, endBuffer.length, address, ROBOT_LISTENER_PORT));
            
            instructionLabel.setText("Mission sent successfully to " + robotIpAddress);
        } catch (IOException e) {
            instructionLabel.setText("Error sending mission: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void setupParameterFieldListeners() {
        if (controlPanel == null) return;

        controlPanel.getFollowAngleField().focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (!newVal) {
                handleFollowAngleFieldFocusLost();
            }
        });
        controlPanel.getFollowAngleField().setOnAction(event -> handleFollowAngleFieldFocusLost());

        for (TextField tf : controlPanel.getAllParamTextFields()) {
            tf.focusedProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal) {
                    textFieldPreviousValues.put(tf, tf.getText());
                } else {
                    handleParameterFieldFocusLost(tf);
                }
            });
            tf.setOnAction(event -> handleParameterFieldFocusLost(tf));
        }

        for (TextField tf : controlPanel.getRobotStartTextFields()) {
            tf.focusedProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal) {
                    textFieldPreviousValues.put(tf, tf.getText());
                } else {
                    handleRobotStartFieldFocusLost();
                }
            });
            tf.setOnAction(event -> handleRobotStartFieldFocusLost());
        }
    }

    private void handleFollowAngleFieldFocusLost() {
        if (selectedPath == null) return;
        String text = controlPanel.getFollowAngleField().getText().trim();
        if (!text.isEmpty()) {
            selectedPath.followAngle = text;
            instructionLabel.setText("Follow angle updated for " + selectedPath.name);
            updateRobotHeadingIfAtWaypoint(selectedPath);
            updateMissionScriptFromState();
        } else {
            controlPanel.getFollowAngleField().setText(selectedPath.followAngle);
        }
    }

    private void updateRobotHeadingIfAtWaypoint(PathData path) {
        if (path == null || path.points.isEmpty()) return;

        double robotX = robot.getXInches();
        double robotY = robot.getYInches();

        for (int i = 0; i < path.points.size(); i++) {
            CurvePoint cp = path.points.get(i);
            if (Math.abs(cp.x - robotX) < 1e-2 && Math.abs(cp.y - robotY) < 1e-2) {
                double pathDir = getPathDirectionAtPoint(path, i);
                double followAngleValue = parseHeadingExpression(path.followAngle, isRedAlliance);
                double targetHeading = pathDir + (followAngleValue - 90.0);
                robot.setHeading(targetHeading);
                updateUIFromRobotState();
                break;
            }
        }
    }

    private double getPathDirectionAtPoint(PathData path, int pointIndex) {
        if (path.points.size() < 2) return robot.getHeadingDegrees();

        CurvePoint p1, p2;
        if (pointIndex < path.points.size() - 1) {
            p1 = path.points.get(pointIndex);
            p2 = path.points.get(pointIndex + 1);
        } else {
            p1 = path.points.get(pointIndex - 1);
            p2 = path.points.get(pointIndex);
        }

        double dx = p2.x - p1.x;
        double dy = p2.y - p1.y;
        return Math.toDegrees(Math.atan2(dy, dx));
    }

    private void handleRobotStartFieldFocusLost() {
        if (controlPanel == null || robot == null) return;

        try {
            double newX = Double.parseDouble(controlPanel.getStartXField().getText());
            double newY = Double.parseDouble(controlPanel.getStartYField().getText());
            double newHeading = Double.parseDouble(controlPanel.getStartHeadingField().getText());

            robot.setPosition(newX, newY, newHeading);
            updateUIFromRobotState();

            if (!allPaths.isEmpty()) {
                PathData firstPath = allPaths.get(0);
                if (!firstPath.points.isEmpty()) {
                    CurvePoint firstPoint = firstPath.points.get(0);
                    firstPoint.x = newX;
                    firstPoint.y = newY;
                    fieldDisplay.drawCurrentState();
                    if (selectedPath == firstPath) {
                        controlPanel.updatePointSelectionComboBox(selectedPath.points, controlPanel.getSelectedPointFromComboBox());
                    }
                }
            }
            instructionLabel.setText("Robot start position updated.");
            updateMissionScriptFromState();

        } catch (NumberFormatException e) {
            instructionLabel.setText("Invalid start position. Reverting.");
            updateUIFromRobotState();
        }
    }

    private void handleParameterFieldFocusLost(TextField textField) {
        if (controlPanel == null || selectedPath == null) return;

        String previousText = textFieldPreviousValues.getOrDefault(textField, "");
        String currentText = textField.getText().trim();

        if (Objects.equals(previousText, currentText) && !previousText.equals(ControlPanel.TEXTFIELD_VARIES_TEXT)) {
            return;
        }
        if (currentText.equals(ControlPanel.TEXTFIELD_VARIES_TEXT)) {
            textFieldPreviousValues.put(textField, currentText);
            return;
        }

        Object selectedItem = controlPanel.getSelectedPointFromComboBox();
        double parsedValue;

        try {
            if (currentText.isEmpty()) {
                if (Objects.equals(selectedItem, ControlPanel.ALL_POINTS_MARKER) && previousText.equals(ControlPanel.TEXTFIELD_VARIES_TEXT)) {
                    textFieldPreviousValues.put(textField, currentText);
                    return;
                }
                throw new NumberFormatException("Parameter cannot be empty.");
            }
            parsedValue = Double.parseDouble(currentText);

            if (textField == controlPanel.getMoveSpeedField() && parsedValue <= 0) throw new NumberFormatException("Move speed must be > 0");
            if (textField == controlPanel.getTurnSpeedField() && parsedValue <= 0) throw new NumberFormatException("Turn speed must be > 0");
            if (textField == controlPanel.getFollowDistanceField() && parsedValue < 0) throw new NumberFormatException("Follow distance must be >= 0");
            if (textField == controlPanel.getSlowDownTurnAmountField() && (parsedValue < 0 || parsedValue > 1)) throw new NumberFormatException("Slow down amount must be 0.0-1.0");

        } catch (NumberFormatException e) {
            instructionLabel.setText("Invalid input: " + e.getMessage() + ". Reverting.");
            textField.setText(previousText);
            textFieldPreviousValues.put(textField, previousText);
            return;
        }

        boolean updateOccurred = false;
        if (Objects.equals(selectedItem, ControlPanel.ALL_POINTS_MARKER)) {
            for (CurvePoint point : selectedPath.points) {
                updateCurvePointParameter(point, textField, parsedValue);
            }
            instructionLabel.setText("Applied '" + getFieldName(textField) + " = " + currentText + "' to all points of " + selectedPath.name + ".");
            updateOccurred = true;
            refreshParameterFieldsForAllSelected();
        } else if (selectedItem instanceof CurvePoint) {
            CurvePoint point = (CurvePoint) selectedItem;
            updateCurvePointParameter(point, textField, parsedValue);
            int pointIndex = selectedPath.points.indexOf(point) + 1;
            instructionLabel.setText("Updated Point " + pointIndex + " (" + getFieldName(textField) + " = " + currentText + ").");
            updateOccurred = true;
        }

        if (updateOccurred) {
            textFieldPreviousValues.put(textField, currentText);
            fieldDisplay.drawCurrentState();
            updateMissionScriptFromState();
        }
    }

    private void updateCurvePointParameter(CurvePoint point, TextField changedField, double value) {
        if (changedField == controlPanel.getMoveSpeedField()) point.moveSpeed = value;
        else if (changedField == controlPanel.getTurnSpeedField()) point.turnSpeed = value;
        else if (changedField == controlPanel.getFollowDistanceField()) point.followDistance = value;
        else if (changedField == controlPanel.getSlowDownTurnDegreesField()) point.slowDownTurnRadians = Math.toRadians(value);
        else if (changedField == controlPanel.getSlowDownTurnAmountField()) point.slowDownTurnAmount = value;
    }

    private String getFieldName(TextField textField) {
        if (textField == controlPanel.getMoveSpeedField()) return "Move Speed";
        if (textField == controlPanel.getTurnSpeedField()) return "Turn Speed";
        if (textField == controlPanel.getFollowDistanceField()) return "Follow Distance";
        if (textField == controlPanel.getSlowDownTurnDegreesField()) return "Slow Turn Deg";
        if (textField == controlPanel.getSlowDownTurnAmountField()) return "Slow Turn Amt";
        return "Parameter";
    }

    private void handlePointSelectionChanged(ObservableValue<? extends Object> obs, Object oldVal, Object newVal) {
        if (controlPanel == null || selectedPath == null) return;
        
        if (isCreatingPath) {
            // Ignore selection changes from ComboBox while creating a path
            return;
        }

        CurvePoint pointToHighlight = null;

        if (newVal == null) {
            if (selectedPath.points.isEmpty()) {
                controlPanel.loadGlobalDefaultsIntoParameterFields();
                controlPanel.setPointEditingControlsDisabled(true);
            } else {
                controlPanel.updatePointSelectionComboBox(selectedPath.points, ControlPanel.ALL_POINTS_MARKER);
            }
            return;
        } else if (Objects.equals(newVal, ControlPanel.ALL_POINTS_MARKER)) {
            refreshParameterFieldsForAllSelected();
        } else if (newVal instanceof CurvePoint) {
            CurvePoint selectedCurvePoint = (CurvePoint) newVal;
            controlPanel.loadParametersForPoint(selectedCurvePoint);
            pointToHighlight = selectedCurvePoint;
            
            // Move robot to the selected point
            int pointIndex = selectedPath.points.indexOf(selectedCurvePoint);
            snapRobotToPoint(selectedPath, pointIndex);
        }

        if (fieldDisplay != null) {
            fieldDisplay.setHighlightedPoint(pointToHighlight);
            fieldDisplay.drawCurrentState();
        }
    }

    private void refreshParameterFieldsForAllSelected() {
        if (controlPanel == null || selectedPath == null) return;
        if (selectedPath.points.isEmpty()) {
            controlPanel.loadGlobalDefaultsIntoParameterFields();
            return;
        }

        checkAndSetField(selectedPath.points, cp -> cp.moveSpeed, controlPanel.getMoveSpeedField(), "%.2f");
        checkAndSetField(selectedPath.points, cp -> cp.turnSpeed, controlPanel.getTurnSpeedField(), "%.2f");
        checkAndSetField(selectedPath.points, cp -> cp.followDistance, controlPanel.getFollowDistanceField(), "%.1f");
        checkAndSetField(selectedPath.points, cp -> Math.toDegrees(cp.slowDownTurnRadians), controlPanel.getSlowDownTurnDegreesField(), "%.1f");
        checkAndSetField(selectedPath.points, cp -> cp.slowDownTurnAmount, controlPanel.getSlowDownTurnAmountField(), "%.2f");
    }

    private <T> void checkAndSetField(List<CurvePoint> path, Function<CurvePoint, T> getter, TextField field, String format) {
        if (path.isEmpty()) {
            loadSpecificGlobalDefault(field);
            return;
        }

        T firstValue = getter.apply(path.get(0));
        boolean allSame = true;
        for (int i = 1; i < path.size(); i++) {
            T currentValue = getter.apply(path.get(i));
            if (currentValue instanceof Double && firstValue instanceof Double) {
                if (Math.abs((Double) currentValue - (Double) firstValue) > 0.0001) {
                    allSame = false;
                    break;
                }
            } else if (!Objects.equals(currentValue, firstValue)) {
                allSame = false;
                break;
            }
        }

        if (allSame) {
            if (firstValue instanceof Double) {
                field.setText(String.format(Locale.US, format, (Double) firstValue));
            } else {
                field.setText(firstValue.toString());
            }
        } else {
            field.setText(ControlPanel.TEXTFIELD_VARIES_TEXT);
        }
        textFieldPreviousValues.put(field, field.getText());
    }

    private void loadSpecificGlobalDefault(TextField field) {
        if (field == controlPanel.getMoveSpeedField()) field.setText(ControlPanel.DEFAULT_MOVE_SPEED);
        else if (field == controlPanel.getTurnSpeedField()) field.setText(ControlPanel.DEFAULT_TURN_SPEED);
        else if (field == controlPanel.getFollowDistanceField()) field.setText(ControlPanel.DEFAULT_FOLLOW_DISTANCE);
        else if (field == controlPanel.getSlowDownTurnDegreesField()) field.setText(ControlPanel.DEFAULT_SLOW_DOWN_TURN_DEGREES);
        else if (field == controlPanel.getSlowDownTurnAmountField()) field.setText(ControlPanel.DEFAULT_SLOW_DOWN_TURN_AMOUNT);
        else field.setText("");
        textFieldPreviousValues.put(field, field.getText());
    }

    private void updateControlPanelForPathState() {
        if (controlPanel == null) return;

        boolean pathsExist = !allPaths.isEmpty();
        boolean missionExists = missionSteps.stream().anyMatch(s -> 
            s.type != AutonomousStep.Type.EMPTY && s.type != AutonomousStep.Type.COMMENT);

        controlPanel.updatePathSelectionComboBox(allPaths, selectedPath);

        boolean pathExistsAndNotEmpty = selectedPath != null && !selectedPath.points.isEmpty();
        controlPanel.setPointEditingControlsDisabled(!pathExistsAndNotEmpty);
        controlPanel.enablePathControls(pathsExist, missionExists);

        Object selectionToRestore = controlPanel.getSelectedPointFromComboBox();
        if (!pathExistsAndNotEmpty) {
            selectionToRestore = ControlPanel.ALL_POINTS_MARKER;
        } else {
            if (selectionToRestore instanceof CurvePoint && !selectedPath.points.contains(selectionToRestore)) {
                selectionToRestore = ControlPanel.ALL_POINTS_MARKER;
            } else if (selectionToRestore == null) {
                selectionToRestore = ControlPanel.ALL_POINTS_MARKER;
            }
        }
        controlPanel.updatePointSelectionComboBox(selectedPath != null ? selectedPath.points : new ArrayList<>(), selectionToRestore);

        Object currentSelectionAfterUpdate = controlPanel.getSelectedPointFromComboBox();
        if (currentSelectionAfterUpdate == null && pathExistsAndNotEmpty) {
            controlPanel.updatePointSelectionComboBox(selectedPath.points, ControlPanel.ALL_POINTS_MARKER);
        } else {
            if (Objects.equals(currentSelectionAfterUpdate, ControlPanel.ALL_POINTS_MARKER)) {
                refreshParameterFieldsForAllSelected();
            } else if (currentSelectionAfterUpdate instanceof CurvePoint) {
                controlPanel.loadParametersForPoint((CurvePoint) currentSelectionAfterUpdate);
            } else {
                controlPanel.loadGlobalDefaultsIntoParameterFields();
            }
        }
        if (isCreatingPath) {
            controlPanel.setPointEditingControlsDisabled(true);
        }

        if (selectedPath != null) {
            controlPanel.getFollowAngleField().setText(selectedPath.followAngle);
        } else {
            controlPanel.getFollowAngleField().setText(ControlPanel.DEFAULT_FOLLOW_ANGLE);
        }
    }

    private void exportPathToCode() {
        exportMissionToCode();
    }

    private void showCodePopup(String code) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Exported Java Code");
        alert.setHeaderText("Copy the code below. It uses y(), h() and fa() for alliance mirroring.");
        alert.setResizable(true);

        TextArea textArea = new TextArea(code);
        textArea.setEditable(false);
        textArea.setWrapText(false);
        textArea.setFont(Font.font("Consolas", 14));

        textArea.setPrefHeight(400);
        textArea.setPrefWidth(650);

        VBox content = new VBox(textArea);
        VBox.setVgrow(textArea, Priority.ALWAYS);
        alert.getDialogPane().setContent(content);
        alert.getDialogPane().setPrefSize(650, 480);

        alert.showAndWait();
    }

    private void deleteCurrentPath() {
        if (selectedPath == null) return;
        allPaths.remove(selectedPath);
        if (!allPaths.isEmpty()) {
            selectedPath = allPaths.get(allPaths.size() - 1);
        } else {
            selectedPath = null;
        }
        isCreatingPath = false;
        fieldDisplay.setPathCreationMode(false, null, () -> finishPathCreation(false));
        fieldDisplay.setPathsToDraw(allPaths, selectedPath);
        fieldDisplay.drawCurrentState();
        fieldDisplay.setHighlightedPoint(null);
        instructionLabel.setText("Path deleted. Select another or click 'New Path'.");
        updateControlPanelForPathState();
        updateMissionScriptFromState();
    }

    private void finishPathCreation(boolean cancelled) {
        if (!isCreatingPath || selectedPath == null) return;
        
        // Change state BEFORE calling updateControlPanelForPathState to avoid guard loops
        isCreatingPath = false;
        fieldDisplay.setPathCreationMode(false, null, null);

        if (cancelled && selectedPath.points.isEmpty()) {
            allPaths.remove(selectedPath);
            selectedPath = allPaths.isEmpty() ? null : allPaths.get(allPaths.size() - 1);
            instructionLabel.setText("Path creation cancelled.");
        } else {
            if (selectedPath.points.isEmpty()) {
                instructionLabel.setText("Path finished with no points.");
                allPaths.remove(selectedPath);
                selectedPath = allPaths.isEmpty() ? null : allPaths.get(allPaths.size() - 1);
            } else {
                instructionLabel.setText("Path '" + selectedPath.name + "' finished with " + selectedPath.points.size() + " points.");
            }
        }

        if (controlPanel != null) {
            controlPanel.setPathEditingActive(false);
        }

        updateControlPanelForPathState();
        updateMissionScriptFromState();

        if (fieldDisplay != null) {
            fieldDisplay.setPathsToDraw(allPaths, selectedPath);
            fieldDisplay.drawCurrentState();
        }
    }

    private void handleFieldClickForPath(Point2D pixelCoords) {
        if (!isCreatingPath || controlPanel == null || selectedPath == null) return;

        Point2D inchesCoordsFieldCenter = fieldDisplay.pixelToInches(pixelCoords.getX(), pixelCoords.getY());
        double fieldX = inchesCoordsFieldCenter.getX();
        double fieldY = inchesCoordsFieldCenter.getY();

        // Chaining logic: ensure path sequence continuity
        if (selectedPath.points.isEmpty()) {
            int pathIndex = allPaths.indexOf(selectedPath);
            if (pathIndex > 0) {
                PathData prevPath = allPaths.get(pathIndex - 1);
                if (!prevPath.points.isEmpty()) {
                    CurvePoint lastPointOfPrev = prevPath.points.getLast();
                    // The first point of a new path is always the last point of the previous path
                    CurvePoint firstPoint = new CurvePoint(lastPointOfPrev);
                    selectedPath.points.add(firstPoint);
                }
            } else {
                // First path in the sequence starts at the robot's initial position
                double startHeading = 0.0;
                robot.setPosition(fieldX, fieldY, startHeading);
                updateUIFromRobotState();
            }
        }

        double moveSpeed, turnSpeed, followDistance, slowDownTurnDeg, slowDownTurnAmount, slowDownTurnRad;
        try {
            moveSpeed = controlPanel.getMoveSpeedParam();
            turnSpeed = controlPanel.getTurnSpeedParam();
            followDistance = controlPanel.getFollowDistanceParam();
            slowDownTurnDeg = controlPanel.getSlowDownTurnDegreesParam();
            slowDownTurnAmount = controlPanel.getSlowDownTurnAmountParam();
            if (moveSpeed <= 0 || turnSpeed <= 0 || followDistance < 0 || slowDownTurnAmount < 0 || slowDownTurnAmount > 1) {
                throw new NumberFormatException("Default parameter out of range.");
            }
            slowDownTurnRad = Math.toRadians(slowDownTurnDeg);
        } catch (NumberFormatException e) {
            moveSpeed = MASTER_DEFAULT_MOVE_SPEED;
            turnSpeed = MASTER_DEFAULT_TURN_SPEED;
            followDistance = MASTER_DEFAULT_FOLLOW_DISTANCE;
            slowDownTurnRad = MASTER_DEFAULT_SLOW_DOWN_TURN_RADIANS;
            slowDownTurnAmount = MASTER_DEFAULT_SLOW_DOWN_TURN_AMOUNT;
        }

        CurvePoint newPoint = new CurvePoint(fieldX, fieldY, moveSpeed, turnSpeed, followDistance, slowDownTurnRad, slowDownTurnAmount);
        selectedPath.points.add(newPoint);

        // Move robot to the newly added point
        snapRobotToPoint(selectedPath, selectedPath.points.size() - 1);
        
        // Refresh the display with current state
        fieldDisplay.setPathsToDraw(allPaths, selectedPath);
        fieldDisplay.drawCurrentState();
        updateMissionScriptFromState();

        instructionLabel.setText(String.format(Locale.US, "Added Point %d to %s. Click next, double-click to finish.", 
                selectedPath.points.size(), selectedPath.name));
    }

    private void updateTimeLapsedDisplay() {
        if (controlPanel == null || recordingManager == null) return;
        long timeLapsedMs = (recordingManager.getCurrentState() == RecordingManager.PlaybackState.RECORDING) ? recordingManager.getCurrentRecordingDuration() : recordingManager.getCurrentEventTimeLapsed();
        controlPanel.updateTimeLapsed(timeLapsedMs);
    }

    private void startNewPathCreation() {
        if (isCreatingPath) {
            finishPathCreation(false);
        }

        // Create new path object
        PathData newPath = new PathData("Path " + (allPaths.size() + 1));
        allPaths.add(newPath);
        selectedPath = newPath;
        isCreatingPath = true;
        
        fieldDisplay.setHighlightedPoint(null);

        if (controlPanel != null) {
            controlPanel.setPathEditingActive(true);
        }

        // Update UI components
        updateControlPanelForPathState();
        updateMissionScriptFromState();
        
        instructionLabel.setText("Creating " + newPath.name + ". Click waypoints on the field.");
        if (fieldDisplay != null) {
            // Ensure display knows about the new selected path
            fieldDisplay.setPathsToDraw(allPaths, selectedPath);
            fieldDisplay.setPathCreationMode(true, this::handleFieldClickForPath, () -> finishPathCreation(false));
            fieldDisplay.drawCurrentState();
        }
    }

    private void setupRecordingControlActions() {
        controlPanel.setOnOpenAction(e -> handleOpenRecording());
        controlPanel.setOnSaveAction(e -> handleSaveRecording());
        controlPanel.setOnClearRecordingAction(e -> {
            if (recordingManager != null) {
                recordingManager.clearAll();
                controlPanel.setPlaybackControlsDisabled(true);
                controlPanel.updateTimelineSlider(0, 1);
                controlPanel.setSaveButtonDisabled(true);
                updateTimeLapsedDisplay();
                instructionLabel.setText("Recording and replay buffer cleared.");
            }
        });
        controlPanel.setOnRecordAction(() -> {
            RecordingManager.PlaybackState recordingState = recordingManager.getCurrentState();
            if (recordingState == RecordingManager.PlaybackState.RECORDING) {
                recordingManager.stopRecording();
                controlPanel.toggleRecordButtonIcon(false);
                if (recordingManager.hasRecording()) {
                    controlPanel.setPlaybackControlsDisabled(false);
                    controlPanel.updateTimelineSlider(recordingManager.getPlaybackIndex(), recordingManager.getTotalEvents());
                    controlPanel.setSaveButtonDisabled(false);
                } else {
                    controlPanel.setPlaybackControlsDisabled(true);
                    controlPanel.setSaveButtonDisabled(true);
                }
            } else {
                controlPanel.togglePlayPauseButtonIcon(false);
                recordingManager.startRecording();
                controlPanel.toggleRecordButtonIcon(true);
                controlPanel.setPlaybackControlsDisabled(true);
                controlPanel.setSaveButtonDisabled(true);
            }
            updateTimeLapsedDisplay();
        });
        controlPanel.setOnPlayPauseAction(() -> {
            RecordingManager.PlaybackState playbackState = recordingManager.getCurrentState();
            if (playbackState == RecordingManager.PlaybackState.PLAYING) {
                recordingManager.pause();
                controlPanel.togglePlayPauseButtonIcon(false);
            } else if (playbackState == RecordingManager.PlaybackState.IDLE || playbackState == RecordingManager.PlaybackState.PAUSED) {
                if (recordingManager.hasRecording()) {
                    recordingManager.play();
                    controlPanel.togglePlayPauseButtonIcon(true);
                }
            }
            updateTimeLapsedDisplay();
        });
        controlPanel.setOnForwardAction(() -> {
            if (recordingManager.hasRecording() && recordingManager.getCurrentState() != RecordingManager.PlaybackState.RECORDING) {
                recordingManager.stepForward();
                controlPanel.togglePlayPauseButtonIcon(false);
            }
            updateTimeLapsedDisplay();
        });
        controlPanel.setOnReverseAction(() -> {
            if (recordingManager.hasRecording() && recordingManager.getCurrentState() != RecordingManager.PlaybackState.RECORDING) {
                recordingManager.stepBackward();
                controlPanel.togglePlayPauseButtonIcon(false);
            }
            updateTimeLapsedDisplay();
        });
        controlPanel.setOnTimelineSliderChanged((observable, oldValue, newValue) -> {
            if (controlPanel.getTimelineSlider().isValueChanging() && recordingManager.getCurrentState() != RecordingManager.PlaybackState.PLAYING) {
                recordingManager.seekTo(newValue.intValue());
                controlPanel.togglePlayPauseButtonIcon(false);
                updateTimeLapsedDisplay();
            }
        });
        controlPanel.setOnSliderMouseReleased(event -> {
            if (recordingManager.getCurrentState() != RecordingManager.PlaybackState.PLAYING &&
                    recordingManager.getCurrentState() != RecordingManager.PlaybackState.RECORDING) {
                int sliderRawValue = (int) controlPanel.getTimelineSlider().getValue();
                recordingManager.seekTo(sliderRawValue);
                controlPanel.togglePlayPauseButtonIcon(false);
            }
        });

        controlPanel.setOnInstantReplayAction(e -> {
            recordingManager.loadFromLiveBuffer();
            controlPanel.setReplayMode(true);
            if (recordingManager.hasRecording()) {
                controlPanel.updateTimelineSlider(0, recordingManager.getTotalEvents());
                recordingManager.seekTo(0);
                instructionLabel.setText("Reviewing last 10 minutes. Use timeline to scrub.");
            } else {
                controlPanel.updateTimelineSlider(0, 1);
                instructionLabel.setText("Live buffer is empty. No replay available.");
            }
            updateTimeLapsedDisplay();
        });

        controlPanel.setOnReturnToLiveAction(e -> {
            recordingManager.stopPlayback();
            recordingManager.loadRecording(new ArrayList<>());
            controlPanel.setReplayMode(false);
            fieldDisplay.clearTrail();
            clearAllNamedLines();
            fieldDisplay.drawCurrentState();
            updateTimeLapsedDisplay();
            instructionLabel.setText("Returned to live view.");
        });

    }

    private void handleSaveRecording() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save Recording");
        fileChooser.setInitialFileName("recording.rec");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Recording Files", "*.rec"));
        File file = fileChooser.showSaveDialog(primaryStage);
        if (file == null) { instructionLabel.setText("Save cancelled."); return; }
        ArrayList<RecordingManager.RecordedEvent> events = recordingManager.getRecordedSession();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            for (RecordingManager.RecordedEvent event : events) {
                String line = formatEventToString(event);
                if (line != null) {
                    writer.write(line);
                    writer.newLine();
                }
            }
            instructionLabel.setText("Recording saved: " + file.getName());
        } catch (IOException e) {
            instructionLabel.setText("Error saving recording: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String formatEventToString(RecordingManager.RecordedEvent event) {
        String payload; UdpMessageData data = event.messageData;
        if (data instanceof PositionData) { PositionData d = (PositionData) data; payload = String.format(Locale.US,"pos:%.3f,%.3f,%.3f", d.x, d.y, d.heading); }
        else if (data instanceof CircleData) { CircleData d = (CircleData) data; payload = String.format(Locale.US,"cir:%.3f,%.3f", d.radiusInches, d.heading); }
        else if (data instanceof LineData) { LineData d = (LineData) data; payload = String.format(Locale.US,"line:%s,%.3f,%.3f,%.3f,%.3f,%d", d.name, d.x1, d.y1, d.x2, d.y2, d.styleCode); }
        else if (data instanceof TextData) { TextData d = (TextData) data; payload = "txt:" + d.text; }
        else if (data instanceof KeyValueData) { KeyValueData kv = (KeyValueData) data; payload = String.format("kv:%s,%s", kv.key, kv.value); }
        else { return null; }
        return event.timestamp + "|" + payload;
    }

    private void handleOpenRecording() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Open Recording");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Recording Files", "*.rec"));
        File file = fileChooser.showOpenDialog(primaryStage);
        if (file == null) { instructionLabel.setText("Open cancelled."); return; }
        ArrayList<RecordingManager.RecordedEvent> loadedEvents = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] lineParts = line.split("\\|", 2);
                if (lineParts.length != 2) continue;
                long timestamp = Long.parseLong(lineParts[0]); String payload = lineParts[1];
                UdpMessageData parsedData = null;
                if (payload.startsWith("pos:")) { String c = payload.substring(4); String[] p = c.split(","); if (p.length == 3) parsedData = new PositionData(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2])); }
                else if (payload.startsWith("cir:")) { String c = payload.substring(4); String[] p = c.split(","); if (p.length == 2) parsedData = new CircleData(Double.parseDouble(p[0]), Double.parseDouble(p[1])); }
                else if (payload.startsWith("line:")) { String c = payload.substring(5); String[] p = c.split(",", 6); if (p.length == 6) parsedData = new LineData(p[0], Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]), Double.parseDouble(p[4]), Integer.parseInt(p[5]));}
                else if (payload.startsWith("txt:")) { parsedData = new TextData(payload.substring(4));}
                else if (payload.startsWith("kv:")) { String c = payload.substring(3); String[] p = c.split(",", 2); if (p.length == 2) parsedData = new KeyValueData(p[0], p[1]); }
                if (parsedData != null) loadedEvents.add(new RecordingManager.RecordedEvent(timestamp, parsedData));
            }
            recordingManager.loadRecording(loadedEvents);
            controlPanel.setPlaybackControlsDisabled(loadedEvents.isEmpty());
            controlPanel.updateTimelineSlider(0, recordingManager.getTotalEvents());
            controlPanel.setSaveButtonDisabled(loadedEvents.isEmpty());
            controlPanel.togglePlayPauseButtonIcon(false);
            instructionLabel.setText("Opened: " + file.getName());
            updateTimeLapsedDisplay();
        } catch (Exception e) {
            instructionLabel.setText("Error opening recording: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void handleUdpMessage(UdpMessageData messageData) {
        if (messageData == null) return;
        recordingManager.addLiveEvent(messageData);
        if (recordingManager.getCurrentState() == RecordingManager.PlaybackState.RECORDING) {
            recordingManager.addEvent(messageData);
        }
        RecordingManager.PlaybackState state = recordingManager.getCurrentState();
        if (state != RecordingManager.PlaybackState.PLAYING && state != RecordingManager.PlaybackState.PAUSED) {
            Platform.runLater(() -> processUdpDataAndUpdateUI(messageData));
        }
    }

    private void processUdpDataAndUpdateUI(UdpMessageData messageData) {
        if (messageData == null) return;
        if (messageData instanceof PositionData) {
            PositionData p = (PositionData) messageData;
            if (robot != null) {
                fieldDisplay.addTrailDot(robot.getXInches(), robot.getYInches());
                robot.setPosition(p.x, p.y, p.heading);
            }
        } else if (messageData instanceof CircleData) {
            CircleData c = (CircleData) messageData;
            if (fieldDisplay != null) {
                fieldDisplay.addDebugCircle(robot.getXInches(), robot.getYInches(), c.radiusInches, c.heading, Color.rgb(255, 165, 0, 0.7));
            }
        } else if (messageData instanceof LineData) {
            LineData l = (LineData) messageData;
            synchronized (namedLinesLock) {
                namedLinesToDraw.put(l.name, l);
            }
        } else if (messageData instanceof TextData) {
            TextData t = (TextData) messageData;
            if (fieldDisplay != null) {
                fieldDisplay.setRobotTextMessage(t.text);
            }
        } else if (messageData instanceof KeyValueData) {
            KeyValueData kv = (KeyValueData) messageData;
            if (keyValueTable != null) {
                keyValueTable.updateValue(kv.key, kv.value);
            }
        }
        if (messageData instanceof PositionData) {
            updateUIFromRobotState();
        } else {
            fieldDisplay.drawCurrentState();
        }
    }

    private void onPlaybackFinished() {
        Platform.runLater(() -> {
            if (controlPanel != null) {
                controlPanel.togglePlayPauseButtonIcon(false);
            }
            updateTimeLapsedDisplay();
        });
    }

    private void startUdpPositionListener() {
        try {
            udpListener = new UdpPositionListener(UDP_LISTENER_PORT, this::handleUdpMessage);
            udpListenerThread = new Thread(udpListener, "UdpListenerThread");
            udpListenerThread.setDaemon(true);
            udpListenerThread.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startUdpPlotListener() {
        try {
            udpPlotListener = new UdpPlotListener(this::handleUdpPlotData);
            udpPlotListenerThread = new Thread(udpPlotListener, "UdpPlotListenerThread");
            udpPlotListenerThread.setDaemon(true);
            udpPlotListenerThread.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void stopApp() {
        if (udpListener != null) udpListener.stopListener();
        if (udpPlotListener != null) udpPlotListener.stopListener();
        if (udpListenerThread != null && udpListenerThread.isAlive()) {
            try { udpListenerThread.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (udpPlotListenerThread != null && udpPlotListenerThread.isAlive()) {
            try { udpPlotListenerThread.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (plotDisplayWindow != null && plotDisplayWindow.isShowing()){
            plotDisplayWindow.hide();
        }
        Platform.exit();
        System.exit(0);
    }

    private void clearAllNamedLines() {
        synchronized (namedLinesLock) {
            namedLinesToDraw.clear();
        }
        if (fieldDisplay != null) {
            fieldDisplay.drawCurrentState();
        }
    }

    private void handleSceneKeyPress(KeyEvent event) {
        if (isCreatingPath) {
            if (event.getCode() == KeyCode.ESCAPE) {
                finishPathCreation(true);
                event.consume();
            }
        } else {
            handleRobotMovementKeyPress(event);
        }
    }

    private void handleRobotMovementKeyPress(KeyEvent event) {
        if (robot == null || isCreatingPath) return;
        double currentX = robot.getXInches();
        double currentY = robot.getYInches();
        double currentHeading_CCW = robot.getHeadingDegrees();
        boolean moved = false;
        double newFieldX = currentX;
        double newFieldY = currentY;
        double angleRad_CCW = Math.toRadians(currentHeading_CCW);
        switch (event.getCode()) {
            case UP:
                newFieldX = currentX + ROBOT_MOVE_INCREMENT_INCHES * Math.cos(angleRad_CCW);
                newFieldY = currentY + ROBOT_MOVE_INCREMENT_INCHES * Math.sin(angleRad_CCW);
                moved = true;
                break;
            case DOWN:
                newFieldX = currentX - ROBOT_MOVE_INCREMENT_INCHES * Math.cos(angleRad_CCW);
                newFieldY = currentY - ROBOT_MOVE_INCREMENT_INCHES * Math.sin(angleRad_CCW);
                moved = true;
                break;
            case LEFT:
                robot.setHeading(currentHeading_CCW + ROBOT_TURN_INCREMENT_DEGREES);
                moved = true;
                break;
            case RIGHT:
                robot.setHeading(currentHeading_CCW - ROBOT_TURN_INCREMENT_DEGREES);
                moved = true;
                break;
            case A:
                double strafeLeftAngleRad_CCW = Math.toRadians(currentHeading_CCW + 90.0);
                newFieldX = currentX + ROBOT_MOVE_INCREMENT_INCHES * Math.cos(strafeLeftAngleRad_CCW);
                newFieldY = currentY + ROBOT_MOVE_INCREMENT_INCHES * Math.sin(strafeLeftAngleRad_CCW);
                moved = true;
                break;
            case D:
                double strafeRightAngleRad_CCW = Math.toRadians(currentHeading_CCW - 90.0);
                newFieldX = currentX + ROBOT_MOVE_INCREMENT_INCHES * Math.cos(strafeRightAngleRad_CCW);
                newFieldY = currentY + ROBOT_MOVE_INCREMENT_INCHES * Math.sin(strafeRightAngleRad_CCW);
                moved = true;
                break;
            default: break;
        }
        if (moved) {
            if (event.getCode() == KeyCode.UP || event.getCode() == KeyCode.DOWN ||
                    event.getCode() == KeyCode.A || event.getCode() == KeyCode.D) {
                robot.setPosition(newFieldX, newFieldY);
            }
            updateUIFromRobotState();
            event.consume();
        }
    }
}
