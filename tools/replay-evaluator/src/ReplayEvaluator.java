import jp.hidemaru.burnedcaptionreader.subtitle.RowSpeechLedger;
import jp.hidemaru.burnedcaptionreader.subtitle.SubtitleEvent;
import jp.hidemaru.burnedcaptionreader.subtitle.SubtitleEventManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Deterministic replay of the same fixture through the old and new contracts. */
public final class ReplayEvaluator {
  record Row(String kind, String id, String group, String text, String expected, long monoMs) {}
  record Metrics(int emittedRows, int acceptedEvents, int suppressedEvents, int unresolved) {}
  record Run(Metrics metrics, Map<String, String> output) {}

  static List<Row> read(Path path) throws IOException {
    List<Row> rows = new ArrayList<>();
    for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
      if (raw.isBlank() || raw.startsWith("#")) continue;
      String[] a = raw.split("\\t", -1);
      if (a.length != 6) throw new IOException("bad fixture row: " + raw);
      rows.add(new Row(a[0], a[2], a[1], a[3].replace("\\n", "\n"), a[4], Long.parseLong(a[5])));
    }
    return rows;
  }
  static int countRows(String text) {
    if (text == null || text.isBlank()) return 0;
    return text.split("\\n", -1).length;
  }
  static Run baseline(List<Row> rows) {
    SubtitleEventManager manager = new SubtitleEventManager(60_000L);
    int emitted = 0, accepted = 0, suppressed = 0, unresolved = 0; String syntheticGroup = "";
    Map<String, String> output = new LinkedHashMap<>();
    for (Row row : rows) {
      if (row.kind.equals("synthetic") && !row.group.equals(syntheticGroup)) { manager.reset(); syntheticGroup = row.group; }
      long t = row.monoMs;
      if (row.id.equals("sep-2")) manager.reset();
      SubtitleEvent out = manager.accept(new SubtitleEvent(row.id, row.text, t, t, 1.0));
      if (out == null) suppressed++; else { accepted++; emitted += countRows(out.getText()); }
      output.put(row.id, out == null ? "" : out.getText());
      if (row.expected.contains("HARD_CASE_UNRESOLVED")) unresolved++;
    }
    return new Run(new Metrics(emitted, accepted, suppressed, unresolved), output);
  }
  static Run candidate(List<Row> rows) {
    RowSpeechLedger ledger = new RowSpeechLedger(60_000L);
    int emitted = 0, accepted = 0, suppressed = 0, unresolved = 0; String syntheticGroup = "";
    Map<String, String> output = new LinkedHashMap<>();
    for (Row row : rows) {
      if (row.expected.contains("HARD_CASE_UNRESOLVED")) unresolved++;
      if (row.kind.equals("synthetic") && !row.group.equals(syntheticGroup)) { ledger.reset(); syntheticGroup = row.group; }
      long t = row.monoMs;
      if (row.id.equals("sep-2")) ledger.reset();
      RowSpeechLedger.Reservation reservation = ledger.reserve(
          new SubtitleEvent(row.id, row.text, t, t, 1.0));
      if (reservation == null) { suppressed++; output.put(row.id, ""); continue; }
      require(ledger.markInFlight(reservation.getId()), "markInFlight failed for " + row.id);
      require(ledger.markSpoken(reservation.getId(), t), "markSpoken failed for " + row.id);
      accepted++; emitted += reservation.getRows().size();
      output.put(row.id, reservation.getText());
    }
    return new Run(new Metrics(emitted, accepted, suppressed, unresolved), output);
  }
  static void cancellationControls() {
    RowSpeechLedger ledger = new RowSpeechLedger(60_000L);
    SubtitleEvent e = new SubtitleEvent("cancel", "retryable caption", 1, 1, 1.0);
    RowSpeechLedger.Reservation first = ledger.reserve(e);
    require(first != null, "initial reservation missing");
    require(ledger.markInFlight(first.getId()), "in-flight transition missing");
    require(ledger.release(first.getId()), "release failed");
    RowSpeechLedger.Reservation retry = ledger.reserve(new SubtitleEvent("retry", e.getText(), 2, 2, 1.0));
    require(retry != null, "released caption was not retryable");
    require(ledger.markInFlight(retry.getId()) && ledger.markSpoken(retry.getId(), 2), "retry completion failed");
    require(ledger.reserve(new SubtitleEvent("duplicate-pending", e.getText(), 3, 3, 1.0)) == null,
        "duplicate pending/spoken row was admitted");
  }
  static void observedRowControl() {
    RowSpeechLedger ledger = new RowSpeechLedger(60_000L);
    String first = "、金のためにって。";
    String second = "損益未達だと組織としても大変なのは";
    complete(ledger, new SubtitleEvent("obs-1", first, 615676496L, 615676496L, 1.0));
    complete(ledger, new SubtitleEvent("obs-2", second, 615676928L, 615676928L, 1.0));
    RowSpeechLedger.Reservation merged = ledger.reserve(new SubtitleEvent("obs-3",
        "、金のためって0。\n損益未達だと組織としても大変なのは\nお前もわかってるだろ?",
        615679470L, 615679470L, 1.0));
    require(merged != null && merged.getRows().size() == 2, "merged event did not retain two fresh rows");
    require(merged.getRows().stream().anyMatch(s -> s.contains("お前もわかってるだろ")), "new third row was lost");
    require(merged.getRows().stream().noneMatch(s -> s.equals(second)), "known second row was repeated");
  }
  static void complete(RowSpeechLedger ledger, SubtitleEvent event) {
    RowSpeechLedger.Reservation r = ledger.reserve(event);
    require(r != null && ledger.markInFlight(r.getId()) && ledger.markSpoken(r.getId(), event.getCommittedAt()),
        "fixture setup completion failed for " + event.getId());
  }
  static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
  public static void main(String[] args) throws Exception {
    Path fixture = Paths.get(args.length == 0 ? "replay-fixture.tsv" : args[0]);
    List<Row> rows = read(fixture);
    Run oldRun = baseline(rows), nextRun = candidate(rows);
    Metrics old = oldRun.metrics(), next = nextRun.metrics();
    observedRowControl();
    cancellationControls();
    String merged = nextRun.output().get("obs-3");
    require(merged != null && merged.contains("お前もわかってるだろ"),
        "new third row was lost from observed merged event");
    require(!merged.contains("損益未達だと組織としても大変なのは"),
        "exact known second row repeated in observed merged event");
    require(nextRun.output().get("obs-4").isEmpty(),
        "third-row fragment was spoken again after merged event");
    require(!nextRun.output().get("num-1").isEmpty() && !nextRun.output().get("num-2").isEmpty(),
        "changed-number control was suppressed");
    require(!nextRun.output().get("neg-1").isEmpty() && !nextRun.output().get("neg-2").isEmpty(),
        "changed-negation control was suppressed");
    require(nextRun.output().get("order-2").isEmpty(),
        "reordered rows were spoken twice");
    require(!nextRun.output().get("gap-3").isEmpty(),
        "caption after the configured history window was suppressed");
    require(nextRun.output().get("short-2").isEmpty(),
        "exact short caption was spoken twice");
    require(next.unresolved == 1, "the one explicitly unresolved hard case must remain unresolved");
    require(rows.stream().anyMatch(r -> r.group.equals("known_second_line_repeat")), "known repeat missing");
    require(rows.stream().anyMatch(r -> r.group.equals("changed_number")), "numeric control missing");
    require(rows.stream().anyMatch(r -> r.group.equals("changed_negation")), "negation control missing");
    require(rows.stream().anyMatch(r -> r.group.equals("short_caption")), "short-caption control missing");
    System.out.printf("fixture_rows=%d%nOLD_SubtitleEventManager accepted_events=%d emitted_rows=%d suppressed_events=%d unresolved=%d%nNEW_RowSpeechLedger accepted_events=%d emitted_rows=%d suppressed_events=%d unresolved=%d%nDELTA emitted_rows=%+d accepted_events=%+d%nPASS: replay and cancellation controls%n",
        rows.size(), old.acceptedEvents, old.emittedRows, old.suppressedEvents, old.unresolved,
        next.acceptedEvents, next.emittedRows, next.suppressedEvents, next.unresolved,
        next.emittedRows - old.emittedRows, next.acceptedEvents - old.acceptedEvents);
  }
}
