package com.licode.team;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.licode.conversation.ConversationManager;
import com.licode.conversation.Message;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ConversationStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path baseDir;

    public ConversationStore(Path teamDir) {
        this.baseDir = teamDir.resolve("conversations");
        try {
            Files.createDirectories(baseDir);
        } catch (IOException ignored) {
        }
    }

    public void save(String memberName, ConversationManager conv) throws IOException {
        List<Message> messages = new ArrayList<>(conv.getMessagesInternal());
        Path file = baseDir.resolve(memberName + ".json");
        MAPPER.writeValue(file.toFile(), new ConversationData(memberName, messages));
    }

    public ConversationManager load(String memberName) throws IOException {
        Path file = baseDir.resolve(memberName + ".json");
        if (!Files.exists(file)) {
            return new ConversationManager();
        }
        ConversationData data = MAPPER.readValue(file.toFile(), ConversationData.class);
        var conv = new ConversationManager();
        for (Message msg : data.messages) {
            conv.getMessagesMutable().add(msg);
        }
        return conv;
    }

    public void delete(String memberName) throws IOException {
        Path file = baseDir.resolve(memberName + ".json");
        Files.deleteIfExists(file);
    }

    public boolean exists(String memberName) {
        return Files.exists(baseDir.resolve(memberName + ".json"));
    }

    public List<String> listMembers() {
        try (var stream = Files.list(baseDir)) {
            return stream
                    .filter(p -> p.toString().endsWith(".json"))
                    .map(p -> p.getFileName().toString().replace(".json", ""))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public static class ConversationData {
        public String memberName;
        public List<Message> messages;

        public ConversationData() {}

        public ConversationData(String memberName, List<Message> messages) {
            this.memberName = memberName;
            this.messages = messages;
        }
    }
}
