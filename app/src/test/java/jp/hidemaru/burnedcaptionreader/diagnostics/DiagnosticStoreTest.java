package jp.hidemaru.burnedcaptionreader.diagnostics;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class DiagnosticStoreTest {
    private Map<String, byte[]> exported(DiagnosticStore store) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        store.export(output, "{\"schema_version\":1}");
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) files.put(entry.getName(), zip.readAllBytes());
        }
        return files;
    }
    @Test public void exportsExactImageAndOrderedUnicodeEvents() throws Exception {
        File directory = Files.createTempDirectory("diagnostic-test").toFile();
        DiagnosticStore store = new DiagnosticStore(directory, 120000, 1000000, 100);
        byte[] image = {1, 2, 3, 4};
        store.write("0001", 1000, "{\"text\":\"字幕…\",\"image\":\"images/0001.png\"}", image);
        store.write("0002", 1100, "{\"type\":\"tts_start\"}", null);
        Map<String, byte[]> files = exported(store);
        assertTrue(Arrays.equals(image, files.get("images/0001.png")));
        assertEquals("{\"text\":\"字幕…\",\"image\":\"images/0001.png\"}\n{\"type\":\"tts_start\"}\n",
                new String(files.get("events.jsonl"), StandardCharsets.UTF_8));
        assertTrue(files.containsKey("manifest.json"));
        store.clear(); directory.delete();
    }
    @Test public void expiresImageAndItsRecordTogether() throws Exception {
        File directory = Files.createTempDirectory("diagnostic-age").toFile();
        DiagnosticStore store = new DiagnosticStore(directory, 500, 1000000, 100);
        store.write("0001", 1000, "{}", new byte[]{1});
        store.write("0002", 2000, "{}", new byte[]{2});
        Map<String, byte[]> files = exported(store);
        assertTrue(!files.containsKey("images/0001.png"));
        assertTrue(files.containsKey("images/0002.png"));
        assertEquals("{}\n", new String(files.get("events.jsonl"), StandardCharsets.UTF_8));
        store.clear(); directory.delete();
    }
    @Test public void obeysByteAndRecordLimits() throws Exception {
        File directory = Files.createTempDirectory("diagnostic-size").toFile();
        DiagnosticStore store = new DiagnosticStore(directory, 999999, 20, 2);
        for (int i = 1; i <= 10; i++) store.write(String.format("%04d", i), i * 1000, "{}", new byte[10]);
        Map<String, byte[]> files = exported(store);
        assertTrue(files.containsKey("images/0010.png"));
        assertEquals(1L, files.keySet().stream().filter(name -> name.startsWith("images/")).count());
        store.clear(); directory.delete();
    }
    @Test public void canExportStoppedSessionAfterProcessRestart() throws Exception {
        File directory = Files.createTempDirectory("diagnostic-reopen").toFile();
        new DiagnosticStore(directory, 500, 1000000, 100).write("0001", 1000, "{}", new byte[]{1});
        DiagnosticStore reopened = new DiagnosticStore(directory, 500, 1000000, 100);
        assertTrue(exported(reopened).containsKey("images/0001.png"));
        reopened.clear(); directory.delete();
    }
    @Test public void clearRemovesImagesAndUnfinishedRecords() throws Exception {
        File directory = Files.createTempDirectory("diagnostic-clear").toFile();
        DiagnosticStore store = new DiagnosticStore(directory, 500, 1000000, 100);
        store.write("0001", 1000, "{}", new byte[]{1});
        Files.write(new File(directory, "orphan.tmp").toPath(), new byte[]{2});
        store.clear();
        assertEquals(0, directory.list().length);
        try { exported(store); throw new AssertionError("Empty export must fail"); }
        catch (IOException expected) { }
        directory.delete();
    }
    @Test public void propagatesExportFailureForUiReporting() throws Exception {
        File directory = Files.createTempDirectory("diagnostic-failure").toFile();
        DiagnosticStore store = new DiagnosticStore(directory, 500, 1000000, 100);
        store.write("0001", 1000, "{}", null);
        try {
            store.export(new OutputStream() { public void write(int value) throws IOException { throw new IOException("full"); } }, "{}");
            throw new AssertionError("Write failure must propagate");
        } catch (IOException expected) { }
        store.clear(); directory.delete();
    }
}
