package kz.hrms.splitupauth.payment.gateway.freedom;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Low-level HTTP client for Freedom Pay (PayBox) classic API.
 *
 * Endpoints used:
 *  - POST /init_payment.php       — create one-time payment, get redirect URL
 *  - POST /v1/merchant/{id}/payment/init  — modern API alternative
 *  - POST /v1/merchant/{id}/payment/{ext_id}/cancel
 *  - POST /v1/merchant/{id}/payment/{ext_id}/refund
 *  - POST /v1/merchant/{id}/payouts        — payout to card
 *  - POST /get_status.php          — query payment status
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class FreedomPayClient {

    private final FreedomPayProperties properties;
    private RestClient restClient;

    @PostConstruct
    public void initRestClient() {
        this.restClient = RestClient.builder().baseUrl(properties.getBaseUrl()).build();
    }

    /**
     * Send form-urlencoded params and parse XML response into a map.
     */
    public Map<String, String> postForm(String path, Map<String, String> params) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        params.forEach(form::add);

        try {
            String body = restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_XML, MediaType.TEXT_XML)
                    .body(form)
                    .retrieve()
                    .body(String.class);

            return FreedomPayXmlParser.parseFlatXml(body);
        } catch (Exception ex) {
            log.error("Freedom Pay request to {} failed: {}", path, ex.getMessage());
            throw new FreedomPayException(
                    "Freedom Pay request failed: " + ex.getMessage(), ex);
        }
    }
}
