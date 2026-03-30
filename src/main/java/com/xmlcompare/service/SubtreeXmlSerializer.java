package com.xmlcompare.service;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

public final class SubtreeXmlSerializer {

    private SubtreeXmlSerializer() {
    }

    /**
     * Walks from {@link Document#getDocumentElement()} following child-element indices at each level.
     * Empty path returns the document element itself.
     */
    public static Element resolveElement(Document doc, List<Integer> path) {
        if (doc == null || path == null) {
            return null;
        }
        Element e = doc.getDocumentElement();
        if (e == null) {
            return null;
        }
        for (int idx : path) {
            List<Element> kids = childElements(e);
            if (idx < 0 || idx >= kids.size()) {
                return null;
            }
            e = kids.get(idx);
        }
        return e;
    }

    public static String serializePretty(Element element) throws TransformerException {
        if (element == null) {
            return "";
        }
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer t = tf.newTransformer();
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        t.setOutputProperty(OutputKeys.METHOD, "xml");
        t.setOutputProperty(OutputKeys.INDENT, "yes");
        t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        StringWriter sw = new StringWriter();
        t.transform(new DOMSource(element), new StreamResult(sw));
        return sw.toString().trim();
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
}
