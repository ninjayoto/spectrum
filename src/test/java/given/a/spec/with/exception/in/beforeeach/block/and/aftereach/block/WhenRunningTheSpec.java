package given.a.spec.with.exception.in.beforeeach.block.and.aftereach.block;

import static matchers.IsFailure.failure;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.greghaskins.spectrum.SpectrumHelper;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.Result;

public class WhenRunningTheSpec {

  private Result result;

  @Before
  public void before() throws Exception {
    this.result =
        SpectrumHelper.run(Fixture.getSpecThatThrowsAnExceptionInBeforeEachAndAfterEachBlocks());
  }

  @Test
  @Ignore("To discuss")
  public void thereAreTwoFailuresForEachAffectedTest() throws Exception {
    assertThat(this.result.getFailureCount(), is(4));
  }

  @Test
  public void theFailuresExplainWhatHappened() throws Exception {
    assertThat(this.result.getFailures().get(0),
        is(failure("a failing test", RuntimeException.class,
            "given.a.spec.with.exception.in.beforeeach.block.and.aftereach.block."
                + "Fixture$SomeException: beforeEach went kaboom")));
    assertThat(this.result.getFailures().get(1),
        is(failure("another failing test", RuntimeException.class,
            "given.a.spec.with.exception.in.beforeeach.block.and.aftereach.block."
                + "Fixture$SomeException: beforeEach went kaboom")));
  }

}
