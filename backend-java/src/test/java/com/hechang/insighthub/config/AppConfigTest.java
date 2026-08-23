package com.hechang.insighthub.config;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AppConfigTest {

    @Test
    void emptyInternalTokenFailsFast() {
        AgentProperties properties = new AgentProperties();
        properties.setBaseUrl("http://127.0.0.1:8000");
        properties.setInternalToken(" ");

        assertThrows(
                IllegalStateException.class,
                () -> new AppConfig().agentWebClient(properties));
    }

    @Test
    void emptyAgentUrlFailsFast() {
        AgentProperties properties = new AgentProperties();
        properties.setInternalToken("test-token");

        assertThrows(
                IllegalStateException.class,
                () -> new AppConfig().agentWebClient(properties));
    }
}
