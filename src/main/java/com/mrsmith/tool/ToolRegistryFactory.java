package com.mrsmith.tool;

import com.mrsmith.config.AgentRuntime;
import com.mrsmith.io.IO;
import com.mrsmith.skill.SkillCatalog;

public interface ToolRegistryFactory {

    ToolRegistry create(AgentRuntime runtime, SkillCatalog catalog, IO io, TaskRunner taskRunner);
}
