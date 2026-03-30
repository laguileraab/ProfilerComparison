package com.xmlcompare.app;

import com.xmlcompare.model.ChangeType;
import com.xmlcompare.model.DiffNode;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

public final class DiffTreeFilter {

    private DiffTreeFilter() {
    }

    public static final class State {
        private String query = "";
        private final EnumSet<ChangeType> allowed = EnumSet.allOf(ChangeType.class);

        public void setQuery(String q) {
            this.query = q != null ? q : "";
        }

        public String getQuery() {
            return query;
        }

        public void setTypeEnabled(ChangeType t, boolean on) {
            if (on) {
                allowed.add(t);
            } else {
                allowed.remove(t);
            }
        }

        public boolean isTypeEnabled(ChangeType t) {
            return allowed.contains(t);
        }

        public boolean isNoStatusSelected() {
            return allowed.isEmpty();
        }

        public boolean statusAllows(ChangeType t) {
            return allowed.contains(t);
        }

        public boolean textMatches(DiffNode n) {
            String q = query.trim().toLowerCase(Locale.ROOT);
            if (q.isEmpty()) {
                return true;
            }
            return contains(n.getNodeLabel(), q)
                    || contains(n.getLeftValue(), q)
                    || contains(n.getRightValue(), q);
        }

        public void reset() {
            query = "";
            allowed.clear();
            allowed.addAll(EnumSet.allOf(ChangeType.class));
        }

        private static boolean contains(String s, String q) {
            return s != null && s.toLowerCase(Locale.ROOT).contains(q);
        }
    }

    public record Result(DiffNode tree, int visibleCount, int totalCount) {
    }

    public static Result apply(DiffNode root, State state) {
        int total = countNodes(root);
        if (state.isNoStatusSelected()) {
            DiffNode msg = DiffNode.leaf(
                    "(select at least one status filter)",
                    "",
                    "",
                    ChangeType.EQUAL);
            return new Result(msg, 1, total);
        }
        DiffNode filtered = filterNode(root, state);
        if (filtered == null) {
            DiffNode msg = DiffNode.leaf("(no rows match filters)", "", "", ChangeType.EQUAL);
            return new Result(msg, 1, total);
        }
        return new Result(filtered, countNodes(filtered), total);
    }

    private static DiffNode filterNode(DiffNode n, State state) {
        List<DiffNode> kept = new ArrayList<>();
        for (DiffNode c : n.getChildren()) {
            DiffNode fc = filterNode(c, state);
            if (fc != null) {
                kept.add(fc);
            }
        }
        boolean self = state.statusAllows(n.getChangeType()) && state.textMatches(n);
        if (!self && kept.isEmpty()) {
            return null;
        }
        boolean structuralBridge = !self;
        return DiffNode.branch(
                n.getNodeLabel(),
                n.getLeftValue(),
                n.getRightValue(),
                n.getChangeType(),
                kept,
                structuralBridge,
                n.getStructureHint(),
                n.getElementPath());
    }

    private static int countNodes(DiffNode n) {
        int c = 1;
        for (DiffNode ch : n.getChildren()) {
            c += countNodes(ch);
        }
        return c;
    }
}
