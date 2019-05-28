package com.google.devtools.build.lib.bazel.rules.ninja;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import com.google.devtools.build.lib.bazel.rules.ninja.NinjaTargetsFunction.Splitter;
import com.google.devtools.build.lib.bazel.rules.ninja.NinjaTargetsFunction.TokenProcessor;
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

    new Splitter(";") {
      private boolean called = false;

      @Override
      public void accept(String text) throws NinjaFileFormatSkyFunctionException {
        tail(expect("tail", () -> called = true));
        super.accept(text);
        assertThat(called).isTrue();
      }
    }.accept(" ; \ntail ");

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
          .head(expect("head", () -> {}))
          .tail(expect("tail", () -> {})).accept("No separator");
      fail("Expected NinjaFileFormatSkyFunctionException to be thrown.");
    } catch (NinjaFileFormatSkyFunctionException e) {
      // expected
    }
  }

  private TokenProcessor expect(String text, Runnable r) {
    r.run();
    return s -> assertThat(s).isEqualTo(text);
  }
}
