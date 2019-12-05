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

package com.google.devtools.build.lib.blackbox.tests.ninja;

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.blackbox.framework.BuilderRunner;
import com.google.devtools.build.lib.blackbox.framework.PathUtils;
import com.google.devtools.build.lib.blackbox.framework.ProcessResult;
import com.google.devtools.build.lib.blackbox.junit.AbstractBlackBoxTest;
import java.nio.file.Path;
import org.junit.Test;

public class NinjaBlackBoxTest extends AbstractBlackBoxTest {
  @Test
  public void testSimpleFile() throws Exception {
    context().write(".bazelignore", "out");
    context().getWorkDir().resolve("out").toFile().mkdir();
    context().write("name.txt", "Ninja");
    context().write("build.ninja",
        "rule echo",
        "  command = bash -x -c 'bash -x -c '\\\''echo \"Hello from $$(cat $in)!\">$out && echo \"One more command!\">> $out'\\\'' ' ",
        "",
        "build out/hello.txt: echo name.txt",
        "",
        "default hello.txt");
    context().write("BUILD",
        "ninja_graph(name = 'graph', main = ':build.ninja', output_root = 'out')",
        "ninja_build(name = 'first_ninja', ",
        "srcs = [':name.txt'],",
        "ninja_graph = ':graph',",
        "targets = ['out/hello.txt'],)");

    BuilderRunner bazel = context().bazel();
    //bazel.build("//:first_ninja");
    bazel.build("//:first_ninja");

    Path outPath = context().getWorkDir().resolve("out/hello.txt");
    assertThat(outPath.toFile().exists()).named(outPath.toString()).isTrue();
    assertThat(PathUtils.readFile(outPath))
        .containsExactly("Hello from Ninja!", "One more command!");

    Path ninjaLog = context().resolveGenPath(bazel, "ninja.log");
    assertThat(ninjaLog.toFile().exists()).named(ninjaLog.toString()).isTrue();
    assertThat(PathUtils.readFile(ninjaLog)).containsExactly("This should be lazy!");
  }

  @Test
  public void testBuildHelloWorldCxx() throws Exception {
    context().write(".bazelignore", "out");
    context().getWorkDir().resolve("out").toFile().mkdir();

    context().write("hello.cxx",
        "#include <iostream>",
        "int main()",
        "{",
        "    std::cout << \"Hello, World!\" << std::endl;",
        "    return 0;",
        "}");

    context().write("included.ninja",
        "rule compile",
        "  command = $cc $ccflags -c $in -o $out",
        ""
        );
    context().write("build.ninja",
        "included = included.ninja",
        "cc     = clang",
        "ccflags = -Wall",
        "cclinkerflags = -lstdc++",
        "",
        "include ${included}",
        "",
        "rule link",
        "  command = $cc $cclinkerflags $in -o $out -v",
        "",
        "build input_alias: phony hello.cxx",
        "build out/hello.o: compile input_alias",
        "build out/hello: link out/hello.o",
        "",
        "default out/hello");
    context().write("BUILD",
        "ninja_graph(name = 'graph', main = ':build.ninja', srcs = [':included.ninja'], output_root = 'out')",
        "ninja_build(name = 'build_hello', ",
        "srcs = [':name.cxx'],",
        "ninja_graph = ':graph',",
        "targets = ['out/hello'],)");

    BuilderRunner bazel = context().bazel();
    assertBuildExecuted(bazel.build("//:build_hello"));

    // Nothing changed, build should be cached.
    assertBuildCached(bazel.build("//:build_hello"));

    context().write("hello.cxx",
        "#include <iostream>",
        "int main()",
        "{",
        "    std::cout << \"Hello, Sun!\" << std::endl;",
        "    return 0;",
        "}");

    // Source file changed, should be executed.
    assertBuildExecuted(bazel.build("//:build_hello"));
  }

  @Test
  public void testDoNotRunMeOnCIBuildCAres() throws Exception {
    if (System.getenv("BUILDKITE") != null) {
      return;
    }
    String prepareScript =
        String.join("\n",
            "curl -sSL https://github.com/c-ares/c-ares/archive/cares-1_15_0.tar.gz > cares.tar.gz",
            "tar -xf cares.tar.gz",
            "rm -f cares.tar.gz",
            "mv c-ares-cares-1_15_0 cares",
            "cd cares",
            "cmake -GNinja -B build");
    Path scriptPath = context().write("script.sh", prepareScript);
    scriptPath.toFile().setExecutable(true);
    ProcessResult scriptResult = context()
        .runBinary(context().getWorkDir(), "bash", false, "-c", "./script.sh");
    System.out.println("\n============= Configuring cares: ============\n");
    System.out.println(scriptResult.outString());
    System.out.println("\n=============================================\n");

    context().write("WORKSPACE", "workspace(name = \"cares\")");
    context().write("BUILD", "filegroup(name = \"all\", srcs = glob([\"cares/**\"], exclude = [\"bazel-*\", \"bazel-*/**\"]), visibility = [\"//visibility:public\"])",
        "ninja_graph(name = 'graph', main = 'cares/build/build.ninja', srcs = ['cares/build/rules.ninja'], output_root = 'build')",
        "ninja_build(name = \"ninja_target\",",
        "            srcs = [\":all\"],",
        "            ninja_graph = \":graph\",",
        "            targets = [\"all\"],",
        ")");

    Path caresDir = context().getWorkDir().resolve("cares");
    //ProcessResult result = context().bazel().build("//:ninja_target");
    ProcessResult result = context().bazel().build("//:ninja_target");
    Path libraryPath = caresDir.resolve("lib/libcares.so");
    assertThat(libraryPath.toFile().exists()).isTrue();
    System.out.println("\n============= Building cares: ============\n");
    System.out.println(result.errString());
  }

  private void assertBuildExecuted(ProcessResult result) {
    assertThat(result.errString()).contains("INFO: From running Ninja targets:");
  }

  private void assertBuildCached(ProcessResult result) {
    assertThat(result.errString()).doesNotContain("INFO: From running Ninja targets:");
  }
}
