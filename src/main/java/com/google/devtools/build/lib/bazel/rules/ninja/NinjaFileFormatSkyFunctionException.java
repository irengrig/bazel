package com.google.devtools.build.lib.bazel.rules.ninja;

import com.google.devtools.build.skyframe.SkyFunctionException;

public class NinjaFileFormatSkyFunctionException extends SkyFunctionException {
  public NinjaFileFormatSkyFunctionException(String message) {
    super(new NinjaFileFormatException(message), Transience.PERSISTENT);
  }

  public NinjaFileFormatSkyFunctionException(Exception e) {
    super(e, Transience.PERSISTENT);
  }
}
