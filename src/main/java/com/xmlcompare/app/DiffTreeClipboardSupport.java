package com.xmlcompare.app;

import com.xmlcompare.model.DiffNode;
import com.xmlcompare.service.SubtreeXmlSerializer;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TreeTableView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.transform.TransformerException;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Context menu and keyboard shortcuts for copying comparison TSV and XML subtrees from the diff tree.
 */
public final class DiffTreeClipboardSupport {

    private DiffTreeClipboardSupport() {
    }

    public static void attach(
            TreeTableView<DiffNode> tree,
            Supplier<Document> leftDocument,
            Supplier<Document> rightDocument,
            Consumer<String> statusOk,
            Consumer<String> statusError) {
        ContextMenu menu = new ContextMenu();
        MenuItem copyComparison = new MenuItem("Copy comparison");
        MenuItem copyLeftSubtree = new MenuItem("Copy subtree(s) from left");
        MenuItem copyRightSubtree = new MenuItem("Copy subtree(s) from right");
        copyComparison.setOnAction(e -> copyComparisonToClipboard(tree));
        copyLeftSubtree.setOnAction(e -> copySubtreesToClipboard(tree, true, leftDocument, rightDocument, statusOk, statusError));
        copyRightSubtree.setOnAction(e -> copySubtreesToClipboard(tree, false, leftDocument, rightDocument, statusOk, statusError));
        menu.getItems().addAll(copyComparison, copyLeftSubtree, copyRightSubtree);
        menu.setOnShowing(e -> {
            boolean sel = !DiffTreeTableSupport.selectedInTreeOrder(tree).isEmpty();
            copyComparison.setDisable(!sel);
            copyLeftSubtree.setDisable(!sel || leftDocument.get() == null);
            copyRightSubtree.setDisable(!sel || rightDocument.get() == null);
        });
        tree.setContextMenu(menu);
        tree.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.C && e.isShortcutDown() && tree.isFocused()) {
                if (copyComparisonToClipboard(tree)) {
                    e.consume();
                }
            }
        });
    }

    private static boolean copyComparisonToClipboard(TreeTableView<DiffNode> tree) {
        List<DiffNode> nodes = DiffTreeTableSupport.selectedInTreeOrder(tree);
        if (nodes.isEmpty()) {
            return false;
        }
        String header = "Node\tLeft value\tRight value\tStatus\n";
        String body = nodes.stream().map(DiffFormat::comparisonTsvLine).collect(Collectors.joining("\n"));
        ClipboardContent content = new ClipboardContent();
        content.putString(header + body);
        Clipboard.getSystemClipboard().setContent(content);
        return true;
    }

    private static boolean copySubtreesToClipboard(
            TreeTableView<DiffNode> tree,
            boolean leftSide,
            Supplier<Document> leftDocument,
            Supplier<Document> rightDocument,
            Consumer<String> statusOk,
            Consumer<String> statusError) {
        List<DiffNode> nodes = DiffTreeTableSupport.selectedInTreeOrder(tree);
        if (nodes.isEmpty()) {
            return false;
        }
        Document doc = leftSide ? leftDocument.get() : rightDocument.get();
        if (doc == null) {
            statusError.accept("Run a successful compare first so the " + (leftSide ? "left" : "right")
                    + " document is available for subtree copy.");
            return false;
        }
        String side = leftSide ? "left" : "right";
        StringBuilder sb = new StringBuilder();
        int blocks = 0;
        for (DiffNode n : nodes) {
            if (!n.canCopyElementSubtree()) {
                continue;
            }
            Element el = SubtreeXmlSerializer.resolveElement(doc, n.getElementPath());
            if (el == null) {
                appendSubtreeSeparator(sb, blocks);
                sb.append("<!-- ").append(side).append(": could not resolve subtree for row «")
                        .append(DiffFormat.escapeXmlComment(n.getNodeLabel())).append("» -->\n");
                blocks++;
                continue;
            }
            try {
                appendSubtreeSeparator(sb, blocks);
                sb.append("<!-- ").append(side).append(": ")
                        .append(DiffFormat.escapeXmlComment(n.getNodeLabel())).append(" -->\n");
                sb.append(SubtreeXmlSerializer.serializePretty(el));
                blocks++;
            } catch (TransformerException ex) {
                appendSubtreeSeparator(sb, blocks);
                sb.append("<!-- ").append(side).append(": serialize error — ")
                        .append(DiffFormat.escapeXmlComment(ex.getMessage())).append(" -->\n");
                blocks++;
            }
        }
        if (blocks == 0) {
            statusError.accept("No subtree to copy: selected rows are not linked to the " + side
                    + " XML tree (e.g. filter messages).");
            return false;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(sb.toString());
        Clipboard.getSystemClipboard().setContent(content);
        statusOk.accept("Copied " + blocks + " subtree block(s) from " + side + " document.");
        return true;
    }

    private static void appendSubtreeSeparator(StringBuilder sb, int blocksSoFar) {
        if (blocksSoFar > 0) {
            sb.append("\n\n");
        }
    }
}
