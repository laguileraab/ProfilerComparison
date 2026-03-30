package com.xmlcompare.app;

import com.xmlcompare.model.ChangeType;
import com.xmlcompare.model.DiffNode;
import com.xmlcompare.service.XmlDiffEngine;
import com.xmlcompare.service.XmlDiffEngine.XmlParseException;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableView;
import javafx.scene.input.Dragboard;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.w3c.dom.Document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class XmlCompareApp extends Application {

    private static final String APP_TITLE = "XML Compare — structural diff";
    private static final double SCENE_WIDTH = 1180;
    private static final double SCENE_HEIGHT = 820;

    private TextArea leftEditor;
    private TextArea rightEditor;
    private TreeTableView<DiffNode> diffTree;
    private Label statusLabel;
    private Label pathLeftLabel;
    private Label pathRightLabel;
    private ScheduledExecutorService compareScheduler;
    private ScheduledFuture<?> pendingCompare;
    private DiffNode lastDiffRoot;
    private Document comparedLeftDocument;
    private Document comparedRightDocument;
    private final DiffTreeFilter.State filterState = new DiffTreeFilter.State();
    private Label filterStatsLabel;
    private PauseTransition filterSearchDebounce;
    private TextField filterSearchField;
    private boolean suppressFilterCallbacks;
    private Stage primaryStage;

    @Override
    public void start(Stage stage) {
        primaryStage = stage;
        compareScheduler = createCompareScheduler();
        initEditorsAndDiffTree();

        statusLabel = new Label();
        statusLabel.getStyleClass().add("status-ok");

        BorderPane root = buildRootLayout(stage);
        BorderPane.setMargin(statusLabel, new Insets(0, 16, 12, 16));
        root.setBottom(statusLabel);

        configureMainStage(stage, root);
        stage.show();
        runCompare();
    }

    @Override
    public void stop() {
        if (pendingCompare != null) {
            pendingCompare.cancel(false);
        }
        if (compareScheduler != null) {
            compareScheduler.shutdownNow();
        }
    }

    private static ScheduledExecutorService createCompareScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "xml-compare-debounce");
            t.setDaemon(true);
            return t;
        });
    }

    private void configureMainStage(Stage stage, BorderPane root) {
        Scene scene = new Scene(root, SCENE_WIDTH, SCENE_HEIGHT);
        scene.getStylesheets().add(
                XmlCompareApp.class.getResource("/styles.css").toExternalForm());

        var iconUrl = XmlCompareApp.class.getResource("app-icon.png");
        if (iconUrl != null) {
            stage.getIcons().add(new Image(iconUrl.toExternalForm()));
        }

        stage.setTitle(APP_TITLE);
        stage.setMinWidth(900);
        stage.setMinHeight(600);
        stage.setScene(scene);
    }

    private void initEditorsAndDiffTree() {
        leftEditor = createXmlEditor();
        rightEditor = createXmlEditor();
        diffTree = DiffTreeTableSupport.createTable();
        DiffTreeTableSupport.attachMultiSelectGuards(diffTree);
        DiffTreeClipboardSupport.attach(
                diffTree,
                () -> comparedLeftDocument,
                () -> comparedRightDocument,
                this::setStatusOk,
                this::setStatusError);
    }

    private BorderPane buildRootLayout(Stage stage) {
        SplitPane mainSplit = new SplitPane(buildEditorsSplit(), buildTreeSection());
        mainSplit.setOrientation(Orientation.VERTICAL);
        mainSplit.setDividerPositions(0.42);

        BorderPane root = new BorderPane();
        root.setTop(buildToolbar(stage));
        root.setCenter(mainSplit);
        return root;
    }

    private SplitPane buildEditorsSplit() {
        SplitPane editors = new SplitPane(
                buildEditorPane(leftEditor, false),
                buildEditorPane(rightEditor, true));
        editors.setOrientation(Orientation.HORIZONTAL);
        editors.setDividerPositions(0.5);
        return editors;
    }

    private VBox buildEditorPane(TextArea editor, boolean rightSide) {
        Label title = new Label(rightSide ? "RIGHT DOCUMENT" : "LEFT DOCUMENT");
        title.getStyleClass().add("editor-label");
        if (rightSide) {
            title.getStyleClass().add("right");
        }

        Label pathChip = new Label("No file loaded");
        pathChip.getStyleClass().add("path-chip");
        if (rightSide) {
            pathRightLabel = pathChip;
        } else {
            pathLeftLabel = pathChip;
        }

        Label memHint = new Label("In-memory buffer — typing does not change the file on disk.");
        memHint.getStyleClass().add("memory-hint");

        VBox pane = new VBox(6, title, pathChip, memHint, editor);
        VBox.setVgrow(editor, Priority.ALWAYS);
        pane.getStyleClass().add("editor-pane");
        installFileDropTarget(pane, editor, !rightSide);
        return pane;
    }

    private Region buildTreeSection() {
        VBox treeWrap = new VBox(8, legendBar(), buildFilterBar(), diffTree);
        VBox.setVgrow(diffTree, Priority.ALWAYS);
        treeWrap.getStyleClass().add("tree-section");
        return treeWrap;
    }

    private Region legendBar() {
        HBox bar = new HBox(16);
        bar.getStyleClass().add("legend-bar");
        bar.getChildren().addAll(
                legendItem("Match", "equal"),
                legendItem("Changed", "modified"),
                legendItem("Left only", "left"),
                legendItem("Right only", "right"));
        return bar;
    }

    private static HBox legendItem(String text, String dotKind) {
        HBox row = new HBox(6);
        row.getStyleClass().add("legend-item");
        Region dot = new Region();
        dot.getStyleClass().addAll("legend-dot", dotKind);
        Label l = new Label(text);
        l.getStyleClass().add("legend-text");
        row.getChildren().addAll(dot, l);
        return row;
    }

    private Region buildFilterBar() {
        filterStatsLabel = new Label();
        filterStatsLabel.getStyleClass().add("filter-stats");

        TextField search = new TextField();
        filterSearchField = search;
        search.setPromptText("Search node name or left/right values…");
        search.getStyleClass().add("filter-search");
        HBox.setHgrow(search, Priority.ALWAYS);

        filterSearchDebounce = new PauseTransition(Duration.millis(320));
        filterSearchDebounce.setOnFinished(e -> {
            filterState.setQuery(search.getText());
            applyViewFilter();
        });
        search.textProperty().addListener((o, a, b) -> {
            filterSearchDebounce.stop();
            filterSearchDebounce.playFromStart();
        });

        CheckBox cbMatch = newStatusCheckBox(ChangeType.EQUAL, "Match");
        CheckBox cbChanged = newStatusCheckBox(ChangeType.MODIFIED, "Changed");
        CheckBox cbLeft = newStatusCheckBox(ChangeType.LEFT_ONLY, "Left only");
        CheckBox cbRight = newStatusCheckBox(ChangeType.RIGHT_ONLY, "Right only");

        Button resetFilters = new Button("Reset filters");
        resetFilters.getStyleClass().add("btn-secondary");
        resetFilters.setOnAction(e -> {
            if (filterSearchDebounce != null) {
                filterSearchDebounce.stop();
            }
            suppressFilterCallbacks = true;
            try {
                search.setText("");
                filterState.reset();
                cbMatch.setSelected(true);
                cbChanged.setSelected(true);
                cbLeft.setSelected(true);
                cbRight.setSelected(true);
            } finally {
                suppressFilterCallbacks = false;
            }
            applyViewFilter();
        });

        Label sortHint = new Label("Sort: click any column header.");
        sortHint.getStyleClass().add("filter-hint");

        Button exportFiltered = new Button("Export filtered…");
        exportFiltered.getStyleClass().add("btn-secondary");
        exportFiltered.setOnAction(e -> exportFilteredComparison());
        exportFiltered.disableProperty().bind(Bindings.createBooleanBinding(
                () -> diffTree.getRoot() == null,
                diffTree.rootProperty()));

        HBox row1 = new HBox(10, search, resetFilters, exportFiltered, filterStatsLabel);
        row1.setAlignment(Pos.CENTER_LEFT);
        row1.getStyleClass().add("filter-row");

        Label statusCaption = new Label("Status:");
        statusCaption.getStyleClass().add("filter-label");
        HBox row2 = new HBox(12, statusCaption, cbMatch, cbChanged, cbLeft, cbRight, sortHint);
        row2.setAlignment(Pos.CENTER_LEFT);
        row2.getStyleClass().add("filter-row");

        VBox box = new VBox(6, row1, row2);
        box.getStyleClass().add("filter-bar");
        return box;
    }

    private CheckBox newStatusCheckBox(ChangeType type, String caption) {
        CheckBox cb = new CheckBox(caption);
        cb.setSelected(filterState.isTypeEnabled(type));
        cb.getStyleClass().add("filter-check");
        cb.selectedProperty().addListener((o, prev, selected) -> {
            if (suppressFilterCallbacks) {
                return;
            }
            filterState.setTypeEnabled(type, selected);
            applyViewFilter();
        });
        return cb;
    }

    private void applyViewFilter() {
        if (filterStatsLabel == null) {
            return;
        }
        if (lastDiffRoot == null) {
            filterStatsLabel.setText("");
            return;
        }
        DiffTreeFilter.Result r = DiffTreeFilter.apply(lastDiffRoot, filterState);
        diffTree.setRoot(DiffTreeTableSupport.buildTreeItem(r.tree()));
        if (diffTree.getRoot() != null) {
            diffTree.getRoot().setExpanded(true);
        }
        filterStatsLabel.setText(String.format("Showing %,d of %,d rows", r.visibleCount(), r.totalCount()));
    }

    private TextArea createXmlEditor() {
        TextArea ta = new TextArea();
        ta.setWrapText(false);
        ta.getStyleClass().add("xml-editor");
        ta.setTooltip(new Tooltip(
                "Drop an .xml file here or paste content. Edits stay in this buffer until you load another file."));
        ta.textProperty().addListener((o, a, b) -> runCompareDebounced());
        return ta;
    }

    private void runCompareDebounced() {
        if (compareScheduler == null || compareScheduler.isShutdown()) {
            return;
        }
        if (pendingCompare != null) {
            pendingCompare.cancel(false);
        }
        pendingCompare = compareScheduler.schedule(
                () -> Platform.runLater(this::runCompare),
                450,
                TimeUnit.MILLISECONDS);
    }

    private void cancelPendingCompare() {
        if (pendingCompare != null) {
            pendingCompare.cancel(false);
            pendingCompare = null;
        }
    }

    private void installFileDropTarget(VBox pane, TextArea editor, boolean left) {
        pane.setOnDragOver(DragHandlers::onDragOverFiles);
        pane.setOnDragDropped(e -> onDragDroppedFiles(e, left));
        editor.setOnDragOver(DragHandlers::onDragOverFiles);
        editor.setOnDragDropped(e -> onDragDroppedFiles(e, left));
    }

    private void onDragDroppedFiles(DragEvent event, boolean left) {
        boolean ok = tryConsumeDroppedXmlFile(event.getDragboard(), left);
        event.setDropCompleted(ok);
        event.consume();
    }

    private boolean tryConsumeDroppedXmlFile(Dragboard db, boolean left) {
        if (!db.hasFiles() || db.getFiles().isEmpty()) {
            return false;
        }
        Path path = db.getFiles().getFirst().toPath();
        try {
            applyLoadedContent(XmlDiffEngine.readFileText(path), path, left);
            return true;
        } catch (IOException ex) {
            setStatusError("Could not read dropped file: " + ex.getMessage());
            return false;
        }
    }

    private void applyLoadedContent(String content, Path path, boolean left) {
        if (left) {
            leftEditor.setText(XmlDiffEngine.normalizeXmlText(content));
            pathLeftLabel.setText(path.toAbsolutePath().toString());
        } else {
            rightEditor.setText(XmlDiffEngine.normalizeXmlText(content));
            pathRightLabel.setText(path.toAbsolutePath().toString());
        }
        cancelPendingCompare();
        runCompare();
    }

    private void exportFilteredComparison() {
        if (primaryStage == null) {
            return;
        }
        TreeItem<DiffNode> root = diffTree.getRoot();
        if (root == null || root.getValue() == null) {
            return;
        }
        FileChooser fc = new FileChooser();
        fc.setTitle("Export filtered comparison");
        fc.setInitialFileName("xml-compare-filtered.tsv");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Tab-separated values (*.tsv)", "*.tsv"));
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text (*.txt)", "*.txt"));
        var file = fc.showSaveDialog(primaryStage);
        if (file == null) {
            return;
        }
        try {
            Files.writeString(file.toPath(), DiffFormat.buildFilteredExportTsv(root), StandardCharsets.UTF_8);
            setStatusOk("Exported filtered tree to " + file.getAbsolutePath());
        } catch (IOException ex) {
            setStatusError("Export failed: " + ex.getMessage());
        }
    }

    private HBox buildToolbar(Stage stage) {
        Label title = new Label("XML Compare");
        title.getStyleClass().add("app-title");
        Label sub = new Label("Paste, open, or drop XML files — tree updates after a short pause, or use Compare now.");
        sub.getStyleClass().add("subtitle");
        VBox titles = new VBox(2, title, sub);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button openLeft = new Button("Open left…");
        openLeft.getStyleClass().add("btn-secondary");
        openLeft.setOnAction(e -> loadFile(stage, true));

        Button openRight = new Button("Open right…");
        openRight.getStyleClass().add("btn-secondary");
        openRight.setOnAction(e -> loadFile(stage, false));

        Button compare = new Button("Compare now");
        compare.getStyleClass().add("btn-primary");
        compare.setOnAction(e -> {
            cancelPendingCompare();
            runCompare();
        });

        Button expand = new Button("Expand all");
        expand.getStyleClass().add("btn-ghost");
        expand.setOnAction(e -> setEntireDiffTreeExpanded(true));

        Button collapse = new Button("Collapse all");
        collapse.getStyleClass().add("btn-ghost");
        collapse.setOnAction(e -> setEntireDiffTreeExpanded(false));

        BooleanBinding noDiffTree = Bindings.createBooleanBinding(
                () -> diffTree.getRoot() == null,
                diffTree.rootProperty());
        expand.disableProperty().bind(noDiffTree);
        collapse.disableProperty().bind(noDiffTree);

        HBox bar = new HBox(12, titles, spacer, openLeft, openRight, compare, expand, collapse);
        bar.getStyleClass().add("toolbar");
        return bar;
    }

    private void setEntireDiffTreeExpanded(boolean expanded) {
        TreeItem<DiffNode> root = diffTree.getRoot();
        if (root == null) {
            return;
        }
        DiffTreeTableSupport.expandAll(root, expanded);
        diffTree.refresh();
    }

    private void loadFile(Stage stage, boolean left) {
        FileChooser fc = new FileChooser();
        fc.setTitle(left ? "Open left XML" : "Open right XML");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("XML", "*.xml"));
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("All", "*.*"));
        var chosen = fc.showOpenDialog(stage);
        if (chosen == null) {
            return;
        }
        Path file = chosen.toPath();
        try {
            String content = XmlDiffEngine.normalizeXmlText(XmlDiffEngine.readFileText(file));
            if (left) {
                leftEditor.setText(content);
                pathLeftLabel.setText(file.toAbsolutePath().toString());
            } else {
                rightEditor.setText(content);
                pathRightLabel.setText(file.toAbsolutePath().toString());
            }
            cancelPendingCompare();
            runCompare();
        } catch (IOException ex) {
            setStatusError("Could not read file: " + ex.getMessage());
        }
    }

    private void runCompare() {
        String l = leftEditor.getText();
        String r = rightEditor.getText();
        if (l.isBlank() && r.isBlank()) {
            clearComparedDocumentsAndTree();
            setStatusOk("Load or paste XML in both panels to compare.");
            return;
        }
        try {
            Document dl = XmlDiffEngine.parse(l.isBlank() ? "<empty/>" : l);
            Document dr = XmlDiffEngine.parse(r.isBlank() ? "<empty/>" : r);

            comparedLeftDocument = l.isBlank() ? null : dl;
            comparedRightDocument = r.isBlank() ? null : dr;

            DiffNode root = XmlDiffEngine.compareDocuments(dl, dr);
            lastDiffRoot = root;
            if (filterSearchField != null) {
                filterState.setQuery(filterSearchField.getText());
            }
            applyViewFilter();

            DiffFormat.RowCounts stats = DiffFormat.countRows(root);
            setStatusOk(String.format(
                    "Compared successfully — %d changed, %d left-only, %d right-only, %d matching rows (including descendants).",
                    stats.modified(), stats.leftOnly(), stats.rightOnly(), stats.equal()));
        } catch (XmlParseException ex) {
            clearComparedDocumentsAndTree();
            setStatusError("XML error: " + ex.getMessage());
        }
    }

    private void clearComparedDocumentsAndTree() {
        lastDiffRoot = null;
        comparedLeftDocument = null;
        comparedRightDocument = null;
        diffTree.setRoot(null);
        applyViewFilter();
    }

    private void setStatusOk(String message) {
        statusLabel.setText(message);
        statusLabel.getStyleClass().setAll("status-ok");
    }

    private void setStatusError(String message) {
        statusLabel.setText(message);
        statusLabel.getStyleClass().setAll("status-error");
    }

    private static final class DragHandlers {
        private DragHandlers() {
        }

        static void onDragOverFiles(DragEvent event) {
            Dragboard db = event.getDragboard();
            if (db.hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
