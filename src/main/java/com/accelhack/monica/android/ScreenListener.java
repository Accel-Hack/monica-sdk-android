package com.accelhack.monica.android;

/** Receives Activity transitions without exposing android types to the caller. */
@FunctionalInterface
public interface ScreenListener {
  /**
   * @param lifecycle one of {@code created}, {@code resumed}, {@code paused}, {@code destroyed},
   *     {@code foreground} when the first Activity starts after none was started, or
   *     {@code background} when the last started one stops
   * @param screen the Activity's simple class name, which is fixed at compile time
   */
  void onScreen(String lifecycle, String screen);
}
