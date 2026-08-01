package com.mrsmith.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoleTest {

    @Test
    void apiNamesAreLowercaseWireFormat() {
        assertEquals("system", Role.SYSTEM.apiName());
        assertEquals("user", Role.USER.apiName());
        assertEquals("assistant", Role.ASSISTANT.apiName());
    }

    @Test
    void chatMessageExposesWireFormatRoleName() {
        ChatMessage message = new ChatMessage(Role.USER, "hello");
        assertEquals("user", message.roleName());
        assertEquals("hello", message.content());
    }
}
