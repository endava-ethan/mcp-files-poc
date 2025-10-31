package dev.poc.files.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Basic HTTP authentication for the MVC MCP transport.
 */
@Configuration
@EnableConfigurationProperties(McpSecurityProperties.class)
public class SecurityConfig {

	@Bean
	public UserDetailsService userDetailsService(McpSecurityProperties securityProperties) {
		UserDetails user = User.withUsername(securityProperties.username())
			.password("{noop}" + securityProperties.password())
			.roles("MCP_CLIENT")
			.build();
		return new InMemoryUserDetailsManager(user);
	}

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http.csrf(AbstractHttpConfigurer::disable);
		http.authorizeHttpRequests(registry -> registry.anyRequest().authenticated());
		http.httpBasic(Customizer.withDefaults());
		return http.build();
	}

}
