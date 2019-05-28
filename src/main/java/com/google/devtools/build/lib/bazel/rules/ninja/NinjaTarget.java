package com.google.devtools.build.lib.bazel.rules.ninja;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;

public class NinjaTarget {
  private final String command;
  private final ImmutableList<String> inputs;
  private final ImmutableList<String> implicitInputs;
  private final ImmutableList<String> orderOnlyInputs;

  private final ImmutableList<String> outputs;
  private final ImmutableList<String> implicitOutputs;

  private final ImmutableSortedMap<String, String> variables;

  public NinjaTarget(String command, ImmutableList<String> inputs,
      ImmutableList<String> implicitInputs,
      ImmutableList<String> orderOnlyInputs,
      ImmutableList<String> outputs,
      ImmutableList<String> implicitOutputs,
      ImmutableSortedMap<String, String> variables) {
    this.command = command;
    this.inputs = inputs;
    this.implicitInputs = implicitInputs;
    this.orderOnlyInputs = orderOnlyInputs;
    this.outputs = outputs;
    this.implicitOutputs = implicitOutputs;
    this.variables = variables;
  }

  public String getCommand() {
    return command;
  }

  public ImmutableList<String> getInputs() {
    return inputs;
  }

  public ImmutableList<String> getImplicitInputs() {
    return implicitInputs;
  }

  public ImmutableList<String> getOrderOnlyInputs() {
    return orderOnlyInputs;
  }

  public ImmutableList<String> getOutputs() {
    return outputs;
  }

  public ImmutableList<String> getImplicitOutputs() {
    return implicitOutputs;
  }

  public ImmutableSortedMap<String, String> getVariables() {
    return variables;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private String command;
    private final ImmutableList.Builder<String> inputsBuilder;
    private final ImmutableList.Builder<String> implicitInputsBuilder;
    private final ImmutableList.Builder<String> orderOnlyInputsBuilder;

    private final ImmutableList.Builder<String> outputsBuilder;
    private final ImmutableList.Builder<String> implicitOutputsBuilder;

    private final ImmutableSortedMap.Builder<String, String> variablesBuilder;

    private Builder() {
      inputsBuilder = new ImmutableList.Builder<>();
      implicitInputsBuilder = new ImmutableList.Builder<>();
      orderOnlyInputsBuilder = new ImmutableList.Builder<>();
      outputsBuilder = new ImmutableList.Builder<>();
      implicitOutputsBuilder = new ImmutableList.Builder<>();
      variablesBuilder = ImmutableSortedMap.naturalOrder();
    }

    public Builder setCommand(String command) {
      this.command = command;
      return this;
    }

    public Builder addInputs(String... inputs) {
      inputsBuilder.add(inputs);
      return this;
    }

    public Builder addImplicitInputs(String... inputs) {
      implicitInputsBuilder.add(inputs);
      return this;
    }

    public Builder addOrderOnlyInputs(String... inputs) {
      orderOnlyInputsBuilder.add(inputs);
      return this;
    }

    public Builder addOutputs(String... outputs) {
      outputsBuilder.add(outputs);
      return this;
    }

    public Builder addImplicitOutputs(String... outputs) {
      implicitOutputsBuilder.add(outputs);
      return this;
    }

    public Builder addVariable(String key, String value) {
      variablesBuilder.put(key, value);
      return this;
    }

    public NinjaTarget build() {
      Preconditions.checkNotNull(command);
      return new NinjaTarget(command,
          inputsBuilder.build(),
          implicitInputsBuilder.build(),
          orderOnlyInputsBuilder.build(),
          outputsBuilder.build(),
          implicitOutputsBuilder.build(),
          variablesBuilder.build());
    }
  }
}
