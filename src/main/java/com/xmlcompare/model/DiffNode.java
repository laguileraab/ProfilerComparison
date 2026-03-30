package com.xmlcompare.model;

import java.util.List;

public final class DiffNode {

    private final String nodeLabel;
    private final String leftValue;
    private final String rightValue;
    private final ChangeType changeType;
    private final List<DiffNode> children;
    private final boolean structuralBridge;
    private final String structureHint;
    /**
     * Indices of element children from {@link org.w3c.dom.Document#getDocumentElement()} down to the
     * DOM element this row represents (empty list = the document root element). {@code null} = not
     * mappable (filter messages, etc.). Attribute and #text rows use the parent element's path.
     */
    private final List<Integer> elementPath;

    private DiffNode(String nodeLabel, String leftValue, String rightValue, ChangeType changeType,
                     List<DiffNode> children, boolean structuralBridge, String structureHint,
                     List<Integer> elementPath) {
        this.nodeLabel = nodeLabel;
        this.leftValue = leftValue != null ? leftValue : "";
        this.rightValue = rightValue != null ? rightValue : "";
        this.changeType = changeType;
        this.children = children != null ? List.copyOf(children) : List.of();
        this.structuralBridge = structuralBridge;
        this.structureHint = structureHint != null ? structureHint : "";
        this.elementPath = elementPath == null ? null : List.copyOf(elementPath);
    }

    public static DiffNode leaf(String nodeLabel, String leftValue, String rightValue, ChangeType changeType) {
        return leaf(nodeLabel, leftValue, rightValue, changeType, null);
    }

    public static DiffNode leaf(String nodeLabel, String leftValue, String rightValue, ChangeType changeType,
                                List<Integer> elementPath) {
        return new DiffNode(nodeLabel, leftValue, rightValue, changeType, List.of(), false, "", elementPath);
    }

    public static DiffNode branch(String nodeLabel, String leftValue, String rightValue, ChangeType changeType,
                                  List<DiffNode> children, boolean structuralBridge, String structureHint,
                                  List<Integer> elementPath) {
        return new DiffNode(nodeLabel, leftValue, rightValue, changeType, children, structuralBridge,
                structureHint, elementPath);
    }

    public String getNodeLabel() {
        return nodeLabel;
    }

    public String getLeftValue() {
        return leftValue;
    }

    public String getRightValue() {
        return rightValue;
    }

    public ChangeType getChangeType() {
        return changeType;
    }

    public List<DiffNode> getChildren() {
        return children;
    }

    public boolean isStructuralBridge() {
        return structuralBridge;
    }

    public String getStructureHint() {
        return structureHint;
    }

    /**
     * @return path indices, or {@code null} if this row cannot be mapped to a DOM element subtree
     */
    public List<Integer> getElementPath() {
        return elementPath;
    }

    public boolean canCopyElementSubtree() {
        return elementPath != null;
    }
}
