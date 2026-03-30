package com.xmlcompare.app;

import com.xmlcompare.model.DiffNode;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeSortMode;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableRow;
import javafx.scene.control.TreeTableView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the structural-diff {@link TreeTableView}, row styling, and multi-select behavior.
 */
public final class DiffTreeTableSupport {

    private DiffTreeTableSupport() {
    }

    public static TreeTableView<DiffNode> createTable() {
        TreeTableView<DiffNode> tree = new TreeTableView<>();
        tree.setShowRoot(true);
        tree.setTableMenuButtonVisible(false);
        tree.setSortMode(TreeSortMode.ALL_DESCENDANTS);
        configureTreeMultipleSelection(tree);
        tree.getColumns().addAll(buildColumns());
        tree.setTooltip(new Tooltip(
                "Ctrl+click or Shift+click to select multiple rows. "
                        + "Right-click: copy comparison table, or full XML subtrees from left/right document. "
                        + "Ctrl+C copies the comparison (TSV)."));

        tree.setRowFactory(tv -> {
            TreeTableRow<DiffNode> row = new TreeTableRow<>();
            row.itemProperty().addListener((obs, prev, item) -> {
                row.getStyleClass().removeAll(
                        "row-equal", "row-modified", "row-left-only", "row-right-only", "row-bridge");
                if (item != null) {
                    if (item.isStructuralBridge()) {
                        row.getStyleClass().add("row-bridge");
                    } else {
                        switch (item.getChangeType()) {
                            case EQUAL -> row.getStyleClass().add("row-equal");
                            case MODIFIED -> row.getStyleClass().add("row-modified");
                            case LEFT_ONLY -> row.getStyleClass().add("row-left-only");
                            case RIGHT_ONLY -> row.getStyleClass().add("row-right-only");
                        }
                    }
                }
            });
            return row;
        });

        return tree;
    }

    private static List<TreeTableColumn<DiffNode, String>> buildColumns() {
        TreeTableColumn<DiffNode, String> colNode = new TreeTableColumn<>("Node");
        colNode.setPrefWidth(280);
        colNode.setCellValueFactory(p -> new SimpleStringProperty(p.getValue().getValue().getNodeLabel()));

        TreeTableColumn<DiffNode, String> colLeft = new TreeTableColumn<>("Left value");
        colLeft.setPrefWidth(360);
        colLeft.setCellValueFactory(p -> new SimpleStringProperty(p.getValue().getValue().getLeftValue()));

        TreeTableColumn<DiffNode, String> colRight = new TreeTableColumn<>("Right value");
        colRight.setPrefWidth(360);
        colRight.setCellValueFactory(p -> new SimpleStringProperty(p.getValue().getValue().getRightValue()));

        TreeTableColumn<DiffNode, String> colStatus = new TreeTableColumn<>("Status");
        colStatus.setPrefWidth(420);
        colStatus.setMinWidth(200);
        colStatus.setCellValueFactory(p -> new SimpleStringProperty(
                DiffFormat.formatStatusDisplay(p.getValue().getValue())));

        colNode.setComparator(String.CASE_INSENSITIVE_ORDER);
        colLeft.setComparator(String.CASE_INSENSITIVE_ORDER);
        colRight.setComparator(String.CASE_INSENSITIVE_ORDER);
        colStatus.setComparator(Comparator.comparingInt(DiffFormat::statusSortRank));

        wrapMonospace(colLeft);
        wrapMonospace(colRight);

        return List.of(colNode, colLeft, colRight, colStatus);
    }

    private static void wrapMonospace(TreeTableColumn<DiffNode, String> col) {
        col.setCellFactory(c -> new TreeTableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item);
                }
                getStyleClass().remove("mono-tree-cell");
                if (!empty) {
                    getStyleClass().add("mono-tree-cell");
                }
            }
        });
    }

    public static void configureTreeMultipleSelection(TreeTableView<DiffNode> tree) {
        var sm = tree.getSelectionModel();
        if (sm.getSelectionMode() != SelectionMode.MULTIPLE) {
            sm.setSelectionMode(SelectionMode.MULTIPLE);
        }
        if (sm.isCellSelectionEnabled()) {
            sm.setCellSelectionEnabled(false);
        }
    }

    /**
     * JavaFX often resets {@link SelectionMode} when the tree root or sort order changes; modifier
     * clicks are also unreliable on {@link TreeTableView}, so we re-apply mode and handle Ctrl/Cmd
     * (toggle) and Shift (range in visible row order) explicitly.
     * <p>
     * Selection is applied on {@link MouseEvent#MOUSE_PRESSED}, not click: the default skin selects
     * on press; a later click handler would fight that and cause rapid select/deselect.
     */
    public static void attachMultiSelectGuards(TreeTableView<DiffNode> tree) {
        Runnable reapply = () -> Platform.runLater(() -> configureTreeMultipleSelection(tree));
        tree.rootProperty().addListener((o, a, b) -> reapply.run());
        tree.getSortOrder().addListener((ListChangeListener<TreeTableColumn<DiffNode, ?>>) c -> reapply.run());

        tree.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() != MouseButton.PRIMARY) {
                return;
            }
            if (!e.isShortcutDown() && !e.isShiftDown()) {
                return;
            }
            TreeTableRow<DiffNode> row = findEnclosingTreeTableRow(e.getPickResult().getIntersectedNode());
            if (row == null || row.isEmpty()) {
                return;
            }
            TreeItem<DiffNode> item = row.getTreeItem();
            if (item == null) {
                return;
            }
            var sm = tree.getSelectionModel();
            if (e.isShortcutDown()) {
                int rowIndex = tree.getRow(item);
                if (rowIndex < 0) {
                    return;
                }
                if (sm.isSelected(rowIndex)) {
                    sm.clearSelection(rowIndex);
                } else {
                    sm.select(rowIndex);
                }
                tree.getFocusModel().focus(rowIndex);
                e.consume();
            } else if (e.isShiftDown()) {
                int targetRow = tree.getRow(item);
                if (targetRow < 0) {
                    return;
                }
                int anchorRow = tree.getFocusModel().getFocusedIndex();
                if (anchorRow < 0) {
                    anchorRow = targetRow;
                }
                int lo = Math.min(anchorRow, targetRow);
                int hi = Math.max(anchorRow, targetRow);
                sm.clearSelection();
                sm.selectRange(lo, null, hi, null);
                tree.getFocusModel().focus(targetRow);
                e.consume();
            }
        });
    }

    private static TreeTableRow<DiffNode> findEnclosingTreeTableRow(Node n) {
        while (n != null) {
            if (n instanceof TreeTableRow<?> tr) {
                @SuppressWarnings("unchecked")
                TreeTableRow<DiffNode> row = (TreeTableRow<DiffNode>) tr;
                return row;
            }
            n = n.getParent();
        }
        return null;
    }

    public static TreeItem<DiffNode> buildTreeItem(DiffNode node) {
        TreeItem<DiffNode> item = new TreeItem<>(node);
        for (DiffNode child : node.getChildren()) {
            item.getChildren().add(buildTreeItem(child));
        }
        return item;
    }

    public static void expandAll(TreeItem<DiffNode> item, boolean expand) {
        if (item == null) {
            return;
        }
        item.setExpanded(expand);
        for (TreeItem<DiffNode> c : item.getChildren()) {
            expandAll(c, expand);
        }
    }

    /**
     * Selected {@link TreeItem}s in depth-first (preorder) order, so copy/export order matches the tree.
     */
    public static List<DiffNode> selectedInTreeOrder(TreeTableView<DiffNode> tree) {
        ObservableList<TreeItem<DiffNode>> selected = tree.getSelectionModel().getSelectedItems();
        if (selected == null || selected.isEmpty()) {
            return List.of();
        }
        Set<TreeItem<DiffNode>> sel = new HashSet<>(selected);
        List<DiffNode> out = new ArrayList<>();
        TreeItem<DiffNode> root = tree.getRoot();
        if (root != null) {
            collectSelectedPreorder(root, sel, out);
        }
        return out;
    }

    private static void collectSelectedPreorder(
            TreeItem<DiffNode> item, Set<TreeItem<DiffNode>> selected, List<DiffNode> out) {
        if (item == null || item.getValue() == null) {
            return;
        }
        if (selected.contains(item)) {
            out.add(item.getValue());
        }
        for (TreeItem<DiffNode> ch : item.getChildren()) {
            collectSelectedPreorder(ch, selected, out);
        }
    }
}
