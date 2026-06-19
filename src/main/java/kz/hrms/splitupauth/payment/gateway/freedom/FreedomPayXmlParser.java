package kz.hrms.splitupauth.payment.gateway.freedom;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Freedom Pay returns flat XML responses like:
 *   <response>
 *     <pg_status>ok</pg_status>
 *     <pg_payment_id>12345</pg_payment_id>
 *     <pg_redirect_url>...</pg_redirect_url>
 *   </response>
 *
 * This parser flattens the first-level children into a Map.
 */
public final class FreedomPayXmlParser {

    private FreedomPayXmlParser() {}

    public static Map<String, String> parseFlatXml(String xml) {
        if (xml == null || xml.isBlank()) return Map.of();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Disable XXE.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(
                    xml.getBytes(StandardCharsets.UTF_8)));

            Map<String, String> result = new LinkedHashMap<>();
            Node root = doc.getDocumentElement();
            NodeList children = root.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node n = children.item(i);
                if (n.getNodeType() == Node.ELEMENT_NODE) {
                    result.put(n.getNodeName(), n.getTextContent().trim());
                }
            }
            return result;
        } catch (Exception ex) {
            throw new FreedomPayException(
                    "Failed to parse Freedom Pay XML response: " + ex.getMessage(), ex);
        }
    }

    /**
     * Parse a cardstorage/list response into one map per saved card. The card element name is
     * not relied upon — any element that has a {@code pg_card_token} or {@code pg_recurring_profile_id}
     * child is treated as a card, and its child elements are flattened into the map.
     */
    public static List<Map<String, String>> parseCardList(String xml) {
        List<Map<String, String>> cards = new ArrayList<>();
        if (xml == null || xml.isBlank()) return cards;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            Set<Node> cardNodes = new LinkedHashSet<>();
            collectParents(doc.getElementsByTagName("pg_recurring_profile_id"), cardNodes);
            collectParents(doc.getElementsByTagName("pg_card_token"), cardNodes);

            for (Node card : cardNodes) {
                Map<String, String> m = new LinkedHashMap<>();
                NodeList ch = card.getChildNodes();
                for (int i = 0; i < ch.getLength(); i++) {
                    Node n = ch.item(i);
                    if (n.getNodeType() == Node.ELEMENT_NODE) {
                        m.put(n.getNodeName(), n.getTextContent().trim());
                    }
                }
                if (!m.isEmpty()) cards.add(m);
            }
            return cards;
        } catch (Exception ex) {
            // Best-effort: a parse failure just yields no cards (caller treats as "not found").
            return cards;
        }
    }

    private static void collectParents(NodeList nodes, Set<Node> out) {
        for (int i = 0; i < nodes.getLength(); i++) {
            Node parent = nodes.item(i).getParentNode();
            if (parent != null && parent.getNodeType() == Node.ELEMENT_NODE) {
                out.add(parent);
            }
        }
    }
}
