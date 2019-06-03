package com.google.devtools.build.lib.bazel.rules.ninja;

import com.google.common.collect.ImmutableSortedMap;
import java.util.Objects;

public class NinjaRule {
  private final ImmutableSortedMap<ParameterName, String> parameters;

  public NinjaRule(ImmutableSortedMap<ParameterName, String> parameters) {
    this.parameters = parameters;
  }

  public ImmutableSortedMap<ParameterName, String> getParameters() {
    return parameters;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    NinjaRule ninjaRule = (NinjaRule) o;
    return parameters.equals(ninjaRule.parameters);
  }

  @Override
  public int hashCode() {
    return Objects.hash(parameters);
  }

  public enum ParameterName {
    command,
    depfile,
    deps,
    msvc_deps_prefix,
    description,
    generator,
    restat,
    rspfile,
    rspfile_content,

    // These variables are provided by the target.
    in,
    in_newline,
    out;

    private final boolean definedByTarget;

    ParameterName() {
      definedByTarget = false;
    }

    ParameterName(boolean definedByTarget) {
      this.definedByTarget = definedByTarget;
    }

    public static ParameterName nullOrValue(String name) {
      try {
        return valueOf(name);
      } catch (IllegalArgumentException e) {
        return null;
      }
    }

    public boolean isDefinedByTarget() {
      return definedByTarget;
    }
  }
}
