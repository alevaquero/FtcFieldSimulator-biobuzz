package com.example.ftcfieldsimulator;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Callback;
import javafx.util.Duration;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class ControlPanel extends VBox {
    public static final double PREFERRED_WIDTH_RATIO_TO_FIELD = 1.0 / 3.0;
    public static final String ALL_POINTS_MARKER = "ALL Points";
    public static final String TEXTFIELD_VARIES_TEXT = "-- Varies --";

    // --- UI Elements ---
    private Button newPathButton, deletePathButton, importCodeButton, exportCodeButton, clearTrailButton, clearNamedLinesButton;
    private Button prevPathButton, nextPathButton; // Path navigation buttons
    private Button editMissionButton; // New button for mission script popup
    private ComboBox<PathData> pathSelectionComboBox; // Multi-path support
    private TextArea missionScriptArea; // Mission Script Support (stored here, edited in popup)
    private ComboBox<String> ipAddressComboBox;
    private Button sendMissionButton;
    private Circle connectivityLed;
    private Timeline pingTimeline;
    private Button recordButton, playPauseButton, reverseButton, forwardButton;
    private Button instantReplayButton, returnToLiveButton;
    private Button openButton, saveButton, clearButton;
    private ImageView recordIcon, stopIcon, playIcon, pauseIcon, reverseIcon, forwardIcon;
    private Slider timelineSlider;
    private Button showPlotButton;

    // --- Robot Start Position UI Elements ---
    private TextField startXField;
    private TextField startYField;
    private TextField startHeadingField;
    private List<TextField> robotStartTextFieldsList;

    // --- Path Point Parameter UI Elements ---
    private TextField followAngleField; // NEW
    private Button prevPointButton, nextPointButton; // Point navigation buttons
    private ComboBox<Object> pointSelectionComboBox;
    private TextField moveSpeedField, turnSpeedField, followDistanceField, slowDownTurnDegreesField, slowDownTurnAmountField;
    private List<TextField> paramTextFieldsList;

    private Label timeLapsedLabel;

    // Default values for the fields
    public static final String DEFAULT_FOLLOW_ANGLE = String.format(Locale.US, "%.1f", 90.0); // NEW
    public static final String DEFAULT_MOVE_SPEED = String.format(Locale.US, "%.1f", 0.4);
    public static final String DEFAULT_TURN_SPEED = String.format(Locale.US, "%.1f", 0.4);
    public static final String DEFAULT_FOLLOW_DISTANCE = String.format(Locale.US, "%.1f", 10.0);
    public static final String DEFAULT_SLOW_DOWN_TURN_DEGREES = String.format(Locale.US, "%.1f", 60.0);
    public static final String DEFAULT_SLOW_DOWN_TURN_AMOUNT = String.format(Locale.US, "%.1f", 0.6);

    // --- SPACING CONSTANTS ---
    private static final int SECTION_SPACING = 5;


    public ControlPanel(double preferredWidth) {
        // --- SPACING CHANGE 1: Reduced main VBox spacing from 10 to 6 ---
        super(6);
        setPadding(new Insets(15));
        setPrefWidth(preferredWidth);
        setStyle("-fx-background-color: #ECEFF1;");

        // --- SPACING CHANGE 2: Reduced title font size from 16 to 15 ---
        Font titleFont = Font.font("Arial", FontWeight.BOLD, 15);

        // --- Robot Management Section ---
        Label robotTitle = new Label("Robot Management");
        robotTitle.setFont(titleFont);

        connectivityLed = new Circle(6, Color.RED);
        HBox robotTitleBox = new HBox(10, robotTitle, connectivityLed);
        robotTitleBox.setAlignment(Pos.CENTER_LEFT);

        pathSelectionComboBox = new ComboBox<>();
        pathSelectionComboBox.setPromptText("Select Path");
        pathSelectionComboBox.setMaxWidth(Double.MAX_VALUE);

        prevPathButton = new Button("<");
        nextPathButton = new Button(">");
        HBox pathNavBox = new HBox(5, prevPathButton, pathSelectionComboBox, nextPathButton);
        pathNavBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(pathSelectionComboBox, Priority.ALWAYS);

        newPathButton = createMaxWidthButton("New Path");
        deletePathButton = createMaxWidthButton("Delete Path");
        importCodeButton = createMaxWidthButton("Import Mission");
        exportCodeButton = createMaxWidthButton("Export Mission");
        ipAddressComboBox = new ComboBox<>();
        ipAddressComboBox.getItems().addAll("192.168.43.1", "192.168.56.2");
        ipAddressComboBox.setValue("192.168.43.1"); // Set default value
        ipAddressComboBox.setTooltip(new Tooltip("Select the Robot IP Address"));
        ipAddressComboBox.setStyle("-fx-font-size: 12px;");
        ipAddressComboBox.valueProperty().addListener((obs, oldVal, newVal) -> triggerImmediatePing());
        sendMissionButton = createMaxWidthButton("Send Mission to Robot");
        sendMissionButton.setStyle("-fx-font-size: 12px;");

        // Create an HBox to hold the IP selector and the send button
        HBox sendPathBox = new HBox(10, ipAddressComboBox, sendMissionButton);
        sendPathBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(sendMissionButton, Priority.ALWAYS); // Make the button fill remaining space

        // Create an HBox for the new/delete buttons
        HBox newDeleteBox = new HBox(5, newPathButton, deletePathButton);
        HBox.setHgrow(newPathButton, Priority.ALWAYS);
        HBox.setHgrow(deletePathButton, Priority.ALWAYS);

        // Create an HBox for the import/export buttons
        HBox importExportBox = new HBox(5, importCodeButton, exportCodeButton);
        HBox.setHgrow(importCodeButton, Priority.ALWAYS);
        HBox.setHgrow(exportCodeButton, Priority.ALWAYS);

        VBox pathControlsBox = new VBox(SECTION_SPACING, robotTitleBox, pathNavBox, newDeleteBox, importExportBox, sendPathBox);

        // --- Mission Script Section ---
        editMissionButton = createMaxWidthButton("Edit Mission Script");
        missionScriptArea = new TextArea(); // Stored but not added to layout
        missionScriptArea.setFont(Font.font("Consolas", 12));
        missionScriptArea.setPromptText("# Mission Script\nINIT: x=0 | y=0 | heading=0 | alliance=BLUE | delay=0\n...");


        // --- Robot Start Position Section ---
        Label robotStartTitle = new Label("Robot Start Position");
        robotStartTitle.setFont(titleFont);
        // --- SPACING CHANGE 4: Reduced GridPane vgap from 8 to 6 ---
        GridPane robotStartGrid = new GridPane();
        robotStartGrid.setHgap(10);
        robotStartGrid.setVgap(6);
        startXField = new TextField("0.0");
        startYField = new TextField("0.0");
        startHeadingField = new TextField("0.0");
        robotStartTextFieldsList = Arrays.asList(startXField, startYField, startHeadingField);
        robotStartGrid.add(new Label("Start X:"), 0, 0);
        robotStartGrid.add(startXField, 1, 0);
        robotStartGrid.add(new Label("Start Y:"), 0, 1);
        robotStartGrid.add(startYField, 1, 1);
        robotStartGrid.add(new Label("Start Heading:"), 0, 2);
        robotStartGrid.add(startHeadingField, 1, 2);
        VBox robotStartBox = new VBox(SECTION_SPACING, robotStartTitle, robotStartGrid);


        // --- Path Parameters Section ---
        Label paramsTitle = new Label("Path Parameters");
        paramsTitle.setFont(titleFont);

        // --- NEW: Follow Angle Field ---
        followAngleField = new TextField(DEFAULT_FOLLOW_ANGLE);
        Tooltip followAngleTooltip = new Tooltip(
                "The robot's heading relative to the path's direction.\n\n" +
                        "• 90°: Forward (like driving a car)\n" +
                        "• 0°: Sideways (facing right)\n" +
                        "• 180°: Sideways (facing left)\n" +
                        "• -90°: Backward (like reversing a car)"
        );
        followAngleTooltip.setWrapText(true); // Ensure the text wraps nicely
        followAngleField.setTooltip(followAngleTooltip);
        GridPane followAngleGrid = new GridPane();
        followAngleGrid.setHgap(10);
        followAngleGrid.setVgap(6);
        followAngleGrid.add(new Label("Follow Angle (Deg):"), 0, 0);
        followAngleField.setMinWidth(70);
        followAngleField.setPrefWidth(70);
        followAngleGrid.add(followAngleField, 1, 0);
        // --- END NEW ---

        pointSelectionComboBox = new ComboBox<>();
        pointSelectionComboBox.setPromptText("Select Point/ALL");
        pointSelectionComboBox.setMaxWidth(Double.MAX_VALUE);
        configurePointSelectionComboBoxCellFactory();

        prevPointButton = new Button("<");
        nextPointButton = new Button(">");
        HBox pointNavBox = new HBox(5, prevPointButton, pointSelectionComboBox, nextPointButton);
        pointNavBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(pointSelectionComboBox, Priority.ALWAYS);

        // --- SPACING CHANGE 4 (continued): Reduced GridPane vgap from 8 to 6 ---
        GridPane paramsGrid = createParametersGrid(6);
        VBox curveParamsBox = new VBox(SECTION_SPACING, paramsTitle, followAngleGrid, pointNavBox, paramsGrid);

        // --- Utility Controls Section ---
        Label utilityTitle = new Label("Utilities");
        utilityTitle.setFont(titleFont);
        clearTrailButton = createMaxWidthButton("Clear Robot Trail");
        clearNamedLinesButton = createMaxWidthButton("Clear Custom Lines");
        VBox utilityControlsBox = new VBox(SECTION_SPACING, utilityTitle, clearTrailButton, clearNamedLinesButton);


        // --- Recording Controls Section ---
        loadPlaybackIcons();
        Label recordingTitle = new Label("Recording");
        recordingTitle.setFont(titleFont);
        openButton = createMaxWidthButton("Open");
        saveButton = createMaxWidthButton("Save");
        clearButton = createMaxWidthButton("Clear");
        HBox fileButtons = new HBox(10, openButton, saveButton, clearButton);
        HBox.setHgrow(openButton, Priority.ALWAYS);
        HBox.setHgrow(saveButton, Priority.ALWAYS);
        HBox.setHgrow(clearButton, Priority.ALWAYS);
        recordButton = new Button();
        recordButton.setGraphic(recordIcon);
        playPauseButton = new Button();
        playPauseButton.setGraphic(playIcon);
        reverseButton = new Button();
        reverseButton.setGraphic(reverseIcon);
        forwardButton = new Button();
        forwardIcon = new ImageView(new Image(getClass().getResourceAsStream("/icons/forward.png"), 20, 20, true, true));
        forwardButton = new Button();
        forwardButton.setGraphic(forwardIcon);
        HBox recordingButtons = new HBox(10, reverseButton, playPauseButton, forwardButton, recordButton);
        recordingButtons.setAlignment(Pos.CENTER);

        // --- Instant Replay Section ---
        instantReplayButton = createMaxWidthButton("Instant Replay");
        returnToLiveButton = createMaxWidthButton("Return to Live");
//        returnToLiveButton.setStyle("-fx-font-weight: bold; -fx-background-color: #C8E6C9;"); // A green color
        HBox replayControlsBox = new HBox(instantReplayButton);
        HBox.setHgrow(instantReplayButton, Priority.ALWAYS);
        HBox.setHgrow(returnToLiveButton, Priority.ALWAYS);
        replayControlsBox.getChildren().add(returnToLiveButton);
        returnToLiveButton.setVisible(false);
        returnToLiveButton.setManaged(false);

        timelineSlider = new Slider(0, 100, 0);
        timeLapsedLabel = new Label("Time: 0.000");
        timeLapsedLabel.setFont(Font.font("Consolas", 12));
        timeLapsedLabel.setMaxWidth(Double.MAX_VALUE);
        timeLapsedLabel.setAlignment(Pos.CENTER_RIGHT);
        timeLapsedLabel.setPadding(new Insets(2, 0, 0, 0));

        VBox recordingControlsBox = new VBox(SECTION_SPACING, recordingTitle, fileButtons, recordingButtons, replayControlsBox, timelineSlider, timeLapsedLabel);

        // --- Tools Section ---
        showPlotButton = createMaxWidthButton("Show Time Plot");
        VBox toolsControlsBox = new VBox(SECTION_SPACING, showPlotButton);

        // --- Initial State ---
        enablePathControls(false, false);
        setPlaybackControlsDisabled(true);
        setSaveButtonDisabled(true);
        setPointEditingControlsDisabled(true);
        loadGlobalDefaultsIntoParameterFields();

        this.getChildren().addAll(robotStartBox, pathControlsBox, editMissionButton, curveParamsBox, utilityControlsBox, recordingControlsBox, toolsControlsBox);

        startPingService();
    }

    private void triggerImmediatePing() {
        String ip = getSelectedIpAddress();
        if (ip != null && !ip.isEmpty()) {
            new Thread(() -> {
                boolean reachable = false;
                try {
                    reachable = InetAddress.getByName(ip).isReachable(1000);
                } catch (Exception ignored) {}
                boolean finalReachable = reachable;
                Platform.runLater(() -> {
                    connectivityLed.setFill(finalReachable ? Color.GREEN : Color.RED);
                });
            }).start();
        } else {
            connectivityLed.setFill(Color.GRAY);
        }
    }

    private void startPingService() {
        pingTimeline = new Timeline(new KeyFrame(Duration.seconds(2), event -> triggerImmediatePing()));
        pingTimeline.setCycleCount(Timeline.INDEFINITE);
        pingTimeline.play();
    }

    private GridPane createParametersGrid(double vgap) {
        GridPane paramsGrid = new GridPane();
        paramsGrid.setHgap(10);
        paramsGrid.setVgap(vgap);
        paramsGrid.setPadding(new Insets(SECTION_SPACING, 0, SECTION_SPACING, 0)); // Reduced top/bottom padding

        ColumnConstraints column1 = new ColumnConstraints();
        column1.setMinWidth(110); // Slightly reduced min width
        ColumnConstraints column2 = new ColumnConstraints();
        column2.setHgrow(Priority.SOMETIMES);
        paramsGrid.getColumnConstraints().addAll(column1, column2);

        double textFieldMaxWidth = 100;
        moveSpeedField = new TextField();
        moveSpeedField.setMaxWidth(textFieldMaxWidth);
        turnSpeedField = new TextField();
        turnSpeedField.setMaxWidth(textFieldMaxWidth);
        followDistanceField = new TextField();
        followDistanceField.setMaxWidth(textFieldMaxWidth);
        slowDownTurnDegreesField = new TextField();
        slowDownTurnDegreesField.setMaxWidth(textFieldMaxWidth);
        slowDownTurnAmountField = new TextField();
        slowDownTurnAmountField.setMaxWidth(textFieldMaxWidth);

        moveSpeedField.setTooltip(new Tooltip("The robot's general speed (0.0 to 1.0) when moving to this point."));
        turnSpeedField.setTooltip(new Tooltip("The robot's general turning speed (0.0 to 1.0) when correcting its heading for this point."));
        followDistanceField.setTooltip(new Tooltip("The 'look-ahead' distance in inches. The robot aims for a point on the path this far ahead of its current position."));
        slowDownTurnDegreesField.setTooltip(new Tooltip("The turn error (in degrees) where the robot starts to slow down its forward movement to prioritize turning. Smaller values make the robot turn more cautiously around corners."));
        slowDownTurnAmountField.setTooltip(new Tooltip("How much to slow down (from 0.0 to 1.0) when turning sharply. A value of 1.0 allows the robot to stop and turn in place. A value of 0.0 disables this feature."));

        paramTextFieldsList = Arrays.asList(
                moveSpeedField, turnSpeedField, followDistanceField,
                slowDownTurnDegreesField, slowDownTurnAmountField
        );

        paramsGrid.add(new Label("Move Speed:"), 0, 0);
        paramsGrid.add(moveSpeedField, 1, 0);
        paramsGrid.add(new Label("Turn Speed:"), 0, 1);
        paramsGrid.add(turnSpeedField, 1, 1);
        paramsGrid.add(new Label("Follow Distance:"), 0, 2);
        paramsGrid.add(followDistanceField, 1, 2);
        paramsGrid.add(new Label("Slow Turn (Deg):"), 0, 4);
        paramsGrid.add(slowDownTurnDegreesField, 1, 4);
        paramsGrid.add(new Label("Slow Turn Amount:"), 0, 5);
        paramsGrid.add(slowDownTurnAmountField, 1, 5);
        return paramsGrid;
    }

    private void configurePointSelectionComboBoxCellFactory() {
        Callback<ListView<Object>, ListCell<Object>> cellFactory = param -> new ListCell<>() {
            @Override
            protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                if (item == null || empty) {
                    setText(null);
                } else if (item instanceof String) {
                    setText((String) item);
                } else if (item instanceof CurvePoint) {
                    CurvePoint cp = (CurvePoint) item;
                    int index = getListView().getItems().indexOf(cp);
                    if (getListView().getItems().contains(ALL_POINTS_MARKER)) {
                        index--; // Adjust if "ALL" is present
                    }
                    setText(String.format(Locale.US, "Point %d (X:%.1f, Y:%.1f)",
                            index + 1, cp.x, cp.y));
                } else {
                    setText(item.toString());
                }
            }
        };
        pointSelectionComboBox.setCellFactory(cellFactory);
        pointSelectionComboBox.setButtonCell(cellFactory.call(null));
    }


    // --- Getters for NEW Robot Start Fields ---
    public TextField getStartXField() { return startXField; }
    public TextField getStartYField() { return startYField; }
    public TextField getStartHeadingField() { return startHeadingField; }
    public List<TextField> getRobotStartTextFields() { return robotStartTextFieldsList; }
    public TextField getFollowAngleField() { return followAngleField; } // NEW


    // --- Public Methods for UI manipulation ---
    public void updateRobotStartFields(double x, double y, double heading) {
        startXField.setText(String.format(Locale.US, "%.2f", x));
        startYField.setText(String.format(Locale.US, "%.2f", y));
        startHeadingField.setText(String.format(Locale.US, "%.2f", heading));
    }

    private Button createMaxWidthButton(String text) {
        Button button = new Button(text);
        button.setMaxWidth(Double.MAX_VALUE);
        return button;
    }

    private void loadPlaybackIcons() {
        double iconSize = 20;
        try {
            recordIcon = new ImageView(new Image(getClass().getResourceAsStream("/icons/record.png"), iconSize, iconSize, true, true));
            stopIcon = new ImageView(new Image(getClass().getResourceAsStream("/icons/stop.png"), iconSize, iconSize, true, true));
            playIcon = new ImageView(new Image(getClass().getResourceAsStream("/icons/play.png"), iconSize, iconSize, true, true));
            pauseIcon = new ImageView(new Image(getClass().getResourceAsStream("/icons/pause.png"), iconSize, iconSize, true, true));
            reverseIcon = new ImageView(new Image(getClass().getResourceAsStream("/icons/reverse.png"), iconSize, iconSize, true, true));
            forwardIcon = new ImageView(new Image(getClass().getResourceAsStream("/icons/forward.png"), iconSize, iconSize, true, true));
        } catch (Exception e) {
            System.err.println("Error loading playback icons: " + e.getMessage());
            // Consider more robust error handling or default icons
        }
    }

    public void updateTimeLapsed(long totalMilliseconds) {
        if (totalMilliseconds < 0) {
            timeLapsedLabel.setText("Time: --.---");
            return;
        }
        long seconds = totalMilliseconds / 1000;
        long millis = totalMilliseconds % 1000;
        timeLapsedLabel.setText(String.format(Locale.US, "Time: %d.%03d", seconds, millis));
    }

    public void setOnClearRecordingAction(EventHandler<ActionEvent> handler) {
        if (clearButton != null) {
            clearButton.setOnAction(handler);
        }
    }
    public void setOnOpenAction(EventHandler<ActionEvent> handler) { openButton.setOnAction(handler); }
    public void setOnSaveAction(EventHandler<ActionEvent> handler) { saveButton.setOnAction(handler); }
    public void setSaveButtonDisabled(boolean isDisabled) {
        saveButton.setDisable(isDisabled);
        // Also disable the clear button if there's nothing to clear
        if (clearButton != null) {
            clearButton.setDisable(isDisabled);
        }
    }

    public void setOnNewPathAction(EventHandler<ActionEvent> handler) { newPathButton.setOnAction(handler); }
    public void setOnDeletePathAction(EventHandler<ActionEvent> handler) { deletePathButton.setOnAction(handler); }
    public void setOnPrevPathAction(EventHandler<ActionEvent> handler) { prevPathButton.setOnAction(handler); }
    public void setOnNextPathAction(EventHandler<ActionEvent> handler) { nextPathButton.setOnAction(handler); }
    public void setOnImportCodeAction(EventHandler<ActionEvent> handler) { importCodeButton.setOnAction(handler); }    public void setOnExportCodeAction(EventHandler<ActionEvent> handler) { exportCodeButton.setOnAction(handler); }
    public void setOnEditMissionAction(EventHandler<ActionEvent> handler) { editMissionButton.setOnAction(handler); }
    public void setOnSendMissionAction(EventHandler<ActionEvent> handler) { if (sendMissionButton != null) { sendMissionButton.setOnAction(handler); } }
    public void setOnClearTrailAction(EventHandler<ActionEvent> handler) { clearTrailButton.setOnAction(handler); }
    public void setOnClearNamedLinesAction(EventHandler<ActionEvent> handler) { clearNamedLinesButton.setOnAction(handler); }

    public void setOnRecordAction(Runnable action) { recordButton.setOnAction(e -> action.run()); }
    public void setOnPlayPauseAction(Runnable action) { playPauseButton.setOnAction(e -> action.run()); }
    public void setOnForwardAction(Runnable action) { if (forwardButton != null) { forwardButton.setOnAction(e -> action.run()); } }
    public void setOnReverseAction(Runnable action) { if (reverseButton != null) { reverseButton.setOnAction(e -> action.run()); } }

    public void setOnTimelineSliderChanged(ChangeListener<Number> listener) { timelineSlider.valueProperty().addListener(listener); }
    public void setOnSliderMouseReleased(EventHandler<MouseEvent> handler) { timelineSlider.setOnMouseReleased(handler); }

    public void setOnShowPlotAction(EventHandler<ActionEvent> handler) { if (showPlotButton != null) { showPlotButton.setOnAction(handler); } }

    // --- Getter for the IP address ComboBox ---
    public String getSelectedIpAddress() {
        return ipAddressComboBox.getValue();
    }

    public void setOnInstantReplayAction(EventHandler<ActionEvent> handler) { instantReplayButton.setOnAction(handler); }
    public void setOnReturnToLiveAction(EventHandler<ActionEvent> handler) { returnToLiveButton.setOnAction(handler); }

    public void setPathEditingActive(boolean isActive) {
        newPathButton.setDisable(isActive);
        deletePathButton.setDisable(isActive || deletePathButton.isDisabled());
        prevPathButton.setDisable(isActive);
        nextPathButton.setDisable(isActive);
        importCodeButton.setDisable(isActive); // Disable during path creation
        exportCodeButton.setDisable(isActive || exportCodeButton.isDisabled());
        if (sendMissionButton != null) {
            sendMissionButton.setDisable(isActive || sendMissionButton.isDisabled());
            ipAddressComboBox.setDisable(isActive || ipAddressComboBox.isDisabled()); // Also disable the IP box
        }
    }

    public void setReplayMode(boolean isInReplayMode) {
        instantReplayButton.setVisible(!isInReplayMode);
        instantReplayButton.setManaged(!isInReplayMode);
        returnToLiveButton.setVisible(isInReplayMode);
        returnToLiveButton.setManaged(isInReplayMode);
        // Disable record button while in replay mode to prevent conflicts
        recordButton.setDisable(isInReplayMode);

        // Enable playback controls and save button ONLY when in replay mode.
        // Disable them when returning to live mode.
        setPlaybackControlsDisabled(!isInReplayMode);
        setSaveButtonDisabled(!isInReplayMode);
    }

    public void enablePathControls(boolean pathExists, boolean missionExists) {
        boolean pathEditingMode = newPathButton.isDisabled();
        deletePathButton.setDisable(!pathExists || pathEditingMode);
        prevPathButton.setDisable(!pathExists || pathEditingMode);
        nextPathButton.setDisable(!pathExists || pathEditingMode);
        importCodeButton.setDisable(pathEditingMode);
        exportCodeButton.setDisable(!missionExists || pathEditingMode);
        if (sendMissionButton != null) {
            sendMissionButton.setDisable(!missionExists || pathEditingMode);
            ipAddressComboBox.setDisable(!missionExists || pathEditingMode); // Also disable the IP box if no mission
        }
    }

    public void updateTimelineSlider(int currentEventIndex, int totalEvents) {
        if (totalEvents > 1) {
            timelineSlider.setMax(totalEvents - 1);
            timelineSlider.setValue(currentEventIndex);
        } else {
            timelineSlider.setMax(100);
            timelineSlider.setValue(0);
        }
    }

    public void setTimelineSliderDisabled(boolean isDisabled) { timelineSlider.setDisable(isDisabled); }
    public void toggleRecordButtonIcon(boolean isRecording) { recordButton.setGraphic(isRecording ? stopIcon : recordIcon); }
    public void togglePlayPauseButtonIcon(boolean isPlaying) { playPauseButton.setGraphic(isPlaying ? pauseIcon : playIcon); }
    public void setRecording(boolean isRecording) { recordButton.setGraphic(isRecording ? stopIcon : recordIcon); }
    public void setPlaying(boolean isPlaying) { playPauseButton.setGraphic(isPlaying ? pauseIcon : playIcon); }

    public void setPlaybackControlsDisabled(boolean isDisabled) {
        playPauseButton.setDisable(isDisabled);
        reverseButton.setDisable(isDisabled);
        forwardButton.setDisable(isDisabled);
        timelineSlider.setDisable(isDisabled);
    }

    public Slider getTimelineSlider() { return this.timelineSlider; }
    public ComboBox<Object> getPointSelectionComboBox() { return pointSelectionComboBox; }
    public ComboBox<PathData> getPathSelectionComboBox() { return pathSelectionComboBox; }

    public void setPointEditingControlsDisabled(boolean disabled) {
        pointSelectionComboBox.setDisable(disabled);
        prevPointButton.setDisable(disabled);
        nextPointButton.setDisable(disabled);
        for (TextField tf : paramTextFieldsList) {
            tf.setDisable(disabled);
        }
    }

    public String getMissionScript() { return missionScriptArea.getText(); }
    public void setMissionScript(String script) { missionScriptArea.setText(script); }
    public TextArea getMissionScriptArea() { return missionScriptArea; }

    public void updatePathSelectionComboBox(List<PathData> paths, PathData pathToSelect) {
        // Only update items if they are functionally different to avoid unnecessary selection resets
        List<PathData> currentItems = pathSelectionComboBox.getItems();
        boolean itemsChanged = currentItems.size() != paths.size();
        if (!itemsChanged) {
            for (int i = 0; i < paths.size(); i++) {
                if (currentItems.get(i) != paths.get(i)) {
                    itemsChanged = true;
                    break;
                }
            }
        }

        if (itemsChanged) {
            pathSelectionComboBox.setItems(FXCollections.observableArrayList(paths));
        }

        if (pathToSelect != null) {
            // Avoid redundant selection changes
            if (pathSelectionComboBox.getValue() != pathToSelect) {
                pathSelectionComboBox.setValue(pathToSelect);
            }
        }
    }

    public void updatePointSelectionComboBox(List<CurvePoint> path, Object objectToSelect) {
        List<Object> newItemsForComboBox = new ArrayList<>();
        newItemsForComboBox.add(ALL_POINTS_MARKER);

        if (path != null && !path.isEmpty()) {
            newItemsForComboBox.addAll(path);
        }

        // Only update items if they are functionally different
        List<Object> currentItems = pointSelectionComboBox.getItems();
        boolean itemsChanged = currentItems.size() != newItemsForComboBox.size();
        if (!itemsChanged) {
            for (int i = 0; i < newItemsForComboBox.size(); i++) {
                if (currentItems.get(i) != newItemsForComboBox.get(i)) {
                    itemsChanged = true;
                    break;
                }
            }
        }

        if (itemsChanged) {
            pointSelectionComboBox.setItems(FXCollections.observableArrayList(newItemsForComboBox));
        }

        if (objectToSelect != null) {
            if (pointSelectionComboBox.getValue() != objectToSelect) {
                pointSelectionComboBox.setValue(objectToSelect);
            }
        } else if (path != null && !path.isEmpty()) {
            if (!ALL_POINTS_MARKER.equals(pointSelectionComboBox.getValue())) {
                pointSelectionComboBox.setValue(ALL_POINTS_MARKER);
            }
        } else {
            if (!pointSelectionComboBox.isDisabled()) {
                if (!ALL_POINTS_MARKER.equals(pointSelectionComboBox.getValue())) {
                    pointSelectionComboBox.setValue(ALL_POINTS_MARKER);
                }
            } else {
                pointSelectionComboBox.setValue(null);
            }
        }
    }

    public void setOnPointSelectionAction(ChangeListener<Object> listener) {
        pointSelectionComboBox.valueProperty().addListener(listener);
    }

    public void setOnPrevPointAction(EventHandler<ActionEvent> handler) { prevPointButton.setOnAction(handler); }
    public void setOnNextPointAction(EventHandler<ActionEvent> handler) { nextPointButton.setOnAction(handler); }

    public void setOnPathSelectionAction(ChangeListener<PathData> listener) {
        pathSelectionComboBox.valueProperty().addListener(listener);
    }

    public Object getSelectedPointFromComboBox() {
        return pointSelectionComboBox.getValue();
    }

    public void loadParametersForPoint(CurvePoint point) {
        if (point == null) {
            clearParameterFields();
            return;
        }
        moveSpeedField.setText(String.format(Locale.US, "%.2f", point.moveSpeed));
        turnSpeedField.setText(String.format(Locale.US, "%.2f", point.turnSpeed));
        followDistanceField.setText(String.format(Locale.US, "%.2f", point.followDistance));
        slowDownTurnDegreesField.setText(String.format(Locale.US, "%.1f", Math.toDegrees(point.slowDownTurnRadians)));
        slowDownTurnAmountField.setText(String.format(Locale.US, "%.2f", point.slowDownTurnAmount));
    }

    public void loadGlobalDefaultsIntoParameterFields() {
        moveSpeedField.setText(DEFAULT_MOVE_SPEED);
        turnSpeedField.setText(DEFAULT_TURN_SPEED);
        followDistanceField.setText(DEFAULT_FOLLOW_DISTANCE);
        slowDownTurnDegreesField.setText(DEFAULT_SLOW_DOWN_TURN_DEGREES);
        slowDownTurnAmountField.setText(DEFAULT_SLOW_DOWN_TURN_AMOUNT);
    }

    public void showVariesInParameterFields() {
        for (TextField tf : paramTextFieldsList) {
            tf.setText(TEXTFIELD_VARIES_TEXT);
        }
    }

    public void clearParameterFields() {
        for (TextField tf : paramTextFieldsList) {
            tf.setText("");
        }
    }

    public TextField getMoveSpeedField() { return moveSpeedField; }
    public TextField getTurnSpeedField() { return turnSpeedField; }
    public TextField getFollowDistanceField() { return followDistanceField; }
    public TextField getSlowDownTurnDegreesField() { return slowDownTurnDegreesField; }
    public TextField getSlowDownTurnAmountField() { return slowDownTurnAmountField; }
    public List<TextField> getAllParamTextFields() { return paramTextFieldsList; }

    public double getMoveSpeedParam() throws NumberFormatException { return Double.parseDouble(moveSpeedField.getText()); }
    public double getTurnSpeedParam() throws NumberFormatException { return Double.parseDouble(turnSpeedField.getText()); }
    public double getFollowDistanceParam() throws NumberFormatException { return Double.parseDouble(followDistanceField.getText()); }
    public double getSlowDownTurnDegreesParam() throws NumberFormatException { return Double.parseDouble(slowDownTurnDegreesField.getText()); }
    public double getSlowDownTurnAmountParam() throws NumberFormatException { return Double.parseDouble(slowDownTurnAmountField.getText()); }
}
