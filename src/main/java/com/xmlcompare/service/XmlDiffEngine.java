package com.xmlcompare.service;

import com.xmlcompare.model.ChangeType;
import com.xmlcompare.model.DiffNode;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public final class XmlDiffEngine {

    private XmlDiffEngine() {
    }

    public static Document parse(String xml) throws XmlParseException {
        if (xml == null || xml.isBlank()) {
            throw new XmlParseException("XML content is empty.");
        }
        String normalized = normalizeXmlText(xml);
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setIgnoringComments(false);
            factory.setIgnoringElementContentWhitespace(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new StrictErrorHandler());
            return builder.parse(new ByteArrayInputStream(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (ParserConfigurationException e) {
            throw new XmlParseException("Parser configuration error: " + e.getMessage(), e);
        } catch (SAXException e) {
            throw new XmlParseException(formatSaxMessage(e), e);
        } catch (IOException e) {
            throw new XmlParseException("Could not read XML: " + e.getMessage(), e);
        }
    }

    /**
     * Removes a UTF-8 BOM and other common leading marks so parsers see a clean declaration or root.
     */
    public static String normalizeXmlText(String xml) {
        if (xml == null || xml.isEmpty()) {
            return xml;
        }
        int start = 0;
        if (xml.charAt(0) == '\uFEFF') {
            start = 1;
        } else if (xml.length() >= 3 && xml.charAt(0) == '\u00EF' && xml.charAt(1) == '\u00BB' && xml.charAt(2) == '\u00BF') {
            start = 3;
        }
        return start > 0 ? xml.substring(start) : xml;
    }

    private static String formatSaxMessage(SAXException e) {
        if (e instanceof SAXParseException spe) {
            int line = spe.getLineNumber();
            int col = spe.getColumnNumber();
            String loc = (line >= 0)
                    ? String.format("Line %d, column %d: ", line, Math.max(col, 0))
                    : "";
            String msg = spe.getMessage() != null ? spe.getMessage() : e.getClass().getSimpleName();
            return loc + msg + " Tip: in text, escape < as &lt;, & as &amp;.";
        }
        return "Invalid XML: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
    }

    public static Document parseFile(Path path) throws XmlParseException {
        try {
            return parse(readFileText(path));
        } catch (IOException e) {
            throw new XmlParseException("Could not read file: " + e.getMessage(), e);
        }
    }

    /**
     * Reads a file as text for XML loading. Handles UTF-8 (with or without BOM), UTF-16 LE/BE (with BOM),
     * UTF-16 LE without BOM (common for Windows Notepad “Unicode”), then Windows-1252 as a byte-preserving fallback.
     */
    public static String readFileText(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        return decodeXmlFileBytes(bytes);
    }

    static String decodeXmlFileBytes(byte[] b) {
        if (b.length == 0) {
            return "";
        }
        if (b.length >= 3 && b[0] == (byte) 0xEF && b[1] == (byte) 0xBB && b[2] == (byte) 0xBF) {
            return new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
        }
        if (b.length >= 2 && b[0] == (byte) 0xFF && b[1] == (byte) 0xFE) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        }
        if (b.length >= 2 && b[0] == (byte) 0xFE && b[1] == (byte) 0xFF) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        }

        CharsetDecoder utf8 = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer decoded = utf8.decode(ByteBuffer.wrap(b));
            return decoded.toString();
        } catch (CharacterCodingException e) {
            if (b.length % 2 == 0 && b.length >= 4 && looksLikeUtf16LeAsciiPrefix(b)) {
                return new String(b, StandardCharsets.UTF_16LE);
            }
            return new String(b, Charset.forName("Windows-1252"));
        }
    }

    /**
     * UTF-16 LE encoding of ASCII uses a 0 high byte after each ASCII character.
     */
    private static boolean looksLikeUtf16LeAsciiPrefix(byte[] b) {
        int pairs = Math.min(b.length / 2, 32);
        int zeroHigh = 0;
        for (int i = 0; i < pairs; i++) {
            if (b[i * 2 + 1] == 0 && b[i * 2] >= 0x20 && b[i * 2] < 0x7F) {
                zeroHigh++;
            }
        }
        return zeroHigh >= 3;
    }

    public static DiffNode compareDocuments(Document left, Document right) {
        Element lRoot = left.getDocumentElement();
        Element rRoot = right.getDocumentElement();
        if (lRoot == null && rRoot == null) {
            return DiffNode.leaf("(empty document)", "", "", ChangeType.EQUAL);
        }
        if (lRoot == null) {
            return elementDiff(null, rRoot, "", List.of());
        }
        if (rRoot == null) {
            return elementDiff(lRoot, null, "", List.of());
        }
        return elementDiff(lRoot, rRoot, "", List.of());
    }

    private static DiffNode elementDiff(Element left, Element right, String path, List<Integer> structuralPath) {
        String label;
        ChangeType nameType = ChangeType.EQUAL;
        if (left != null && right != null) {
            String ln = qualifiedName(left);
            String rn = qualifiedName(right);
            label = ln;
            if (!ln.equals(rn)) {
                nameType = ChangeType.MODIFIED;
                label = ln + "  →  " + rn;
            }
        } else if (left != null) {
            label = qualifiedName(left);
            nameType = ChangeType.LEFT_ONLY;
        } else {
            label = qualifiedName(right);
            nameType = ChangeType.RIGHT_ONLY;
        }

        String currentPath = path.isEmpty() ? label : path + " / " + (left != null ? qualifiedName(left) : qualifiedName(right));

        List<DiffNode> parts = new ArrayList<>();

        if (left != null && right != null) {
            parts.addAll(attributeDiffs(left, right, structuralPath));
        } else if (left != null) {
            parts.addAll(attributesLeftOnly(left, structuralPath));
        } else {
            parts.addAll(attributesRightOnly(right, structuralPath));
        }

        String leftText = left != null ? directTextContent(left) : "";
        String rightText = right != null ? directTextContent(right) : "";
        List<Element> leftKids = left != null ? childElements(left) : List.of();
        List<Element> rightKids = right != null ? childElements(right) : List.of();

        boolean hasElementChildren = !leftKids.isEmpty() || !rightKids.isEmpty();
        ChangeType textType = ChangeType.EQUAL;
        if (!leftText.equals(rightText)) {
            textType = leftText.isEmpty() != rightText.isEmpty()
                    ? (leftText.isEmpty() ? ChangeType.RIGHT_ONLY : ChangeType.LEFT_ONLY)
                    : ChangeType.MODIFIED;
        }

        if (!hasElementChildren && (left != null || right != null)) {
            if (!leftText.isEmpty() || !rightText.isEmpty()) {
                ChangeType rowType = aggregateChange(nameType, textType);
                parts.add(DiffNode.leaf("#text", leftText, rightText, rowType, structuralPath));
            }
        } else if (hasElementChildren) {
            if (!leftText.isEmpty() || !rightText.isEmpty()) {
                parts.add(DiffNode.leaf("#text (mixed)", leftText, rightText, textType, structuralPath));
            }
        }

        int max = Math.max(leftKids.size(), rightKids.size());
        for (int i = 0; i < max; i++) {
            Element lc = i < leftKids.size() ? leftKids.get(i) : null;
            Element rc = i < rightKids.size() ? rightKids.get(i) : null;
            List<Integer> childPath = new ArrayList<>(structuralPath);
            childPath.add(i);
            parts.add(elementDiff(lc, rc, currentPath, childPath));
        }

        ChangeType aggregate = nameType;
        for (DiffNode c : parts) {
            aggregate = escalate(aggregate, c.getChangeType());
        }

        String leftSummary = left != null ? briefElementSummary(left) : "";
        String rightSummary = right != null ? briefElementSummary(right) : "";

        String structureHint = elementStructureHint(left, right, leftKids.size(), rightKids.size());
        return DiffNode.branch(label, leftSummary, rightSummary, aggregate, parts, false, structureHint,
                structuralPath);
    }

    /**
     * Explains element-child counts on both sides so filtered “Ancestor” rows still show why parents differ.
     */
    private static String elementStructureHint(Element left, Element right, int leftChildCount, int rightChildCount) {
        if (left != null && right != null) {
            if (leftChildCount == 0 && rightChildCount == 0) {
                return "";
            }
            if (leftChildCount == rightChildCount) {
                return "Same element-child count (" + leftChildCount + ")";
            }
            if (leftChildCount > rightChildCount) {
                return "Left has more element children (" + leftChildCount + " vs " + rightChildCount + ")";
            }
            return "Right has more element children (" + rightChildCount + " vs " + leftChildCount + ")";
        }
        if (left != null) {
            return leftChildCount > 0
                    ? "Only on left · " + leftChildCount + " element children"
                    : "Only on left";
        }
        if (right != null) {
            return rightChildCount > 0
                    ? "Only on right · " + rightChildCount + " element children"
                    : "Only on right";
        }
        return "";
    }

    private static ChangeType aggregateChange(ChangeType a, ChangeType b) {
        if (a == ChangeType.MODIFIED || b == ChangeType.MODIFIED) {
            return ChangeType.MODIFIED;
        }
        if (a == b) {
            return a;
        }
        if (a == ChangeType.EQUAL) {
            return b;
        }
        if (b == ChangeType.EQUAL) {
            return a;
        }
        return ChangeType.MODIFIED;
    }

    private static ChangeType escalate(ChangeType parent, ChangeType child) {
        if (child == ChangeType.EQUAL) {
            return parent;
        }
        if (parent == ChangeType.EQUAL) {
            return child;
        }
        if (parent == child) {
            return parent;
        }
        return ChangeType.MODIFIED;
    }

    private static List<DiffNode> attributeDiffs(Element left, Element right, List<Integer> structuralPath) {
        Set<String> names = new TreeSet<>();
        NamedNodeMap la = left.getAttributes();
        NamedNodeMap ra = right.getAttributes();
        for (int i = 0; i < la.getLength(); i++) {
            names.add(la.item(i).getNodeName());
        }
        for (int i = 0; i < ra.getLength(); i++) {
            names.add(ra.item(i).getNodeName());
        }
        List<DiffNode> out = new ArrayList<>();
        for (String name : names) {
            String lv = left.hasAttribute(name) ? left.getAttribute(name) : null;
            String rv = right.hasAttribute(name) ? right.getAttribute(name) : null;
            if (lv == null && rv == null) {
                continue;
            }
            ChangeType t;
            if (lv == null) {
                t = ChangeType.RIGHT_ONLY;
            } else if (rv == null) {
                t = ChangeType.LEFT_ONLY;
            } else if (lv.equals(rv)) {
                t = ChangeType.EQUAL;
            } else {
                t = ChangeType.MODIFIED;
            }
            String display = "@" + name;
            out.add(DiffNode.leaf(display, lv != null ? lv : "", rv != null ? rv : "", t, structuralPath));
        }
        return out;
    }

    private static List<DiffNode> attributesLeftOnly(Element left, List<Integer> structuralPath) {
        List<DiffNode> out = new ArrayList<>();
        NamedNodeMap la = left.getAttributes();
        for (int i = 0; i < la.getLength(); i++) {
            String name = la.item(i).getNodeName();
            out.add(DiffNode.leaf("@" + name, left.getAttribute(name), "", ChangeType.LEFT_ONLY, structuralPath));
        }
        return out;
    }

    private static List<DiffNode> attributesRightOnly(Element right, List<Integer> structuralPath) {
        List<DiffNode> out = new ArrayList<>();
        NamedNodeMap ra = right.getAttributes();
        for (int i = 0; i < ra.getLength(); i++) {
            String name = ra.item(i).getNodeName();
            out.add(DiffNode.leaf("@" + name, "", right.getAttribute(name), ChangeType.RIGHT_ONLY, structuralPath));
        }
        return out;
    }

    private static String briefElementSummary(Element e) {
        StringBuilder sb = new StringBuilder();
        sb.append("<").append(qualifiedName(e));
        if (e.hasAttributes()) {
            sb.append(" …").append(e.getAttributes().getLength()).append(" attr");
        }
        sb.append(">");
        List<Element> kids = childElements(e);
        if (!kids.isEmpty()) {
            sb.append(" ").append(kids.size()).append(" children");
        }
        return sb.toString();
    }

    private static String qualifiedName(Element e) {
        String ns = e.getNamespaceURI();
        String prefix = e.getPrefix();
        String local = e.getLocalName();
        if (local == null || local.isEmpty()) {
            local = e.getTagName();
        }
        if (prefix != null && !prefix.isEmpty()) {
            return prefix + ":" + local;
        }
        if (ns != null && !ns.isEmpty() && (prefix == null || prefix.isEmpty())) {
            return "{" + ns + "}" + local;
        }
        return local;
    }

    private static String directTextContent(Element e) {
        StringBuilder sb = new StringBuilder();
        NodeList children = e.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Text t) {
                sb.append(t.getData());
            }
        }
        String s = sb.toString();
        if (s.isBlank()) {
            return "";
        }
        String collapsed = s.replaceAll("\\s+", " ").trim();
        if (collapsed.length() > 200) {
            return collapsed.substring(0, 197) + "…";
        }
        return collapsed;
    }

    private static List<Element> childElements(Element e) {
        List<Element> list = new ArrayList<>();
        NodeList nl = e.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                list.add((Element) n);
            }
        }
        return list;
    }

    public static final class XmlParseException extends Exception {
        public XmlParseException(String message) {
            super(message);
        }

        public XmlParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class StrictErrorHandler implements ErrorHandler {
        @Override
        public void warning(SAXParseException e) {
            // ignore recoverable warnings
        }

        @Override
        public void error(SAXParseException e) throws SAXException {
            throw e;
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXException {
            throw e;
        }
    }
}
