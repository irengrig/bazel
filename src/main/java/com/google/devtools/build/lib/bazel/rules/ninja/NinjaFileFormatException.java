package com.google.devtools.build.lib.bazel.rules.ninja;

public class NinjaFileFormatException extends Exception {
  public NinjaFileFormatException(String message) {
    super(message);
  }
}
