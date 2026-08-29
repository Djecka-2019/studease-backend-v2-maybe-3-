package tech.studease.studease.common.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import tech.studease.studease.common.config.properties.CorsProperties;
import tech.studease.studease.domain.sessions.TestSessionRepository;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

  private final CorsProperties corsProperties;
  private final TestSessionRepository testSessionRepository;

  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    String[] origins = corsProperties.allowedOriginPatterns().toArray(String[]::new);
    registry.addEndpoint("/ws").setAllowedOriginPatterns(origins);
    registry.addEndpoint("/ws").setAllowedOriginPatterns(origins).withSockJS();
  }

  @Override
  public void configureMessageBroker(MessageBrokerRegistry registry) {
    registry.enableSimpleBroker("/topic");
    registry.setApplicationDestinationPrefixes("/api/v1");
  }

  @Override
  public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(new StompInboundGuard(testSessionRepository));
  }
}
