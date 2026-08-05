package com.mrsmith.tool;

import com.mrsmith.config.AppConfig;
import com.mrsmith.skill.SkillCatalog;

public interface ToolRegistryFactory {

    ToolRegistry create(AppConfig config, SkillCatalog catalog);
}
