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
        "  command = echo \"Hello from $$(cat $in)!\" > $out",
        "",
        "build out/hello.txt: echo name.txt",
        "",
        "default hello.txt");
    context().write("BUILD",
        "ninja_build(name = 'first_ninja', src = ':build.ninja')");

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
  public void testBuildSomething() throws Exception {
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
        "ninja_build(name = 'build_hello', src = ':build.ninja', "
            + "executable_target = 'out/hello')");

    BuilderRunner bazel = context().bazel();
    // bazel.build("//:build_hello");

    // Path outPath = context().getWorkDir().resolve("out/hello");
    // assertThat(outPath.toFile().exists()).named(outPath.toString()).isTrue();

    // outPath.toFile().setExecutable(true);
    ProcessResult result = bazel.enableDebug().run("//:build_hello");

    // assertThat(result.outString()).isEqualTo("Hello, World!");

    Path ninjaLog = context().resolveGenPath(bazel, "ninja.log");
    assertThat(ninjaLog.toFile().exists()).named(ninjaLog.toString()).isTrue();
    assertThat(PathUtils.readFile(ninjaLog)).containsExactly("This should be lazy!");
  }
}
