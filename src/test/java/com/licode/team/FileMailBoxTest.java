package com.licode.team;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class FileMailBoxTest {

    @TempDir
    Path tempDir;

    @Test
    void sendCreatesFileWithMessage() throws IOException {
        var mb = new FileMailBox(tempDir);
        mb.send("alice", new MailMessage("lead", "alice", "Hello Alice"));
        List<MailMessage> unread = mb.readUnread("alice");
        assertEquals(1, unread.size());
        assertEquals("lead", unread.get(0).from());
        assertEquals("Hello Alice", unread.get(0).text());
        assertFalse(unread.get(0).read());
    }

    @Test
    void readUnreadReturnsOnlyUnread() throws IOException {
        var mb = new FileMailBox(tempDir);
        mb.send("alice", new MailMessage("lead", "alice", "msg 1"));
        mb.send("alice", new MailMessage("bob", "alice", "msg 2"));
        mb.markAllRead("alice");
        mb.send("alice", new MailMessage("charlie", "alice", "msg 3"));
        List<MailMessage> unread = mb.readUnread("alice");
        assertEquals(1, unread.size());
        assertEquals("msg 3", unread.get(0).text());
    }

    @Test
    void markAllReadMakesUnreadEmpty() throws IOException {
        var mb = new FileMailBox(tempDir);
        mb.send("alice", new MailMessage("lead", "alice", "msg 1"));
        mb.send("alice", new MailMessage("bob", "alice", "msg 2"));
        mb.markAllRead("alice");
        List<MailMessage> unread = mb.readUnread("alice");
        assertTrue(unread.isEmpty());
    }

    @Test
    void nonexistentAgentReturnsEmpty() throws IOException {
        var mb = new FileMailBox(tempDir);
        List<MailMessage> unread = mb.readUnread("nonexistent");
        assertTrue(unread.isEmpty());
    }

    @Test
    void sendForcesReadToFalse() throws IOException {
        var mb = new FileMailBox(tempDir);
        mb.send("alice", new MailMessage("lead", "alice", "msg", "t", true, null, null, null));
        List<MailMessage> unread = mb.readUnread("alice");
        assertEquals(1, unread.size());
        assertFalse(unread.get(0).read());
    }

    @Test
    void concurrentSendsDoNotCorrupt() throws Exception {
        var mb = new FileMailBox(tempDir);
        int threads = 4;
        int msgsPerThread = 25;
        var latch = new CountDownLatch(threads);
        try (ExecutorService exec = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                final int tid = t;
                exec.submit(() -> {
                    try {
                        for (int i = 0; i < msgsPerThread; i++) {
                            mb.send("alice", new MailMessage("sender" + tid, "alice", "msg " + tid + "-" + i));
                        }
                    } catch (IOException ignored) {
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
        }
        List<MailMessage> unread = mb.readUnread("alice");
        assertEquals(threads * msgsPerThread, unread.size());
    }

    @Test
    void protocolMessageFactoryMethods() {
        var idle = MailMessage.idle("reviewer", "completed code review");
        assertEquals(com.licode.team.MailMessage.ProtocolMessageType.IDLE, idle.type());
        assertTrue(idle.text().startsWith("[idle]"));

        var shutdown = MailMessage.shutdown("work complete");
        assertEquals(com.licode.team.MailMessage.ProtocolMessageType.SHUTDOWN, shutdown.type());

        var req = MailMessage.approvalRequest("alice", "WriteFile", "modify config.yaml");
        assertEquals(com.licode.team.MailMessage.ProtocolMessageType.APPROVAL_REQUEST, req.type());
        assertTrue(req.text().contains("WriteFile"));

        var resp = MailMessage.approvalResponse("alice", true, "looks good");
        assertEquals(com.licode.team.MailMessage.ProtocolMessageType.APPROVAL_RESPONSE, resp.type());

        var prog = MailMessage.progress("bob", 5, 1200, "running");
        assertEquals(com.licode.team.MailMessage.ProtocolMessageType.PROGRESS, prog.type());

        var err = MailMessage.error("charlie", "NullPointerException");
        assertEquals(com.licode.team.MailMessage.ProtocolMessageType.ERROR, err.type());

        var handoff = MailMessage.handoff("dave", "eve", "continue the parser");
        assertEquals(com.licode.team.MailMessage.ProtocolMessageType.HANDOFF, handoff.type());
    }
}
