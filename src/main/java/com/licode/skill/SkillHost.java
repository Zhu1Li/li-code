package com.licode.skill;

import com.licode.tool.ToolRegistry;
import java.util.Set;
import java.util.function.Predicate;

public interface SkillHost {

    void activateSkill(String name, String body);

    void deactivateSkill(String name);

    void clearActiveSkills();

    Set<String> getActiveSkillNames();

    String buildActiveSkillsContext();

    void setToolFilter(Predicate<String> filter);

    ToolRegistry toolRegistry();
}
