package com.licode.skill;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SkillCatalogTest {

    @Test
    void testLoadBuiltins() {
        var catalog = new SkillCatalog();
        catalog.loadCatalog(Path.of(System.getProperty("user.dir")));
        var list = catalog.list();
        System.out.println("Skills found: " + list.size());
        for (var m : list) {
            System.out.println("  " + m.name() + " — " + m.description()
                    + " [source=" + catalog.source(m.name()) + "]"
                    + " [mode=" + m.mode() + "]");
        }
        assertTrue(list.size() >= 3, "Expected at least 3 builtin skills, got " + list.size());
    }

    @Test
    void testGetFullCommit() {
        var catalog = new SkillCatalog();
        catalog.loadCatalog(Path.of(System.getProperty("user.dir")));
        var skill = catalog.getFull("commit");
        assertTrue(skill.isPresent(), "commit skill should be found");
        assertFalse(skill.get().promptBody().isBlank(), "commit body should not be empty");
    }

    @Test
    void testParseBuiltinCommit() {
        var catalog = new SkillCatalog();
        catalog.loadCatalog(Path.of(System.getProperty("user.dir")));
        var skill = catalog.getFull("commit");
        assertTrue(skill.isPresent());
        assertEquals("fork", skill.get().meta().mode());
        assertTrue(skill.get().meta().allowedTools().contains("Bash"));
    }
}
