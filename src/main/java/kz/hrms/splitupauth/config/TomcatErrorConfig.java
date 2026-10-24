package kz.hrms.splitupauth.config;

import org.apache.catalina.valves.ErrorReportValve;
import org.apache.coyote.ActionCode;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Configuration;

/**
 * C6: replaces Tomcat's default HTML error page with a minimal JSON body so clients hitting
 * malformed URLs, oversized headers or other connector-level rejects get the same structured format
 * the application's {@link kz.hrms.splitupauth.exception.GlobalExceptionHandler} uses.
 */
@Configuration
public class TomcatErrorConfig
    implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

  @Override
  public void customize(TomcatServletWebServerFactory factory) {
    factory.addContextCustomizers(
        context -> {
          ErrorReportValve valve = new JsonErrorReportValve();
          valve.setShowReport(false);
          valve.setShowServerInfo(false);
          context.getParent().getPipeline().addValve(valve);
        });
  }

  static class JsonErrorReportValve extends ErrorReportValve {
    @Override
    protected void report(
        org.apache.catalina.connector.Request request,
        org.apache.catalina.connector.Response response,
        Throwable throwable) {
      int status = response.getStatus();
      if (status < 400 || response.isCommitted()) {
        return;
      }
      String code = codeFor(status);
      String body = "{\"code\":\"" + code + "\",\"status\":" + status + "}";
      try {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(body);
        response.getCoyoteResponse().action(ActionCode.CLOSE, null);
      } catch (Exception ignored) {
        // Best effort — the connection may already be broken.
      }
    }

    private static String codeFor(int status) {
      return switch (status) {
        case 400 -> "BAD_REQUEST";
        case 401 -> "UNAUTHORIZED";
        case 403 -> "FORBIDDEN";
        case 404 -> "NOT_FOUND";
        case 405 -> "METHOD_NOT_ALLOWED";
        case 408 -> "REQUEST_TIMEOUT";
        case 413 -> "PAYLOAD_TOO_LARGE";
        case 414 -> "URI_TOO_LONG";
        case 415 -> "UNSUPPORTED_MEDIA_TYPE";
        case 429 -> "TOO_MANY_REQUESTS";
        case 500 -> "INTERNAL_SERVER_ERROR";
        case 502 -> "BAD_GATEWAY";
        case 503 -> "SERVICE_UNAVAILABLE";
        default -> "HTTP_" + status;
      };
    }
  }
}
