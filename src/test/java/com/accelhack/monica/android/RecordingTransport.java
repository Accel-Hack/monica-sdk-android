package com.accelhack.monica.android;

import com.accelhack.monica.MonicaEnvelope;
import com.accelhack.monica.MonicaEvent;
import com.accelhack.monica.MonicaTransport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class RecordingTransport implements MonicaTransport {
  private final List<MonicaEnvelope> envelopes = Collections.synchronizedList(new ArrayList<>());

  @Override
  public boolean send(MonicaEnvelope envelope) {
    envelopes.add(envelope);
    return true;
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
