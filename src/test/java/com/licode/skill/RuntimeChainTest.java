package com.licode.skill;

import com.licode.command.CommandRegistry;
import com.licode.runtime.LiRuntime;
import com.licode.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeChainTest {

    /** Simulates the full LiCodeModel initialization chain. */
    @Test
    void testFullRuntimeChain() {
        // This creates a LiRuntime WITHOUT a real ProviderConfig
        // We just need to verify the SkillCatalog + CommandRegistry wiring
        
        Path workDir = Path.of(System.getProperty("user.dir"));
        var catalog = new SkillCatalog();
        catalog.loadCatalog(workDir);
        
        System.out.println("=== Runtime chain simulation ===");
        System.out.println("Working dir: " + workDir);
        
        var list = catalog.list();
        System.out.println("Skills loaded: " + list.size());
        for (var m : list) {
            System.out.println("  " + m.name() + " — " + m.description()
                    + " [source=" + catalog.source(m.name()) + "]"
                    + " [mode=" + m.mode() + "]");
        }
        
        // Simulate wireSkillsToCommands (uses registerSkillCommand which handles overrides)
        var cmdRegistry = new CommandRegistry();
        int registered = 0;
        for (var meta : list) {
            String name = meta.name();
            String desc = meta.description();
            if (desc == null || desc.isBlank()) desc = name;
            cmdRegistry.registerSkillCommand(name, desc, () -> {
                var skill = catalog.getFull(name);
                return skill.map(Skill::promptBody).orElse(null);
            });
            registered++;
            System.out.println("  REGISTERED " + name + " as skill command");
        }
        System.out.println("Skills registered as commands: " + registered);
        
        // Simulate /skills command
        var skills = new ArrayList<String>();
        for (var c : cmdRegistry.listVisible()) {
            if (c.description() != null && c.description().endsWith("[skill]")) {
                skills.add(c.name());
            }
        }
        System.out.println("Skill commands visible: " + skills);
        
        assertEquals(4, list.size(), "Expected 4 builtin skills loaded (incl. fix-tests)");
        assertEquals(4, skills.size(), "Expected all 4 skills visible");
        assertTrue(skills.contains("commit"));
        assertTrue(skills.contains("review"));
        assertTrue(skills.contains("test"));
        assertTrue(skills.contains("fix-tests"));
    }
}
