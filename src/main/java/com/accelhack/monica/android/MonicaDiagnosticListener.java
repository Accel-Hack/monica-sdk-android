package com.accelhack.monica.android;

/**
 * Told why ingest refused an envelope.
 *
 * <p>Registered with {@link MonicaAndroidOptions.Builder#onDiagnostic(
 * MonicaDiagnosticListener)}. Without one, the SDK writes
 * {@link MonicaDiagnostic#describe()} to logcat under the {@code MONICA} tag for a
 * {@code 422} and for the {@code 401} that stops sending; registering one replaces that,
 * so a listener that does nothing is how the warning is turned off.
 *
 * <p>It runs on the sender thread, right where the response was read, so it must not
 * block. Anything it throws is swallowed: reporting a rejection must not turn into a
 * second failure.
 */
public interface MonicaDiagnosticListener {
  /**
   * Reports one refused envelope. Called once per envelope, not once per attempt, and
   * only for the statuses that mean the envelope was dropped ({@code 4xx} other than
   * {@code 429}) — never for a retry, a timeout or a {@code 5xx}.
   *
   * @param diagnostic what ingest said, never {@code null}
   */
  void onDiagnostic(MonicaDiagnostic diagnostic);
}
