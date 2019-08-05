// Copyright 2019 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

package com.google.devtools.build.lib.bazel.rules.ninja;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Objects;

public class NinjaTarget {
  private final String command;
  private final ImmutableSortedSet<String> inputs;
  private final ImmutableSortedSet<String> implicitInputs;
  private final ImmutableSortedSet<String> orderOnlyInputs;

  private final ImmutableSortedSet<String> outputs;
  private final ImmutableSortedSet<String> implicitOutputs;

  private final ImmutableSortedMap<String, String> variables;

  public NinjaTarget(String command, ImmutableSortedSet<String> inputs,
      ImmutableSortedSet<String> implicitInputs,
      ImmutableSortedSet<String> orderOnlyInputs,
      ImmutableSortedSet<String> outputs,
      ImmutableSortedSet<String> implicitOutputs,
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

  public ImmutableSortedSet<String> getInputs() {
    return inputs;
  }

  public ImmutableSortedSet<String> getImplicitInputs() {
    return implicitInputs;
  }

  public ImmutableSortedSet<String> getOrderOnlyInputs() {
    return orderOnlyInputs;
  }

  public ImmutableSortedSet<String> getOutputs() {
    return outputs;
  }

  public ImmutableSortedSet<String> getImplicitOutputs() {
    return implicitOutputs;
  }

  public ImmutableSortedMap<String, String> getVariables() {
    return variables;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    NinjaTarget that = (NinjaTarget) o;
    return command.equals(that.command) &&
        inputs.equals(that.inputs) &&
        implicitInputs.equals(that.implicitInputs) &&
        orderOnlyInputs.equals(that.orderOnlyInputs) &&
        outputs.equals(that.outputs) &&
        implicitOutputs.equals(that.implicitOutputs) &&
        variables.equals(that.variables);
  }

  @Override
  public String toString() {
    return "NinjaTarget{" +
        "command='" + command + '\'' +
        ", inputs=" + inputs +
        ", implicitInputs=" + implicitInputs +
        ", orderOnlyInputs=" + orderOnlyInputs +
        ", outputs=" + outputs +
        ", implicitOutputs=" + implicitOutputs +
        ", variables=" + variables +
        '}';
  }

  @Override
  public int hashCode() {
    return Objects
        .hash(command, inputs, implicitInputs, orderOnlyInputs, outputs, implicitOutputs,
            variables);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private String command;
    private final ImmutableSortedSet.Builder<String> inputsBuilder;
    private final ImmutableSortedSet.Builder<String> implicitInputsBuilder;
    private final ImmutableSortedSet.Builder<String> orderOnlyInputsBuilder;

    private final ImmutableSortedSet.Builder<String> outputsBuilder;
    private final ImmutableSortedSet.Builder<String> implicitOutputsBuilder;

    private final ImmutableSortedMap.Builder<String, String> variablesBuilder;

    private Builder() {
      inputsBuilder = ImmutableSortedSet.naturalOrder();
      implicitInputsBuilder = ImmutableSortedSet.naturalOrder();
      orderOnlyInputsBuilder = ImmutableSortedSet.naturalOrder();
      outputsBuilder = ImmutableSortedSet.naturalOrder();
      implicitOutputsBuilder = ImmutableSortedSet.naturalOrder();
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
