package kz.hrms.splitupauth.payment.gateway.freedom;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Hardened parser for FreedomPay XML responses and {@code pg_xml} callbacks.
 *
 * <p>XXE is disabled (no DOCTYPE, no external entities, no XInclude) and the input size is capped
 * before parsing, so a hostile or broken upstream cannot exhaust memory. {@link
 * #parseMessage(String)} keeps repeated and nested elements for signature verification; {@link
 * #parseFlatXml(String)} is the legacy first-value view used by business code.
 */
public final class FreedomPayXmlParser {

  /** Generous for any documented response (card lists included), small enough to be harmless. */
  public static final int MAX_XML_CHARS = 256 * 1024;

  private static final int MAX_DEPTH = 8;

  private FreedomPayXmlParser() {}

  public static FreedomPayMessage parseMessage(String xml) {
    if (xml == null || xml.isBlank()) return new FreedomPayMessage(List.of());
    if (xml.length() > MAX_XML_CHARS) {
      throw new FreedomPayException("Freedom Pay XML response exceeds size limit");
    }
    try {
      Document doc =
          newBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
      return new FreedomPayMessage(children(doc.getDocumentElement(), 0));
    } catch (FreedomPayException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new FreedomPayException(
          "Failed to parse Freedom Pay XML response: " + ex.getClass().getSimpleName(), ex);
    }
  }

  public static Map<String, String> parseFlatXml(String xml) {
    return parseMessage(xml).firstValues();
  }

  private static List<FreedomPayMessage.Field> children(Element parent, int depth) {
    if (depth > MAX_DEPTH) {
      throw new FreedomPayException("Freedom Pay XML response is nested too deeply");
    }
    List<FreedomPayMessage.Field> fields = new ArrayList<>();
    NodeList nodes = parent.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      Node n = nodes.item(i);
      if (n.getNodeType() != Node.ELEMENT_NODE) continue;
      Element e = (Element) n;
      if (hasElementChildren(e)) {
        fields.add(FreedomPayMessage.Field.nested(e.getNodeName(), children(e, depth + 1)));
      } else {
        fields.add(FreedomPayMessage.Field.leaf(e.getNodeName(), e.getTextContent().trim()));
      }
    }
    return fields;
  }

  private static boolean hasElementChildren(Element e) {
    NodeList nodes = e.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      if (nodes.item(i).getNodeType() == Node.ELEMENT_NODE) return true;
    }
    return false;
  }

  /**
   * Parse a cardstorage/list response into one map per saved card. The card element name is not
   * relied upon — any element that has a {@code pg_card_token} or {@code pg_recurring_profile_id}
   * child is treated as a card, and its child elements are flattened into the map.
   */
  public static List<Map<String, String>> parseCardList(String xml) {
    List<Map<String, String>> cards = new ArrayList<>();
    if (xml == null || xml.isBlank() || xml.length() > MAX_XML_CHARS) return cards;
    try {
      Document doc =
          newBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

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

  private static DocumentBuilder newBuilder() throws ParserConfigurationException {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    return factory.newDocumentBuilder();
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
