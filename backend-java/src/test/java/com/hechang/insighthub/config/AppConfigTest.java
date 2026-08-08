package com.hechang.insighthub.config;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AppConfigTest {

    @Test
    void emptyInternalTokenFailsFast() {
        AgentProperties properties = new AgentProperties();
        properties.setInternalToken(" ");

        assertThrows(
                IllegalStateException.class,
                () -> new AppConfig().agentWebClient(properties));
    }
}
