package kz.hrms.splitupauth.websocket;

import kz.hrms.splitupauth.config.CorsProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

  private final WebSocketAuthHandshakeInterceptor authHandshakeInterceptor;
  private final WebSocketAuthChannelInterceptor authChannelInterceptor;
  private final CorsProperties corsProperties;
  private final WebSocketSessionRegistry sessionRegistry;

  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    var registration = registry.addEndpoint("/ws").addInterceptors(authHandshakeInterceptor);

    if (corsProperties.getAllowedOrigins().isEmpty()) {
      registration.setAllowedOriginPatterns("*");
    } else {
      registration.setAllowedOrigins(corsProperties.getAllowedOrigins().toArray(String[]::new));
    }
  }

  @Override
  public void configureMessageBroker(MessageBrokerRegistry registry) {
    registry.enableSimpleBroker("/topic");
    registry.setApplicationDestinationPrefixes("/app");
  }

  @Override
  public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
    // Server-push socket: inbound frames are tiny (CONNECT/SUBSCRIBE), so bound them tightly, and
    // drop a client that cannot keep up instead of buffering unboundedly for it.
    registration.addDecoratorFactory(sessionRegistry);
    registration.setMessageSizeLimit(16 * 1024);
    registration.setSendBufferSizeLimit(512 * 1024);
    registration.setSendTimeLimit(15_000);
  }

  @Override
  public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(authChannelInterceptor);
  }
}
