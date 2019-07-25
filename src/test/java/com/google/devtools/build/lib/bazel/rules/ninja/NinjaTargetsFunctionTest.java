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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.bazel.rules.ninja.NinjaTargetsFunction.Splitter;
import com.google.devtools.build.lib.bazel.rules.ninja.NinjaTargetsFunction.TokenProcessor;
import com.google.devtools.build.lib.blackbox.framework.PathUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.util.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class NinjaTargetsFunctionTest {

  @Test
  public void testSplitter() throws Exception {
    Splitter splitter = new Splitter(";") {
      private boolean headCalled = false;
      private boolean tailCalled = false;

      @Override
      public void accept(String text) throws NinjaFileFormatSkyFunctionException {
        head(expect("head", () -> headCalled = true));
        tail(expect("tail", () -> tailCalled = true));
        super.accept(text);
        assertThat(headCalled).isTrue();
        assertThat(tailCalled).isTrue();
      }
    };

    splitter.accept("");
    splitter.accept("head;tail");
    splitter.accept("head ;tail");
    splitter.accept("head; \ntail ");

    new Splitter(";;") {
      private boolean called = false;

      @Override
      public void accept(String text) throws NinjaFileFormatSkyFunctionException {
        tail(expect("tail", () -> called = true));
        super.accept(text);
        assertThat(called).isTrue();
      }
    }.accept(" ;; \ntail ");

    Splitter onlyHead = new Splitter("|") {
      private boolean called = false;

      @Override
      public void accept(String text) throws NinjaFileFormatSkyFunctionException {
        head(expect("head;", () -> called = true));
        super.accept(text);
        assertThat(called).isTrue();
      }
    };
    onlyHead.accept("head; ");
    onlyHead.accept("head; |");

    try {
      new Splitter(";", true)
          .head(expect("head", () -> {
          }))
          .tail(expect("tail", () -> {
          })).accept("No separator");
      fail("Expected NinjaFileFormatSkyFunctionException to be thrown.");
    } catch (NinjaFileFormatSkyFunctionException e) {
      // expected
    }
  }

  @Test
  public void testParseSimpleNinjaBuildExpression() throws Exception {
    ImmutableList<String> lines = ImmutableList.of("build hello.o: compile hello.c");
    NinjaTargetsValue.Builder builder = NinjaTargetsValue.builder();
    NinjaTargetsFunction.parseTargetExpression(lines, builder);
    NinjaTargetsValue value = builder.build();
    assertThat(value.getTargets()).hasSize(1);

    NinjaTarget target = value.getTargets().get(0);
    assertThat(target.getCommand()).isEqualTo("compile");
    assertThat(target.getInputs()).containsExactly("hello.c");
    assertThat(target.getOutputs()).containsExactly("hello.o");

    assertThat(target.getImplicitInputs()).isEmpty();
    assertThat(target.getOrderOnlyInputs()).isEmpty();
    assertThat(target.getImplicitOutputs()).isEmpty();
  }

  @Test
  public void testParseAllPartsNinjaBuildExpression() throws Exception {
    ImmutableList<String> lines = ImmutableList
        .of("build out1 out2 | implOut1 implOut2 : command inp1 ${inp2} | implInp1 implInp2 || orderInp1 orderInp2");
    NinjaTargetsValue.Builder builder = NinjaTargetsValue.builder();
    NinjaTargetsFunction.parseTargetExpression(lines, builder);
    NinjaTargetsValue value = builder.build();
    assertThat(value.getTargets()).hasSize(1);

    NinjaTarget target = value.getTargets().get(0);
    assertThat(target.getCommand()).isEqualTo("command");
    assertThat(target.getInputs()).containsExactly("inp1", "${inp2}");
    assertThat(target.getOutputs()).containsExactly("out1", "out2");

    assertThat(target.getImplicitInputs()).containsExactly("implInp1", "implInp2");
    assertThat(target.getOrderOnlyInputs()).containsExactly("orderInp1", "orderInp2");
    assertThat(target.getImplicitOutputs()).containsExactly("implOut1", "implOut2");
  }

  @Test
  public void testParseRealNinjaBuildExpression() throws Exception {
    ImmutableList<String> lines = ImmutableList.of(
        "build out/inner/something-very-long.so "
            + " : $cc "
            + " out/inner/some-other-very-long.so "
            + " | ${some.other.tool1} ${some.other.tool2}",
        // Header ends here
        "    description = ${long-description-with-many-vars}",
        "    args = $ --argument-with-space");
    NinjaTargetsValue.Builder builder = NinjaTargetsValue.builder();
    NinjaTargetsFunction.parseTargetExpression(lines, builder);
    NinjaTargetsValue value = builder.build();
    assertThat(value.getTargets()).hasSize(1);

    NinjaTarget target = value.getTargets().get(0);
    assertThat(target.getCommand()).isEqualTo("$cc");
    assertThat(target.getInputs()).containsExactly("out/inner/some-other-very-long.so");
    assertThat(target.getOutputs()).containsExactly("out/inner/something-very-long.so");

    assertThat(target.getImplicitInputs())
        .containsExactly("${some.other.tool1}", "${some.other.tool2}");
    assertThat(target.getOrderOnlyInputs()).isEmpty();
    assertThat(target.getImplicitOutputs()).isEmpty();

    ImmutableSortedMap<String, String> map = ImmutableSortedMap.of(
        "description", "${long-description-with-many-vars}",
        "args", "$ --argument-with-space"
    );
    assertThat(target.getVariables()).containsExactlyEntriesIn(map);
  }

  @Test
  public void testDefaultTargets() throws Exception {
    ImmutableList<String> lines = ImmutableList.of("default a b c");
    NinjaTargetsValue.Builder builder = NinjaTargetsValue.builder();
    NinjaTargetsFunction.parseTargetExpression(lines, builder);
    NinjaTargetsValue value = builder.build();
    assertThat(value.getDefaults()).containsExactly("a", "b", "c");
  }

  @Test
  public void testPhony() throws Exception {
    NinjaTargetsValue.Builder builder = NinjaTargetsValue.builder();
    NinjaTargetsFunction.parseTargetExpression(
        ImmutableList.of("build abc: phony very-long one-more"), builder);
    NinjaTargetsFunction.parseTargetExpression(
        ImmutableList.of("build cde: phony | implicit || order-only"), builder);
    NinjaTargetsFunction.parseTargetExpression(
        ImmutableList.of("build skip those targets: phony"), builder);

    NinjaTargetsValue value = builder.build();
    List<NinjaTarget> targets = value.getTargets();
    assertThat(targets).hasSize(2);

    assertThat(targets).containsExactly(
        NinjaTarget.builder()
            .setCommand("phony")
            .addOutputs("abc")
            .addInputs("very-long", "one-more")
            .build(),
        NinjaTarget.builder()
            .setCommand("phony")
            .addOutputs("cde")
            .addImplicitInputs("implicit")
            .addOrderOnlyInputs("order-only")
            .build()
    );
  }

  @Test
  public void testReplaceAliases() throws Exception {
    NinjaTargetsValue.Builder builder = NinjaTargetsValue.builder();
    NinjaTargetsFunction.parseTargetExpression(
        ImmutableList.of("build abc: phony very-long one-more"), builder);
    NinjaTargetsFunction.parseTargetExpression(
        ImmutableList.of("build cde: phony | implicit || order-only"), builder);
    NinjaTargetsFunction.parseTargetExpression(
        ImmutableList.of("build result: some-command abc cde"), builder);

    NinjaTargetsValue value = builder.build();
    List<NinjaTarget> targets = Lists.newArrayList(value.getTargets());
    assertThat(targets).hasSize(3);

    List<NinjaTarget> replaced = NinjaBuildRuleConfiguredTargetFactory.replaceAliases(targets);
    assertThat(replaced).hasSize(1);
    assertThat(replaced).containsExactly(
        NinjaTarget.builder()
            .setCommand("some-command")
            .addOutputs("result")
            .addInputs("very-long", "one-more", "implicit", "order-only")
            .build()
    );
  }

  // Let's assume it's good enough for now.
  // TODO(ichern@): test chunks in both file channel reader and ninja targets function
  @Test
  public void testReadingTargetsFromFile() throws Exception {
    List<String> lines = Lists.newArrayList(
        "some = stuff to be skipped",
        "build out1 out2 | implOut1 implOut2 : command inp1 ${inp2} | implInp1 implInp2 "
            + "|| orderInp1 orderInp2",
        "cc     = clang",
        "cflags = -Weverything",
        "other = one two "
        );
    // generate more contents
    for (int i = 0; i < 1000; i++) {
      lines.add("default abc" + i);
    }
    Path buildNinja = Files.createTempFile("test", ".ninja");
    PathUtils.writeFile(buildNinja, lines.toArray(new String[0]));
    Root root = Root.absoluteRoot(FileSystems.getNativeFileSystem());
    RootedPath rootedPath = RootedPath.toRootedPath(root,
        PathFragment.create(buildNinja.toString()));

    NinjaTargetsValue.Key key = NinjaTargetsValue.Key.create(rootedPath, 0, 0, -1);
    NinjaTargetsValue.Builder builder = NinjaTargetsValue.builder();
    NinjaTargetsFunction.parseFileFragmentForNinjaTargets(key, rootedPath, builder);

    NinjaTargetsValue value = builder.build();
    assertThat(value.getTargets()).hasSize(1);
    assertThat(value.getTargets().get(0).getCommand()).isEqualTo("command");

    assertThat(value.getDefaults()).hasSize(1000);

    ImmutableSortedMap.Builder<String, String> vBuilder = ImmutableSortedMap.naturalOrder();
    vBuilder.put("cc", "clang");
    vBuilder.put("cflags", "-Weverything");
    vBuilder.put("other", "one two");
    vBuilder.put("some", "stuff to be skipped");
    assertThat(value.getVariables()).containsExactlyEntriesIn(vBuilder.build());
  }

  @Test
  public void testReadingTargetsWithPool() throws Exception {
    List<String> lines = Lists.newArrayList(
        "some = stuff to be skipped",
        "build out1: command inp1 ${inp2}",
        "  pool =",
        "build out2: commandxx inp3 inp4",
        "  pool = xxx");
    Path buildNinja = Files.createTempFile("test", ".ninja");
    PathUtils.writeFile(buildNinja, lines.toArray(new String[0]));
    Root root = Root.absoluteRoot(FileSystems.getNativeFileSystem());
    RootedPath rootedPath = RootedPath.toRootedPath(root,
        PathFragment.create(buildNinja.toString()));

    NinjaTargetsValue.Key key = NinjaTargetsValue.Key.create(rootedPath, 0, 0, -1);
    NinjaTargetsValue.Builder builder = NinjaTargetsValue.builder();
    NinjaTargetsFunction.parseFileFragmentForNinjaTargets(key, rootedPath, builder);

    NinjaTargetsValue value = builder.build();
    assertThat(value.getTargets()).hasSize(2);
    List<String> outputs = Lists.newArrayList();
    value.getTargets().forEach(t -> outputs.addAll(t.getOutputs()));
    assertThat(outputs).containsExactly("out1", "out2");
  }

  @Test
  public void testCaresExample() throws Exception {
    String rulesText = "rule C_SHARED_LIBRARY_LINKER__c-ares\n"
        + "  command = $PRE_LINK && /usr/bin/cc -fPIC $LANGUAGE_COMPILE_FLAGS $ARCH_FLAGS "
        + "$LINK_FLAGS -shared $SONAME_FLAG$SONAME -o $TARGET_FILE $in $LINK_PATH $LINK_LIBRARIES "
        + "&& $POST_BUILD\n"
        + "  description = Linking C shared library $TARGET_FILE\n"
        + "  restat = $RESTAT\n";
    String targetsText =
        "build lib/libcares.so.2.2.0: C_SHARED_LIBRARY_LINKER__c-ares "
            + "CMakeFiles/c-ares.dir/ares__close_sockets.c.o "
            + "CMakeFiles/c-ares.dir/ares__get_hostent.c.o CMakeFiles/c-ares.dir/ares__read_line.c.o "
            + "CMakeFiles/c-ares.dir/ares__timeval.c.o CMakeFiles/c-ares.dir/ares_android.c.o "
            + "CMakeFiles/c-ares.dir/ares_cancel.c.o CMakeFiles/c-ares.dir/ares_data.c.o "
            + "CMakeFiles/c-ares.dir/ares_destroy.c.o CMakeFiles/c-ares.dir/ares_expand_name.c.o "
            + "CMakeFiles/c-ares.dir/ares_expand_string.c.o CMakeFiles/c-ares.dir/ares_fds.c.o "
            + "CMakeFiles/c-ares.dir/ares_free_hostent.c.o CMakeFiles/c-ares.dir/ares_free_string.c.o "
            + "CMakeFiles/c-ares.dir/ares_getenv.c.o CMakeFiles/c-ares.dir/ares_gethostbyaddr.c.o "
            + "CMakeFiles/c-ares.dir/ares_gethostbyname.c.o CMakeFiles/c-ares.dir/ares_getnameinfo.c.o "
            + "CMakeFiles/c-ares.dir/ares_getsock.c.o CMakeFiles/c-ares.dir/ares_init.c.o "
            + "CMakeFiles/c-ares.dir/ares_library_init.c.o CMakeFiles/c-ares.dir/ares_llist.c.o "
            + "CMakeFiles/c-ares.dir/ares_mkquery.c.o CMakeFiles/c-ares.dir/ares_create_query.c.o "
            + "CMakeFiles/c-ares.dir/ares_nowarn.c.o CMakeFiles/c-ares.dir/ares_options.c.o "
            + "CMakeFiles/c-ares.dir/ares_parse_a_reply.c.o "
            + "CMakeFiles/c-ares.dir/ares_parse_aaaa_reply.c.o "
            + "CMakeFiles/c-ares.dir/ares_parse_mx_reply.c.o "
            + "CMakeFiles/c-ares.dir/ares_parse_naptr_reply.c.o "
            + "CMakeFiles/c-ares.dir/ares_parse_ns_reply.c.o CMakeFiles/c-ares.dir/ares_parse_ptr_reply.c.o CMakeFiles/c-ares.dir/ares_parse_soa_reply.c.o CMakeFiles/c-ares.dir/ares_parse_srv_reply.c.o CMakeFiles/c-ares.dir/ares_parse_txt_reply.c.o CMakeFiles/c-ares.dir/ares_platform.c.o CMakeFiles/c-ares.dir/ares_process.c.o CMakeFiles/c-ares.dir/ares_query.c.o CMakeFiles/c-ares.dir/ares_search.c.o CMakeFiles/c-ares.dir/ares_send.c.o CMakeFiles/c-ares.dir/ares_strcasecmp.c.o CMakeFiles/c-ares.dir/ares_strdup.c.o CMakeFiles/c-ares.dir/ares_strerror.c.o CMakeFiles/c-ares.dir/ares_timeout.c.o CMakeFiles/c-ares.dir/ares_version.c.o CMakeFiles/c-ares.dir/ares_writev.c.o CMakeFiles/c-ares.dir/bitncmp.c.o CMakeFiles/c-ares.dir/inet_net_pton.c.o CMakeFiles/c-ares.dir/inet_ntop.c.o CMakeFiles/c-ares.dir/windows_port.c.o\n"
            + "  LINK_LIBRARIES = -lnsl -lrt\n"
            + "  OBJECT_DIR = CMakeFiles/c-ares.dir\n"
            + "  POST_BUILD = :\n"
            + "  PRE_LINK = :\n"
            + "  SONAME = libcares.so.2\n"
            + "  SONAME_FLAG = -Wl,-soname,\n"
            + "  TARGET_COMPILE_PDB = CMakeFiles/c-ares.dir/\n"
            + "  TARGET_FILE = lib/libcares.so.2.2.0\n"
            + "  TARGET_PDB = lib/libcares.pdb\n";

    NinjaTargetsValue.Builder builder = NinjaTargetsValue.builder();
    NinjaTargetsFunction.parseTargetExpression(Arrays.asList((rulesText).split("\n")), builder);
    NinjaTargetsFunction.parseTargetExpression(Arrays.asList((targetsText).split("\n")), builder);

    NinjaTargetsValue ninjaTargetsValue = builder.build();
    List<NinjaTarget> targets = ninjaTargetsValue.getTargets();
    assertThat(targets).hasSize(1);
    ImmutableSortedMap<String, NinjaRule> rules = ninjaTargetsValue.getRules();
    assertThat(rules).hasSize(1);

    String command = NinjaBuildRuleConfiguredTargetFactory
        .replaceParameters(targets.get(0), rules.get("C_SHARED_LIBRARY_LINKER__c-ares"),
            ImmutableSortedMap.of(),
            Function.identity());

    ImmutableSet<String> variables = ImmutableSet.of("LINK_LIBRARIES", "OBJECT_DIR", "POST_BUILD",
        "PRE_LINK", "SONAME", "SONAME_FLAG", "TARGET_COMPILE_PDB", "TARGET_FILE", "TARGET_PDB");

    for (String variable : variables) {
      assertThat(command).doesNotContain(variable);
    }
    System.out.println("command: " + command);
  }

  private TokenProcessor expect(String text, Runnable r) {
    r.run();
    return s -> assertThat(s).isEqualTo(text);
  }
}
