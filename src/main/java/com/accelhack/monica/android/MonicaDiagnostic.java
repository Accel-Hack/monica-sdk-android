package com.accelhack.monica.android;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Why ingest refused an envelope.
 *
 * <p>The contract ({@code spec/v1/ingest.md}) answers a {@code 422} with the field paths
 * that failed validation, and {@code spec/v1/error.json} exists only to carry them. A
 * transport that drops the body throws away the one piece of information that says how
 * to fix the payload, and an integrator whose {@code beforeSend} strips a required field
 * never learns that nothing has been arriving.
 *
 * <p>This is that body, already parsed and already safe to log: it holds no API key and
 * no part of the envelope, only what ingest said about it.
 *
 * <p>Reaching it is a listener rather than a return value because {@code monica-core}
 * {@code 0.1.1} declares {@code boolean MonicaTransport#send(MonicaEnvelope)}, and this
 * repository cannot widen a core API. When core grows a result-carrying
 * {@code deliver()}, this should move onto it and the listener become the compatibility
 * shim.
 *
 * @see MonicaAndroidOptions.Builder#onDiagnostic(MonicaDiagnosticListener)
 */
public final class MonicaDiagnostic {
  /** How many issues {@link #describe()} spells out; a 64 KiB body can hold thousands. */
  private static final int MAX_DESCRIBED_ISSUES = 10;

  private final int status;
  private final String code;
  private final String message;
  private final List<Issue> issues;
  private final boolean stopped;

  MonicaDiagnostic(int status, String code, String message, List<Issue> issues, boolean stopped) {
    this.status = status;
    this.code = code;
    this.message = message;
    this.issues = Collections.unmodifiableList(
        issues == null ? new ArrayList<>() : new ArrayList<>(issues));
    this.stopped = stopped;
  }

  /** The HTTP status ingest answered with. */
  public int status() {
    return status;
  }

  /**
   * The {@code error.code} ingest reported, or {@code null} when the body was missing,
   * truncated or not shaped like {@code error.json}.
   *
   * <p>It is for a human to read. Branch on {@link #status()}, never on this.
   *
   * @return the reported code, or {@code null}
   */
  public String code() {
    return code;
  }

  /**
   * The {@code error.message} ingest reported, or {@code null} when the body carried none.
   *
   * @return the reported message, or {@code null}
   */
  public String message() {
    return message;
  }

  /**
   * The field-level problems, empty unless ingest sent any. Only {@code 422} carries
   * them.
   *
   * @return the reported issues, never {@code null}
   */
  public List<Issue> issues() {
    return issues;
  }

  /**
   * Whether this response also stopped the transport for good. A {@code 401} means the
   * key itself was refused, so retrying with it can only keep failing.
   *
   * @return {@code true} when nothing more will be sent
   */
  public boolean stopped() {
    return stopped;
  }

  /**
   * The one line the SDK logs by default. Wording is shared by every MONICA SDK, so a
   * search for it finds the same problem whichever platform reported it.
   *
   * @return a line safe to log; it contains no key and no envelope content
   */
  public String describe() {
    StringBuilder line = new StringBuilder("monica: ingest rejected the envelope with ")
        .append(status);
    if (code != null && !code.isEmpty()) line.append(" (").append(code).append(')');
    line.append(": ").append(issues.size()).append(" issue(s)");
    int described = Math.min(issues.size(), MAX_DESCRIBED_ISSUES);
    for (int index = 0; index < described; index++) {
      Issue issue = issues.get(index);
      line.append("; ").append(issue.path()).append(": ").append(issue.message());
    }
    if (issues.size() > described) {
      line.append("; and ").append(issues.size() - described).append(" more");
    }
    if (stopped) line.append("; no further envelopes will be sent");
    return line.toString();
  }

  @Override
  public String toString() {
    return describe();
  }

  /** One field-level problem: where it is, and what is wrong with it. */
  public static final class Issue {
    private final String path;
    private final String message;

    Issue(String path, String message) {
      this.path = path;
      this.message = message;
    }

    /**
     * The JSON path into the envelope, as ingest wrote it, for example
     * {@code $.items[0].request.method}.
     *
     * @return the path, never {@code null}
     */
    public String path() {
      return path;
    }

    /**
     * What ingest found wrong there.
     *
     * @return the message, never {@code null}
     */
    public String message() {
      return message;
    }

    @Override
    public String toString() {
      return path + ": " + message;
    }
  }
}
