package com.xmlcompare.app;

import com.xmlcompare.model.ChangeType;
import com.xmlcompare.model.DiffNode;
import javafx.scene.control.TreeItem;

/**
 * Text and TSV formatting for the diff tree (status column, export, clipboard).
 */
public final class DiffFormat {

    private DiffFormat() {
    }

    public static String formatStatus(ChangeType t) {
        return switch (t) {
            case EQUAL -> "Match";
            case MODIFIED -> "Changed";
            case LEFT_ONLY -> "Left only";
            case RIGHT_ONLY -> "Right only";
        };
    }

    public static String formatStatusDisplay(DiffNode n) {
        String hint = n.getStructureHint();
        boolean hasHint = hint != null && !hint.isBlank();

        if (n.isStructuralBridge()) {
            StringBuilder sb = new StringBuilder("Parent (path) · ");
            if (hasHint) {
                sb.append(hint);
            } else {
                sb.append("no element-child diff at this node");
            }
            sb.append(" · underlying: ").append(formatStatus(n.getChangeType()));
            return sb.toString();
        }

        String base = formatStatus(n.getChangeType());
        if (hasHint && n.getChangeType() == ChangeType.MODIFIED) {
            String lbl = n.getNodeLabel();
            if (lbl != null && !lbl.startsWith("@") && !lbl.startsWith("#")) {
                return base + " · " + hint;
            }
        }
        return base;
    }

    public static int statusSortRank(String statusLabel) {
        if (statusLabel == null) {
            return 99;
        }
        if (statusLabel.startsWith("Parent (path)")) {
            return -1;
        }
        if (statusLabel.startsWith("Match")) {
            return 0;
        }
        if (statusLabel.startsWith("Changed")) {
            return 1;
        }
        if (statusLabel.startsWith("Left only")) {
            return 2;
        }
        if (statusLabel.startsWith("Right only")) {
            return 3;
        }
        return 99;
    }

    public static String escapeTsvCell(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\t", " ").replace("\n", " ").replace("\r", " ");
    }

    public static String comparisonTsvLine(DiffNode n) {
        return escapeTsvCell(n.getNodeLabel()) + "\t"
                + escapeTsvCell(n.getLeftValue()) + "\t"
                + escapeTsvCell(n.getRightValue()) + "\t"
                + escapeTsvCell(formatStatusDisplay(n));
    }

    public static String escapeXmlComment(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("--", "—");
    }

    public static String buildFilteredExportTsv(TreeItem<DiffNode> root) {
        StringBuilder sb = new StringBuilder();
        sb.append("Depth\tNode\tLeft value\tRight value\tStatus\tRaw change type\n");
        appendExportRows(root, 0, sb);
        return sb.toString();
    }

    private static void appendExportRows(TreeItem<DiffNode> item, int depth, StringBuilder sb) {
        if (item == null || item.getValue() == null) {
            return;
        }
        DiffNode n = item.getValue();
        sb.append(depth).append('\t');
        sb.append(escapeTsvCell(n.getNodeLabel())).append('\t');
        sb.append(escapeTsvCell(n.getLeftValue())).append('\t');
        sb.append(escapeTsvCell(n.getRightValue())).append('\t');
        sb.append(escapeTsvCell(formatStatusDisplay(n))).append('\t');
        sb.append(escapeTsvCell(formatStatus(n.getChangeType()))).append('\n');
        for (TreeItem<DiffNode> ch : item.getChildren()) {
            appendExportRows(ch, depth + 1, sb);
        }
    }

    public record RowCounts(int equal, int modified, int leftOnly, int rightOnly) {
    }

    public static RowCounts countRows(DiffNode n) {
        MutableCounts m = new MutableCounts();
        countRecursive(n, m);
        return new RowCounts(m.equal, m.modified, m.leftOnly, m.rightOnly);
    }

    private static void countRecursive(DiffNode n, MutableCounts s) {
        switch (n.getChangeType()) {
            case EQUAL -> s.equal++;
            case MODIFIED -> s.modified++;
            case LEFT_ONLY -> s.leftOnly++;
            case RIGHT_ONLY -> s.rightOnly++;
        }
        for (DiffNode c : n.getChildren()) {
            countRecursive(c, s);
        }
    }

    private static final class MutableCounts {
        int equal;
        int modified;
        int leftOnly;
        int rightOnly;
    }
}
