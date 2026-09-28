package com.accelhack.monica.android;

import com.accelhack.monica.MonicaEnvelope;
import com.accelhack.monica.MonicaEvent;
import com.accelhack.monica.MonicaTransport;
import com.accelhack.monica.SendResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class RecordingTransport implements MonicaTransport {
  private final List<MonicaEnvelope> envelopes = Collections.synchronizedList(new ArrayList<>());
  private volatile String presenceInterval;
  private volatile String presenceSampleRate;

  @Override
  public boolean send(MonicaEnvelope envelope) {
    envelopes.add(envelope);
    return true;
  }

  /** Answers 202 with whatever presence headers {@link #presenceHeaders} set. */
  @Override
  public SendResult deliver(MonicaEnvelope envelope) {
    envelopes.add(envelope);
    return SendResult.accepted(202, presenceInterval, presenceSampleRate);
  }

  /** The raw header values every later 202 carries; {@code null} leaves a header out. */
  RecordingTransport presenceHeaders(String intervalMs, String sampleRate) {
    presenceInterval = intervalMs;
    presenceSampleRate = sampleRate;
    return this;
  }

  List<MonicaEvent> clientReports() {
    List<MonicaEvent> reports = new ArrayList<>();
    for (MonicaEvent item : items()) {
      if ("client_report".equals(item.get("type"))) reports.add(item);
    }
    return reports;
  }

  List<MonicaEnvelope> envelopes() {
    return envelopes;
  }

  List<MonicaEvent> items() {
    List<MonicaEvent> items = new ArrayList<>();
    synchronized (envelopes) {
      for (MonicaEnvelope envelope : envelopes) items.addAll(envelope.getItems());
    }
    return items;
  }

  MonicaEvent only() {
    List<MonicaEvent> items = items();
    if (items.size() != 1) throw new IllegalStateException("expected one item, got " + items.size());
    return items.get(0);
  }
}
