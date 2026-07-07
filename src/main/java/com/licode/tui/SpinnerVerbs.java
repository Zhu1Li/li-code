package com.licode.tui;

import java.util.Map;
import java.util.random.RandomGenerator;

public final class SpinnerVerbs {

    private SpinnerVerbs() {}

    private static final String[] VERBS = {
        "Analyzing", "Baking", "Calculating", "Composing", "Computing",
        "Crafting", "Decoding", "Decompiling", "Deliberating", "Deriving",
        "Designing", "Digesting", "Evolving", "Exploring", "Fabricating",
        "Formulating", "Generating", "Hashing", "Imagining", "Inferring",
        "Integrating", "Marshalling", "Mewing", "Modeling", "Musing",
        "Navigating", "Optimizing", "Piecing", "Pondering", "Processing",
        "Proving", "Realizing", "Reasoning", "Refactoring", "Reflecting",
        "Rendering", "Resolving", "Searching", "Shaping", "Simplifying",
        "Sketching", "Solving", "Streaming", "Synthesizing", "Thinking",
        "Tokenizing", "Traversing", "Weaving", "Wiring", "Wrangling"
    };

    private static final Map<String, String> IRREGULAR_PAST = Map.ofEntries(
        Map.entry("Thinking", "Thought"),
        Map.entry("Simplifying", "Simplified"),
        Map.entry("Deriving", "Derived"),
        Map.entry("Realizing", "Realized"),
        Map.entry("Searching", "Searched"),
        Map.entry("Synthesizing", "Synthesized"),
        Map.entry("Wrangling", "Wrangled"),
        Map.entry("Piecing", "Pieced"),
        Map.entry("Solving", "Solved"),
        Map.entry("Refactoring", "Refactored"),
        Map.entry("Streaming", "Streamed"),
        Map.entry("Wiring", "Wired"),
        Map.entry("Navigating", "Navigated"),
        Map.entry("Evolving", "Evolved"),
        Map.entry("Shaping", "Shaped"),
        Map.entry("Baking", "Baked"),
        Map.entry("Composing", "Composed"),
        Map.entry("Crafting", "Crafted"),
        Map.entry("Decoding", "Decoded"),
        Map.entry("Designing", "Designed"),
        Map.entry("Exploring", "Explored"),
        Map.entry("Fabricating", "Fabricated"),
        Map.entry("Hashing", "Hashed"),
        Map.entry("Imagining", "Imagined"),
        Map.entry("Musing", "Mused"),
        Map.entry("Proving", "Proved"),
        Map.entry("Resolving", "Resolved"),
        Map.entry("Sketching", "Sketched"),
        Map.entry("Tokenizing", "Tokenized"),
        Map.entry("Traversing", "Traversed"),
        Map.entry("Weaving", "Wove")
    );

    public static String random() {
        return VERBS[RandomGenerator.getDefault().nextInt(VERBS.length)];
    }

    public static String pastTense(String verb) {
        String known = IRREGULAR_PAST.get(verb);
        if (known != null) return known;
        if (verb.endsWith("ing")) {
            String stem = verb.substring(0, verb.length() - 3);
            return stem + "ed";
        }
        return verb + "ed";
    }
}
