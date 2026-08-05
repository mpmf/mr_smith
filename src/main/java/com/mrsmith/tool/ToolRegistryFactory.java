package com.mrsmith.tool;

import com.mrsmith.config.AppConfig;

public interface ToolRegistryFactory {

    ToolRegistry create(AppConfig config);
}
