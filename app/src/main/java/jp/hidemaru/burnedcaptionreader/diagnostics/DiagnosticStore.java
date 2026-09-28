package jp.hidemaru.burnedcaptionreader.diagnostics;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Disk ring; owned by the recorder's single worker. Image and JSON expire together. */
public final class DiagnosticStore {
    private final File directory;
    private final long maxAgeMs;
    private final long maxBytes;
    private final int maxRecords;
    private final Deque<File> retained = new ArrayDeque<>();
    private long retainedBytes;
    private boolean loaded;

    public DiagnosticStore(File directory, long maxAgeMs, long maxBytes, int maxRecords) {
        this.directory = directory;
        this.maxAgeMs = maxAgeMs;
        this.maxBytes = maxBytes;
        this.maxRecords = maxRecords;
    }

    public void clear() throws IOException {
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("Cannot create diagnostics directory");
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Cannot list diagnostics directory");
        for (File file : files) if (file.isFile()) Files.delete(file.toPath());
        retained.clear(); retainedBytes = 0; loaded = true;
    }

    public void write(String id, long time, String json, byte[] png) throws IOException {
        if (!id.matches("[0-9]+")) throw new IOException("Invalid record id");
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("Cannot create diagnostics directory");
        if (!loaded) {
            for (File file : records()) {
                retained.addLast(file);
                retainedBytes += file.length() + image(file).length();
            }
            loaded = true;
        }
        File image = new File(directory, id + ".png");
        File record = new File(directory, id + ".json");
        File temporary = new File(directory, id + ".tmp");
        try {
            if (png != null) Files.write(image.toPath(), png);
            Files.write(temporary.toPath(), json.getBytes(StandardCharsets.UTF_8));
            Files.move(temporary.toPath(), record.toPath());
            if (!record.setLastModified(time)) throw new IOException("Cannot set record timestamp");
            retained.addLast(record);
            retainedBytes += record.length() + image.length();
            prune(time);
        } catch (IOException error) {
            temporary.delete(); image.delete(); record.delete();
            throw error;
        }
    }

    private File[] records() {
        File[] files = directory.listFiles((dir, name) -> name.matches("[0-9]+\\.json"));
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparing(File::getName));
        return files;
    }

    private File image(File record) {
        return new File(directory, record.getName().replace(".json", ".png"));
    }

    private void prune(long now) throws IOException {
        while (!retained.isEmpty()) {
            File file = retained.peekFirst();
            if (now - file.lastModified() <= maxAgeMs && retainedBytes <= maxBytes && retained.size() <= maxRecords) break;
            File png = image(file);
            long removedBytes = file.length() + png.length();
            Files.deleteIfExists(png.toPath());
            Files.delete(file.toPath());
            retained.removeFirst();
            retainedBytes -= removedBytes;
        }
    }

    public void export(OutputStream output, String manifest) throws IOException {
        File[] files = records();
        if (files.length == 0) throw new IOException("診断記録がありません。記録をオンにして動画を再生してください");
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifest.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("events.jsonl"));
            for (File file : files) {
                Files.copy(file.toPath(), zip);
                zip.write('\n');
            }
            zip.closeEntry();
            for (File file : files) {
                File png = image(file);
                if (!png.isFile()) continue;
                zip.putNextEntry(new ZipEntry("images/" + png.getName()));
                Files.copy(png.toPath(), zip);
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("README.txt"));
            zip.write(("events.jsonl contains ordered JSON records. mono_ms is elapsedRealtime, not YouTube playback time.\n"
                    + "frame_id joins detection/refinement/selection records; event_id joins commits and decisions.\n"
                    + "tts_request and tts_submit contain speech text; utterance_id joins submit/start/done/error/stop.\n"
                    + "Images are exact PNG copies of OCR inputs, before bitmap recycling. No audio is recorded.\n"
                    + "Retention: up to 120 seconds, 64 MiB, 2000 records. Images may be dropped under load; inspect image_status.\n"
                    + "Boundary records may refer to earlier pruned frames. Recording is opt-in, local, and disabled after process restart.\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
    }
}
