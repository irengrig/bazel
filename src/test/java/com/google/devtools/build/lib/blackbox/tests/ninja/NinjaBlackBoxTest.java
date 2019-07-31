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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class NinjaBlackBoxTest extends AbstractBlackBoxTest {
  @Test
  public void testSimpleFile() throws Exception {
    context().write(".bazelignore", "out");
    context().getWorkDir().resolve("out").toFile().mkdir();
    context().write("name.txt", "Ninja");
    context().write("build.ninja",
        "rule echo",
        "  command = echo \"Hello from $$(cat $in)!\">$out && echo \"One more command!\">> $out",
        "",
        "build out/hello.txt: echo name.txt",
        "",
        "default hello.txt");
    context().write("BUILD",
        "ninja_build(name = 'first_ninja', ",
        "srcs = [':build.ninja', ':name.txt'],",
        "build_ninja = ':build.ninja')");

    BuilderRunner bazel = context().bazel();
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
  public void testFilteringTargets() throws Exception {
    context().write(".bazelignore", "out");
    context().getWorkDir().resolve("out").toFile().mkdir();
    context().write("name.txt", "Ninja");
    context().write("build.ninja",
        "rule echo",
        "  command = echo \"Hello from $$(cat $in)!\" > $out",
        "",
        "build out/hello.txt: echo name.txt",
        "",
        "build something_unneeded: echo non_existing_input.txt",
        "",
        "default out/hello.txt");
    context().write("BUILD",
        "ninja_build(name = 'first_ninja', ",
        "srcs = [':build.ninja', ':name.txt'],",
        "export_targets = {'out/hello.txt': 'hello'},",
        "build_ninja = ':build.ninja')");

    BuilderRunner bazel = context().bazel();
    bazel.build("//:first_ninja");

    Path outPath = context().getWorkDir().resolve("out/hello.txt");
    assertThat(outPath.toFile().exists()).named(outPath.toString()).isTrue();
    assertThat(PathUtils.readFile(outPath)).containsExactly("Hello from Ninja!");

    Path ninjaLog = context().resolveGenPath(bazel, "ninja.log");
    assertThat(ninjaLog.toFile().exists()).named(ninjaLog.toString()).isTrue();
    assertThat(PathUtils.readFile(ninjaLog)).containsExactly("This should be lazy!");
  }

  @Test
  public void testOutputGroups() throws Exception {
    copyFileRule();
    context().write(".bazelignore", "out");
    context().getWorkDir().resolve("out").toFile().mkdir();
    context().write("name.txt", "Ninja");
    context().write("build.ninja",
        "hello = Hello",
        "",
        "rule echo",
        "  command = echo \"$hello from $$(cat $in)!\" > $out",
        "",
        "build out/hello.txt: echo name.txt",
        "",
        "build out/goodbye.txt: echo name.txt",
        "  hello = Goodbye",
        "",
        "default hello.txt");
    context().write("BUILD",
        "load(':debug.bzl', 'copy_file')",
        "ninja_build(name = 'first_ninja', ",
        "srcs = [':build.ninja', ':name.txt'],",
        "export_targets = {'out/hello.txt': 'hello_file', 'out/goodbye.txt': 'goodbye_file'},",
        "build_ninja = ':build.ninja')",
        "filegroup(name = 'hello_file', srcs = [':first_ninja'], output_group = 'hello_file')",
        "filegroup(name = 'goodbye_file', srcs = [':first_ninja'], output_group = 'goodbye_file')",
        "copy_file(name = 'debug_hello', source = ':hello_file')",
        "copy_file(name = 'debug_goodbye', source = ':goodbye_file')"
        );

    BuilderRunner bazel = context().bazel();
    bazel.build("//:debug_hello", "//:debug_goodbye");

    Path helloFile = context().resolveBinPath(bazel, "copy/hello.txt");
    Path goodbyeFile = context().resolveBinPath(bazel, "copy/goodbye.txt");

    assertThat(helloFile.toFile().exists()).named(helloFile.toString()).isTrue();
    assertThat(PathUtils.readFile(helloFile)).containsExactly("Hello from Ninja!");

    assertThat(helloFile.toFile().exists()).named(goodbyeFile.toString()).isTrue();
    assertThat(PathUtils.readFile(goodbyeFile)).containsExactly("Goodbye from Ninja!");
  }

  @Test
  public void testBazelProvidesInputForNinja() throws Exception {
    context().write("helper.bzl",
        "def _impl(ctx):",
        "  out = ctx.actions.declare_file('generated_hello.txt')",
        "  ctx.actions.write(out, 'Generated')",
        "  return [DefaultInfo(files = depset([out]))]",
        "",
        "generate_hello = rule(",
        "  implementation = _impl",
        ")");
    context().write(".bazelignore", "out");
    context().getWorkDir().resolve("out").toFile().mkdir();
    context().write("build.ninja",
        "hello = Hello",
        "",
        "rule echo",
        "  command = echo \"$hello from $$(cat $in)!\" > $out",
        "",
        "build out/hello.txt: echo name.txt",
        "",
        "default hello.txt");
    context().write("BUILD",
        "load(':helper.bzl', 'generate_hello')",
        "generate_hello(name = 'generated')",
        "ninja_build(name = 'first_ninja', ",
        "  srcs = [':build.ninja'],",
        "  deps_mapping = {':generated': 'name.txt'},",
        "  build_ninja = ':build.ninja',",
        ")"
    );

    BuilderRunner bazel = context().bazel();
    bazel.build("//:first_ninja");

    Path outPath = context().getWorkDir().resolve("out/hello.txt");
    assertThat(outPath.toFile().exists()).named(outPath.toString()).isTrue();
    assertThat(PathUtils.readFile(outPath)).containsExactly("Hello from Generated!");

    Path ninjaLog = context().resolveGenPath(bazel, "ninja.log");
    assertThat(ninjaLog.toFile().exists()).named(ninjaLog.toString()).isTrue();
    assertThat(PathUtils.readFile(ninjaLog)).containsExactly("This should be lazy!");
  }

  @Test
  public void testInclude() throws Exception {
    context().write(".bazelignore", "out");
    context().getWorkDir().resolve("out").toFile().mkdir();
    context().write("name.txt", "Ninja");
    context().write("rules.ninja",
        "hello = Hello",
        "",
        "rule echo",
        "  command = echo \"$hello from $$(cat $in) with include!\" > $out"
        );
    context().write("build.ninja",
        "include rules.ninja",
        "",
        "build out/hello.txt: echo name.txt",
        "",
        "default hello.txt");
    context().write("BUILD",
        "ninja_build(name = 'first_ninja', ",
            "srcs = [':build.ninja', ':rules.ninja', ':name.txt'],",
            "build_ninja = ':build.ninja')");

    BuilderRunner bazel = context().bazel();
    bazel.build("//:first_ninja");

    Path outPath = context().getWorkDir().resolve("out/hello.txt");
    assertThat(outPath.toFile().exists()).named(outPath.toString()).isTrue();
    assertThat(PathUtils.readFile(outPath)).containsExactly("Hello from Ninja with include!");

    Path ninjaLog = context().resolveGenPath(bazel, "ninja.log");
    assertThat(ninjaLog.toFile().exists()).named(ninjaLog.toString()).isTrue();
    assertThat(PathUtils.readFile(ninjaLog)).containsExactly("This should be lazy!");
  }

  @Test
  public void testSubninja() throws Exception {
    context().write(".bazelignore", "out");
    context().getWorkDir().resolve("out").toFile().mkdir();
    context().write("name.txt", "Ninja");
    context().write("composite.ninja",
        "hello = Hello",
        "",
        "rule echo",
        "  command = echo \"$hello from $$(cat $in) with include!\" > $out",
        "subninja build.ninja"
    );
    context().write("build.ninja",
        "build out/hello.txt: echo name.txt",
        "",
        "default hello.txt");
    context().write("BUILD",
        "ninja_build(name = 'first_ninja', ",
        "srcs = [':composite.ninja', ':build.ninja', ':name.txt'],",
        "build_ninja = ':composite.ninja')");

    BuilderRunner bazel = context().bazel();
    bazel.build("//:first_ninja");

    Path outPath = context().getWorkDir().resolve("out/hello.txt");
    assertThat(outPath.toFile().exists()).named(outPath.toString()).isTrue();
    assertThat(PathUtils.readFile(outPath)).containsExactly("Hello from Ninja with include!");

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

    context().write("build.ninja",
        "cc     = clang",
        "ccflags = -Wall",
        "cclinkerflags = -lstdc++",
        "",
        "rule compile",
        "  command = $cc $ccflags -c $in -o $out",
        "",
        "rule link",
        "  command = $cc $cclinkerflags $in -o $out -v",
        "",
        "build out/hello.o: compile hello.cxx",
        "build out/hello: link out/hello.o",
        "",
        "default out/hello");
    context().write("BUILD",
        "ninja_build(name = 'build_hello', ",
        "srcs = [':build.ninja'],",
        "build_ninja = ':build.ninja', executable_target = 'out/hello')");

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

    // For debug, uncomment this line and comment out the next; attach to the bazel process with debugger.
    // ProcessResult result = bazel.enableDebug().run("//:build_hello");
    ProcessResult result = bazel.run("//:build_hello");
    assertBuildCached(result);

    assertThat(result.outString()).isEqualTo("Hello, Sun!");

    Path ninjaLog = context().resolveBinPath(bazel, "ninja.log");
    assertThat(ninjaLog.toFile().exists()).named(ninjaLog.toString()).isTrue();
    assertThat(PathUtils.readFile(ninjaLog)).containsExactly("This should be lazy!");
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
        "cmake -GNinja");
    Path scriptPath = context().write("script.sh", prepareScript);
    scriptPath.toFile().setExecutable(true);
    ProcessResult scriptResult = context()
        .runBinary(context().getWorkDir(), "bash", true, "-c", "./script.sh");
    System.out.println("\n============= Configuring cares: ============\n");
    System.out.println(scriptResult.outString());
    System.out.println("\n=============================================\n");

    context().write("cares/WORKSPACE", "workspace(name = \"cares\")");
    context().write("cares/BUILD", "filegroup(name = \"all\", srcs = glob([\"**\"], exclude = [\"bazel-*\", \"bazel-*/**\"]), visibility = [\"//visibility:public\"])",
        "ninja_build(name = \"ninja_target\",",
        "            srcs = [\":all\"],",
        "            build_ninja = \":build.ninja\",",
        // this is important to NOT call ninja files regeneration
        "            export_targets = {\"all\" : \"all_files\"}",
        ")");

    Path caresDir = context().getWorkDir().resolve("cares");
    ProcessResult result = context().bazel()
        .withWorkingDirectory(caresDir).build("//:ninja_target");
    Path libraryPath = caresDir.resolve("lib/libcares.so");
    assertThat(libraryPath.toFile().exists()).isTrue();
    System.out.println("\n============= Building cares: ============\n");
    System.out.println(result.errString());
  }

  @Test
  public void testDoNotRunMeOnCIReadBigNinjaFile() throws Exception {
    if (System.getenv("BUILDKITE") != null) {
      return;
    }

    Path buildNinja = context().getWorkDir().resolve("build.ninja");
    // mind --test_env flag:
    // TEST_NINJA_FILE=<...> bash -c 'bazel test --test_filter=com.google.devtools.build.lib.blackbox.tests.ninja.NinjaBlackBoxTest#testDoNotRunMeOnCIReadBigNinjaFile$ --test_env=TEST_NINJA_FILE -- //src/test/java/com/google/devtools/build/lib/blackbox/tests:NinjaBlackBoxTest'
    String pathFromEnv = System.getenv("TEST_NINJA_FILE");
    Path origPath = Paths.get(pathFromEnv);

    Files.createSymbolicLink(buildNinja, origPath);
    context().write("WORKSPACE", "workspace(name = \"read_big_file\")");
    context().write("BUILD", "filegroup(name = \"all\", srcs = glob([\"**\"], exclude = [\"bazel-*\", \"bazel-*/**\"]), visibility = [\"//visibility:public\"])",
        "ninja_build(name = \"ninja_target\",",
        "            srcs = [\":all\"],",
        "            build_ninja = \":build.ninja\",",
        "            export_targets = {\"all\" : \"all_files\"}",
        ")");

    long startTime = System.currentTimeMillis();
    ProcessResult result = context().bazel()
        .withEnv("bazel.ninja.chunk.size", "-1")
        .withEnv("bazel.ninja.only.read.file", "true")
        //.enableDebug()
        .build("//:ninja_target");
    long endTime = System.currentTimeMillis();
    System.out.println("Time to read: " + (endTime - startTime) + ", " + result.outString());
  }

  private void copyFileRule() throws IOException {
    String text = "def _impl(ctx):\n"
        + "  out = ctx.actions.declare_file('copy/' + ctx.file.source.basename)\n"
        + "  ctx.actions.run_shell("
        + "    inputs = [ctx.file.source],"
        + "    outputs = [out], "
        + "    command = 'cp $1 $2',"
        + "    arguments = [ctx.file.source.path, out.path]"
        + "  )\n"
        + "  return [DefaultInfo(files = depset([out]))]\n"
        + "\n"
        + "copy_file = rule(\n"
        + "    implementation = _impl,\n"
        + "    attrs = {\n"
        + "        \"source\": attr.label(allow_single_file = True),\n"
        + "    }\n"
        + ")";
    context().write("debug.bzl", text);
  }

  private void assertBuildExecuted(ProcessResult result) {
    assertThat(result.errString()).contains("INFO: From Ninja: building ");
  }

  private void assertBuildCached(ProcessResult result) {
    assertThat(result.errString()).doesNotContain("INFO: From Ninja: building ");
  }
}
