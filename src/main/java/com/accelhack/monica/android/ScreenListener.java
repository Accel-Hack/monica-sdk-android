package com.accelhack.monica.android;

/** Receives Activity transitions without exposing android types to the caller. */
@FunctionalInterface
public interface ScreenListener {
  /**
   * @param lifecycle one of {@code created}, {@code resumed}, {@code paused}, {@code destroyed}
   * @param screen the Activity's simple class name, which is fixed at compile time
   */
  void onScreen(String lifecycle, String screen);
}
