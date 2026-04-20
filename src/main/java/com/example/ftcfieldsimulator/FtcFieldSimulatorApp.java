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
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.text.Font;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ComboBox;

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
    private List<CurvePoint> currentPath = new ArrayList<>();
    private boolean isCreatingPath = false;
    private Map<String, LineData> namedLinesToDraw = new HashMap<>();
    private final Object namedLinesLock = new Object();
    private Map<TextField, String> textFieldPreviousValues = new HashMap<>();
    private boolean isRedAlliance = false; // State to track current alliance view

    // --- Configuration Constants ---
    public static final double FIELD_WIDTH_INCHES = 144.0;
    public static final double FIELD_HEIGHT_INCHES = 144.0;
    private static final int FIELD_DISPLAY_WIDTH_PIXELS = 800;
    private static final int FIELD_DISPLAY_HEIGHT_PIXELS = 800;
    private static final String FIELD_IMAGE_PATH = "/decode_field.png";
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

        startUdpPositionListener();
        startUdpPlotListener();
        primaryStage.setScene(scene);
        primaryStage.setResizable(true);
        primaryStage.show();
        primaryStage.setOnCloseRequest(event -> stopApp());

        updateUIFromRobotState();
        updateControlPanelForPathState();
        updateTimeLapsedDisplay();
    }

    private void setupFieldDisplayKeyHandlers() {
        if (fieldDisplay == null) return;
        fieldDisplay.setOnPointDeleteAction(this::handleDeletePoint);
    }

    private void handleDeletePoint(CurvePoint pointToDelete) {
        if (pointToDelete == null || !currentPath.contains(pointToDelete)) return;

        int deletedIndex = currentPath.indexOf(pointToDelete);
        currentPath.remove(pointToDelete);

        if (deletedIndex == 0 && !currentPath.isEmpty()) {
            CurvePoint newFirstPoint = currentPath.get(0);
            robot.setPosition(newFirstPoint.x, newFirstPoint.y);
            controlPanel.updateRobotStartFields(newFirstPoint.x, newFirstPoint.y, robot.getHeadingDegrees());
        }

        fieldDisplay.setHighlightedPoint(null);
        fieldDisplay.setPathToDraw(currentPath);
        updateControlPanelForPathState();
        updateUIFromRobotState();

        instructionLabel.setText("Deleted Point " + (deletedIndex + 1) + ".");
    }

    private void setupFieldDisplayMouseHandlers() {
        if (fieldDisplay == null) return;
        fieldDisplay.setOnSegmentClick(this::handleInsertPoint);
        fieldDisplay.setOnPointDrag((index, newCoords) -> {
            if (index == 0) {
                controlPanel.updateRobotStartFields(newCoords.getX(), newCoords.getY(), robot.getHeadingDegrees());
                robot.setPosition(newCoords.getX(), newCoords.getY());
            }
            if (controlPanel.getSelectedPointFromComboBox() != currentPath.get(index)) {
                controlPanel.updatePointSelectionComboBox(currentPath, currentPath.get(index));
            }
            controlPanel.loadParametersForPoint(currentPath.get(index));
            instructionLabel.setText(String.format(Locale.US, "Dragging Point %d to (X:%.1f, Y:%.1f)",
                    index + 1, newCoords.getX(), newCoords.getY()));
        });

        fieldDisplay.setOnPointDragEnd(index -> {
            if (index >= 0 && index < currentPath.size()) {
                CurvePoint point = currentPath.get(index);
                instructionLabel.setText(String.format(Locale.US, "Moved Point %d.", index + 1));
                controlPanel.updatePointSelectionComboBox(currentPath, point);
            }
        });
    }

    private void handleInsertPoint(int segmentIndex, Point2D clickCoordsPixels) {
        if (segmentIndex < 0 || segmentIndex >= currentPath.size() - 1) return;

        Point2D clickCoordsInches = fieldDisplay.pixelToInches(clickCoordsPixels.getX(), clickCoordsPixels.getY());
        CurvePoint startPoint = currentPath.get(segmentIndex);
        CurvePoint endPoint = currentPath.get(segmentIndex + 1);

        double newMoveSpeed = (startPoint.moveSpeed + endPoint.moveSpeed) / 2.0;
        double newTurnSpeed = (startPoint.turnSpeed + endPoint.turnSpeed) / 2.0;
        double newFollowDistance = (startPoint.followDistance + endPoint.followDistance) / 2.0;
        double newSlowDownTurnRadians = (startPoint.slowDownTurnRadians + endPoint.slowDownTurnRadians) / 2.0;
        double newSlowDownTurnAmount = (startPoint.slowDownTurnAmount + endPoint.slowDownTurnAmount) / 2.0;

        CurvePoint newPoint = new CurvePoint(clickCoordsInches.getX(), clickCoordsInches.getY(), newMoveSpeed, newTurnSpeed, newFollowDistance, newSlowDownTurnRadians, newSlowDownTurnAmount);
        currentPath.add(segmentIndex + 1, newPoint);
        fieldDisplay.setPathToDraw(currentPath);
        fieldDisplay.setHighlightedPoint(newPoint);
        updateControlPanelForPathState();
        updateUIFromRobotState();

        instructionLabel.setText("Inserted new point " + (segmentIndex + 2) + ".");
    }

    private void updateUIFromRobotState() {
        if (robot != null && fieldDisplay != null) {
            double displayHeading = robot.getHeadingDegrees() % 360;
            if (displayHeading < 0) displayHeading += 360;

            if (fieldStatusDisplay != null) {
                fieldStatusDisplay.updateRobotStatus(robot.getXInches(), robot.getYInches(), displayHeading);
            }

            if (controlPanel != null && (
                    controlPanel.getStartXField().isFocused() ||
                            controlPanel.getStartYField().isFocused() ||
                            controlPanel.getStartHeadingField().isFocused()
            )) {
                controlPanel.updateRobotStartFields(robot.getXInches(), robot.getYInches(), displayHeading);
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
        controlPanel.setOnImportCodeAction(event -> showImportCodeDialog());
        controlPanel.setOnExportCodeAction(event -> exportPathToCode());
        controlPanel.setOnSendPathAction(event -> handleSendPathToRobot());
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

    private void showImportCodeDialog() {
        Dialog<ImportResult> dialog = new Dialog<>();
        dialog.setTitle("Import Path from Code");
        dialog.setHeaderText("Paste your Java code snippet below and select the alliance.");
        dialog.setResizable(true);

        TextArea textArea = new TextArea();
        textArea.setPromptText("pathToSpike1.add(new CurvePoint(...));");
        textArea.setFont(Font.font("Consolas", 14));
        textArea.setPrefHeight(300);
        textArea.setPrefWidth(650);

        ComboBox<String> allianceSelector = new ComboBox<>();
        allianceSelector.getItems().addAll("Blue Alliance", "Red Alliance");
        allianceSelector.setValue(isRedAlliance ? "Red Alliance" : "Blue Alliance");
        allianceSelector.setMaxWidth(Double.MAX_VALUE);

        VBox content = new VBox(10, new Label("Java Code Snippet:"), textArea, new Label("Alliance for this path:"), allianceSelector);
        VBox.setVgrow(textArea, Priority.ALWAYS);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefSize(650, 480);

        ButtonType importButtonType = new ButtonType("Import", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(importButtonType, ButtonType.CANCEL);

        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == importButtonType) {
                return new ImportResult(textArea.getText(), "Red Alliance".equals(allianceSelector.getValue()));
            }
            return null;
        });

        Optional<ImportResult> result = dialog.showAndWait();
        result.ifPresent(res -> parseAndImportPath(res.code, res.isRed));
    }

    private void parseAndImportPath(String code, boolean importedAsRed) {
        List<CurvePoint> newPath = new ArrayList<>();
        double startX = -1, startY = -1, startHeading = 0;
        boolean poseFound = false;

        this.isRedAlliance = importedAsRed;
        double allianceMultiplier = isRedAlliance ? -1.0 : 1.0;

        Pattern posePattern = Pattern.compile("new\\s+Pose2D\\([^,]+,\\s*([\\d\\.\\-]+),\\s*(?:y\\(([\\d\\.\\-]+)\\)|([\\d\\.\\-]+)),\\s*[^,]+,\\s*([\\d\\.\\-]+)\\)");
        Pattern curvePointPattern = Pattern.compile(
                "new\\s+CurvePoint\\(\\s*([\\d\\.\\-]+),\\s*(?:y\\(([\\d\\.\\-]+)\\)|([\\d\\.\\-]+)),\\s*([\\d\\.\\-]+)," +
                        "\\s*([\\d\\.\\-]+),\\s*([\\d\\.\\-]+),\\s*Math\\.toRadians\\(([\\d\\.\\-]+)\\)," +
                        "\\s*([\\d\\.\\-]+)\\)"
        );

        String[] lines = code.split("\\r?\\n");
        for (String line : lines) {
            if (!poseFound) {
                Matcher poseMatcher = posePattern.matcher(line);
                if (poseMatcher.find()) {
                    try {
                        startX = Double.parseDouble(poseMatcher.group(1));
                        String yGroupVal = poseMatcher.group(2);
                        if (yGroupVal != null) {
                            startY = Double.parseDouble(yGroupVal) * allianceMultiplier;
                        } else {
                            startY = Double.parseDouble(poseMatcher.group(3));
                        }
                        startHeading = Double.parseDouble(poseMatcher.group(4));
                        poseFound = true;
                    } catch (NumberFormatException e) {
                        System.err.println("Could not parse Pose2D line: " + line);
                    }
                }
            }

            Matcher curvePointMatcher = curvePointPattern.matcher(line);
            if (curvePointMatcher.find()) {
                try {
                    double x = Double.parseDouble(curvePointMatcher.group(1));
                    double y;
                    String yGroupVal = curvePointMatcher.group(2);
                    if (yGroupVal != null) {
                        y = Double.parseDouble(yGroupVal) * allianceMultiplier;
                    } else {
                        y = Double.parseDouble(curvePointMatcher.group(3));
                    }
                    double moveSpeed = Double.parseDouble(curvePointMatcher.group(4));
                    double turnSpeed = Double.parseDouble(curvePointMatcher.group(5));
                    double followDistance = Double.parseDouble(curvePointMatcher.group(6));
                    double slowDownTurnDeg = Double.parseDouble(curvePointMatcher.group(7));
                    double slowDownTurnAmount = Double.parseDouble(curvePointMatcher.group(8));

                    newPath.add(new CurvePoint(x, y, moveSpeed, turnSpeed, followDistance, Math.toRadians(slowDownTurnDeg), slowDownTurnAmount));
                } catch (NumberFormatException e) {
                    System.err.println("Could not parse CurvePoint line: " + line);
                }
            }
        }

        if (newPath.isEmpty()) {
            instructionLabel.setText("Import failed: No valid 'new CurvePoint(...)' lines found.");
            return;
        }

        if (!poseFound) {
            CurvePoint firstPoint = newPath.get(0);
            startX = firstPoint.x;
            startY = firstPoint.y;
            startHeading = 0.0;
        }

        this.currentPath = newPath;
        robot.setPosition(startX, startY, startHeading);
        controlPanel.updateRobotStartFields(startX, startY, startHeading);
        fieldDisplay.setPathToDraw(this.currentPath);
        isCreatingPath = false;
        controlPanel.setPathEditingActive(false);
        updateControlPanelForPathState();
        updateUIFromRobotState();

        instructionLabel.setText("Successfully imported " + newPath.size() + " points for " + (isRedAlliance ? "RED" : "BLUE") + ".");
    }

    private void setupParameterFieldListeners() {
        if (controlPanel == null) return;
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

    private void handleRobotStartFieldFocusLost() {
        if (controlPanel == null || robot == null) return;

        try {
            double newX = Double.parseDouble(controlPanel.getStartXField().getText());
            double newY = Double.parseDouble(controlPanel.getStartYField().getText());
            double newHeading = Double.parseDouble(controlPanel.getStartHeadingField().getText());

            robot.setPosition(newX, newY, newHeading);
            updateUIFromRobotState();

            if (!currentPath.isEmpty()) {
                CurvePoint firstPoint = currentPath.get(0);
                firstPoint.x = newX;
                firstPoint.y = newY;
                fieldDisplay.drawCurrentState();
                controlPanel.updatePointSelectionComboBox(currentPath, controlPanel.getSelectedPointFromComboBox());
            }
            instructionLabel.setText("Robot start position updated.");

        } catch (NumberFormatException e) {
            instructionLabel.setText("Invalid start position. Reverting.");
            updateUIFromRobotState();
        }
    }

    private void handleParameterFieldFocusLost(TextField textField) {
        if (controlPanel == null || currentPath == null) return;

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
            for (CurvePoint point : currentPath) {
                updateCurvePointParameter(point, textField, parsedValue);
            }
            instructionLabel.setText("Applied '" + getFieldName(textField) + " = " + currentText + "' to all points.");
            updateOccurred = true;
            refreshParameterFieldsForAllSelected();
        } else if (selectedItem instanceof CurvePoint) {
            CurvePoint point = (CurvePoint) selectedItem;
            updateCurvePointParameter(point, textField, parsedValue);
            int pointIndex = currentPath.indexOf(point) + 1;
            instructionLabel.setText("Updated Point " + pointIndex + " (" + getFieldName(textField) + " = " + currentText + ").");
            updateOccurred = true;
        }

        if (updateOccurred) {
            textFieldPreviousValues.put(textField, currentText);
            fieldDisplay.drawCurrentState();
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
        if (controlPanel == null || isCreatingPath) {
            if (isCreatingPath && newVal != null && controlPanel.getSelectedPointFromComboBox() != null) {
                Platform.runLater(() -> controlPanel.updatePointSelectionComboBox(currentPath, oldVal != null ? oldVal : ControlPanel.ALL_POINTS_MARKER));
            }
            return;
        }

        CurvePoint pointToHighlight = null;

        if (newVal == null) {
            if (currentPath.isEmpty()) {
                controlPanel.loadGlobalDefaultsIntoParameterFields();
                controlPanel.setPointEditingControlsDisabled(true);
            } else {
                controlPanel.updatePointSelectionComboBox(currentPath, ControlPanel.ALL_POINTS_MARKER);
            }
            return;
        } else if (Objects.equals(newVal, ControlPanel.ALL_POINTS_MARKER)) {
            refreshParameterFieldsForAllSelected();
        } else if (newVal instanceof CurvePoint) {
            CurvePoint selectedCurvePoint = (CurvePoint) newVal;
            controlPanel.loadParametersForPoint(selectedCurvePoint);
            pointToHighlight = selectedCurvePoint;
        }

        if (fieldDisplay != null) {
            fieldDisplay.setHighlightedPoint(pointToHighlight);
            fieldDisplay.drawCurrentState();
        }
    }

    private void refreshParameterFieldsForAllSelected() {
        if (controlPanel == null) return;
        if (currentPath.isEmpty()) {
            controlPanel.loadGlobalDefaultsIntoParameterFields();
            return;
        }

        checkAndSetField(currentPath, cp -> cp.moveSpeed, controlPanel.getMoveSpeedField(), "%.2f");
        checkAndSetField(currentPath, cp -> cp.turnSpeed, controlPanel.getTurnSpeedField(), "%.2f");
        checkAndSetField(currentPath, cp -> cp.followDistance, controlPanel.getFollowDistanceField(), "%.1f");
        checkAndSetField(currentPath, cp -> Math.toDegrees(cp.slowDownTurnRadians), controlPanel.getSlowDownTurnDegreesField(), "%.1f");
        checkAndSetField(currentPath, cp -> cp.slowDownTurnAmount, controlPanel.getSlowDownTurnAmountField(), "%.2f");
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

        boolean pathExistsAndNotEmpty = !currentPath.isEmpty();
        controlPanel.setPointEditingControlsDisabled(!pathExistsAndNotEmpty);
        controlPanel.enablePathControls(pathExistsAndNotEmpty);

        Object selectionToRestore = controlPanel.getSelectedPointFromComboBox();
        if (!pathExistsAndNotEmpty) {
            selectionToRestore = ControlPanel.ALL_POINTS_MARKER;
        } else {
            if (selectionToRestore instanceof CurvePoint && !currentPath.contains(selectionToRestore)) {
                selectionToRestore = ControlPanel.ALL_POINTS_MARKER;
            } else if (selectionToRestore == null) {
                selectionToRestore = ControlPanel.ALL_POINTS_MARKER;
            }
        }
        controlPanel.updatePointSelectionComboBox(currentPath, selectionToRestore);

        Object currentSelectionAfterUpdate = controlPanel.getSelectedPointFromComboBox();
        if (currentSelectionAfterUpdate == null && pathExistsAndNotEmpty) {
            controlPanel.updatePointSelectionComboBox(currentPath, ControlPanel.ALL_POINTS_MARKER);
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
    }

    private void handleSendPathToRobot() {
        if (currentPath.isEmpty()) {
            instructionLabel.setText("No path to send.");
            return;
        }

        String robotIpAddress = controlPanel.getSelectedIpAddress();
        if (robotIpAddress == null || robotIpAddress.trim().isEmpty()) {
            instructionLabel.setText("No Robot IP Address selected!");
            return;
        }

        instructionLabel.setText("Sending path to robot...");
        try (DatagramSocket socket = new DatagramSocket()) {
            InetAddress address = InetAddress.getByName(robotIpAddress);
            double followAngle;
            try {
                followAngle = Double.parseDouble(controlPanel.getFollowAngleField().getText());
                String followAngleMessage = String.format(Locale.US, "follow_angle:%.2f", followAngle);
                byte[] followAngleBuffer = followAngleMessage.getBytes(StandardCharsets.UTF_8);
                DatagramPacket followAnglePacket = new DatagramPacket(followAngleBuffer, followAngleBuffer.length, address, ROBOT_LISTENER_PORT);
                socket.send(followAnglePacket);
            } catch (NumberFormatException e) {
                instructionLabel.setText("Invalid Follow Angle! Sending Aborted.");
                return;
            }

            double startX, startY, startHeading;
            try {
                startX = Double.parseDouble(controlPanel.getStartXField().getText());
                startY = Double.parseDouble(controlPanel.getStartYField().getText());
                startHeading = Double.parseDouble(controlPanel.getStartHeadingField().getText());
            } catch (NumberFormatException e) {
                instructionLabel.setText("Invalid Start Position fields! Sending Aborted.");
                return;
            }

            String startPosMessage = String.format(Locale.US, "start_robot_pos:%.3f,%.3f,%.3f", startX, startY, startHeading);
            byte[] startPosBuffer = startPosMessage.getBytes(StandardCharsets.UTF_8);
            DatagramPacket startPosPacket = new DatagramPacket(startPosBuffer, startPosBuffer.length, address, ROBOT_LISTENER_PORT);
            socket.send(startPosPacket);

            for (CurvePoint point : currentPath) {
                String message = String.format(Locale.US, "curve_point:%.3f,%.3f,%.2f,%.2f,%.2f,%.3f,%.2f",
                        point.x, point.y, point.moveSpeed, point.turnSpeed,
                        point.followDistance,
                        point.slowDownTurnRadians, point.slowDownTurnAmount);

                byte[] buffer = message.getBytes(StandardCharsets.UTF_8);
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length, address, ROBOT_LISTENER_PORT);
                socket.send(packet);
            }

            String endMessage = "end";
            byte[] endBuffer = endMessage.getBytes(StandardCharsets.UTF_8);
            DatagramPacket endPacket = new DatagramPacket(endBuffer, endBuffer.length, address, ROBOT_LISTENER_PORT);
            socket.send(endPacket);
            instructionLabel.setText("Path sent successfully to " + robotIpAddress);
        } catch (IOException e) {
            instructionLabel.setText("Error sending path: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void exportPathToCode() {
        if (currentPath == null || currentPath.isEmpty()) {
            instructionLabel.setText("No path to export.");
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Export Code");
            alert.setHeaderText(null);
            alert.setContentText("There is no path to export. Please create a path first.");
            alert.showAndWait();
            return;
        }

        StringBuilder codeBuilder = new StringBuilder();
        try {
            double followAngleDeg = Double.parseDouble(controlPanel.getFollowAngleField().getText());
            double startX = Double.parseDouble(controlPanel.getStartXField().getText());
            double startY = Double.parseDouble(controlPanel.getStartYField().getText());
            double startHeading = Double.parseDouble(controlPanel.getStartHeadingField().getText());

            double allianceMultiplier = isRedAlliance ? -1.0 : 1.0;

            codeBuilder.append("// Code generated by FTC Field Simulator\n\n");
            codeBuilder.append("// 1. Set the robot's starting position on the field\n");
            double exportStartY = startY * allianceMultiplier;
            codeBuilder.append(String.format(Locale.US, "drivetrain.setPosition(new Pose2D(DistanceUnit.INCH, %.2f, y(%.2f), AngleUnit.DEGREES, %.2f));\n\n", startX, exportStartY, startHeading));

            codeBuilder.append("// 2. Define the path waypoints\n");
            codeBuilder.append("ArrayList<CurvePoint> pathToFollow = new ArrayList<>();\n");

            for (CurvePoint point : currentPath) {
                double exportY = point.y * allianceMultiplier;
                codeBuilder.append(String.format(Locale.US,
                        "pathToFollow.add(new CurvePoint(%.2f, y(%.2f), %.2f, %.2f, %.2f, Math.toRadians(%.1f), %.2f));\n",
                        point.x, exportY,
                        point.moveSpeed, point.turnSpeed,
                        point.followDistance,
                        Math.toDegrees(point.slowDownTurnRadians),
                        point.slowDownTurnAmount
                ));
            }
            codeBuilder.append("\n");

            codeBuilder.append("// 3. Create and add the command to the scheduler\n");
            codeBuilder.append(String.format(Locale.US, "scheduler.add(new FollowPathCommand(pathToFollow, Math.toRadians(%.1f), true));\n", followAngleDeg));

        } catch (NumberFormatException e) {
            instructionLabel.setText("Invalid parameters in UI fields! Could not generate code.");
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Export Error");
            alert.setHeaderText("Invalid Number Format");
            alert.setContentText("Could not generate code because one of the path parameter fields (like Follow Angle or Start Position) contains invalid text.");
            alert.showAndWait();
            return;
        }

        showCodePopup(codeBuilder.toString());
        instructionLabel.setText("Code generated with y() wrapping. See popup window to copy.");
    }

    private void showCodePopup(String code) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Exported Java Code");
        alert.setHeaderText("Copy the code below. It uses y() for alliance mirroring.");
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
        currentPath.clear();
        isCreatingPath = false;
        fieldDisplay.setPathCreationMode(false, null, () -> finishPathCreation(false));
        fieldDisplay.setPathToDraw(currentPath);
        fieldDisplay.drawCurrentState();
        fieldDisplay.setHighlightedPoint(null);
        instructionLabel.setText("Path deleted. Click 'New Path' to start drawing.");
        updateControlPanelForPathState();
    }

    private void finishPathCreation(boolean cancelled) {
        if (!isCreatingPath) return;
        isCreatingPath = false;
        fieldDisplay.setPathCreationMode(false, null, null);

        if (cancelled && currentPath != null) {
            currentPath.clear();
            instructionLabel.setText("Path creation cancelled. Click 'New Path' to start again.");
        } else {
            if (currentPath == null || currentPath.isEmpty()) {
                instructionLabel.setText("Path finished with no points. Click 'New Path' to start again.");
                if (currentPath != null) currentPath.clear();
                else currentPath = new ArrayList<>();
            } else {
                instructionLabel.setText("Path finished with " + currentPath.size() + " points. Select points to edit parameters.");
            }
        }

        if (controlPanel != null) {
            controlPanel.setPathEditingActive(false);
        }

        updateControlPanelForPathState();

        if (fieldDisplay != null && currentPath != null) {
            fieldDisplay.setPathToDraw(currentPath);
            fieldDisplay.drawCurrentState();
        }
    }

    private void handleFieldClickForPath(Point2D pixelCoords) {
        if (!isCreatingPath || controlPanel == null) return;

        Point2D inchesCoordsFieldCenter = fieldDisplay.pixelToInches(pixelCoords.getX(), pixelCoords.getY());
        double fieldX = inchesCoordsFieldCenter.getX();
        double fieldY = inchesCoordsFieldCenter.getY();

        if (currentPath.isEmpty()) {
            double startHeading = 0.0;
            robot.setPosition(fieldX, fieldY, startHeading);
            if (controlPanel != null) {
                controlPanel.updateRobotStartFields(fieldX, fieldY, startHeading);
            }
            updateUIFromRobotState();
        }

        double moveSpeed, turnSpeed, followDistance, slowDownTurnDeg, slowDownTurnAmount, slowDownTurnRad;
        try {
            moveSpeed = controlPanel.getMoveSpeedParam();
            turnSpeed = controlPanel.getTurnSpeedParam();
            followDistance = controlPanel.getFollowDistanceParam();
            slowDownTurnDeg = controlPanel.getSlowDownTurnDegreesParam();
            slowDownTurnAmount = controlPanel.getSlowDownTurnAmountParam();
            if (moveSpeed <= 0 || turnSpeed <= 0 || followDistance < 0 || slowDownTurnAmount < 0 || slowDownTurnAmount > 1) {
                throw new NumberFormatException("Default parameter out of typical range.");
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
        currentPath.add(newPoint);
        fieldDisplay.setPathToDraw(currentPath);
        fieldDisplay.drawCurrentState();

        if (currentPath.size() == 1) {
            instructionLabel.setText("Point 1 added. Click next waypoint. ESC to cancel.");
        } else {
            instructionLabel.setText("Point " + currentPath.size() + " added. Click next, Double-click last, or ESC to cancel.");
        }
    }

    private void updateTimeLapsedDisplay() {
        if (controlPanel == null || recordingManager == null) return;
        long timeLapsedMs = (recordingManager.getCurrentState() == RecordingManager.PlaybackState.RECORDING) ? recordingManager.getCurrentRecordingDuration() : recordingManager.getCurrentEventTimeLapsed();
        controlPanel.updateTimeLapsed(timeLapsedMs);
    }

    private void startNewPathCreation() {
        if (isCreatingPath) return;
        if (currentPath == null) currentPath = new ArrayList<>();
        currentPath.clear();
        isCreatingPath = true;
        fieldDisplay.setHighlightedPoint(null);

        if (controlPanel != null) {
            controlPanel.setPathEditingActive(true);
        }

        updateControlPanelForPathState();
        instructionLabel.setText("Click the first waypoint. Parameters from global defaults will be used.");
        if (fieldDisplay != null) {
            fieldDisplay.setPathCreationMode(true, this::handleFieldClickForPath, () -> finishPathCreation(false));
            fieldDisplay.setPathToDraw(currentPath);
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
            if (controlPanel != null) {
                double displayHeading = robot.getHeadingDegrees() % 360;
                if (displayHeading < 0) displayHeading += 360;
                controlPanel.updateRobotStartFields(robot.getXInches(), robot.getYInches(), displayHeading);
            }
            updateUIFromRobotState();
            event.consume();
        }
    }
}
