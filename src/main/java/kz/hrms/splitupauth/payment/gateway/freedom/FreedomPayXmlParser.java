package kz.hrms.splitupauth.payment.gateway.freedom;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

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
}
