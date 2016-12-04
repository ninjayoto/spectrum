package com.greghaskins.spectrum;

import static com.greghaskins.spectrum.internal.AfterHook.after;
import static com.greghaskins.spectrum.internal.BeforeHook.before;

import com.greghaskins.spectrum.internal.Atomic;
import com.greghaskins.spectrum.internal.Child;
import com.greghaskins.spectrum.internal.NotifyingBlock;
import com.greghaskins.spectrum.internal.Parent;
import com.greghaskins.spectrum.internal.PreConditionBlock;
import com.greghaskins.spectrum.model.HookContext;
import com.greghaskins.spectrum.model.Hooks;
import com.greghaskins.spectrum.model.IdempotentBlock;
import com.greghaskins.spectrum.model.PreConditions;
import com.greghaskins.spectrum.model.TaggingState;

import org.junit.runner.Description;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunNotifier;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

class Suite implements Parent, Child {
  // the hooks - they will be turned into a chain of responsibility
  // so the first one will be executed last as the chain is built up
  // from first to last.
  private Hooks hooks = new Hooks();

  protected final List<Child> children = new ArrayList<>();
  private final Set<Child> focusedChildren = new HashSet<>();

  private final ChildRunner childRunner;

  private final Description description;
  private final Parent parent;
  private boolean ignored;

  private final TaggingState tagging;
  private PreConditions preconditions = PreConditions.Factory.defaultPreConditions();
  private Set<String> namesUsed = new HashSet<>();

  /**
   * The strategy for running the children within the suite.
   */
  @FunctionalInterface
  interface ChildRunner {
    void runChildren(final Suite suite, final RunNotifier notifier);
  }

  static Suite rootSuite(final Description description) {
    return new Suite(description, Parent.NONE, Suite::defaultChildRunner, new TaggingState());
  }

  /**
   * Constructs a suite.
   *
   * @param description the JUnit description
   * @param parent parent item
   * @param childRunner which child running strategy to use - this will normally be
   *        {@link #defaultChildRunner(Suite, RunNotifier)} which runs them all but can be
   *        substituted.
   * @param taggingState the state of tagging inherited from the parent
   */
  protected Suite(final Description description, final Parent parent, final ChildRunner childRunner,
      final TaggingState taggingState) {
    this.description = description;
    this.parent = parent;
    this.ignored = parent.isIgnored();
    this.childRunner = childRunner;
    this.tagging = taggingState;
  }

  Suite addSuite(final String name) {
    return addSuite(name, Suite::defaultChildRunner);
  }

  Suite addSuite(final String name, final ChildRunner childRunner) {
    final Suite suite =
        new Suite(Description.createSuiteDescription(sanitise(name)), this, childRunner,
            this.tagging.clone());

    return addedToThis(suite);
  }

  private Suite addedToThis(Suite suite) {
    addChild(suite);

    return suite;
  }

  Suite addAbortingSuite(final String name) {
    final Suite suite =
        new CompositeTest(Description.createSuiteDescription(sanitise(name)), this,
            this.tagging.clone());

    return addedToThis(suite);
  }

  Child addSpec(final String name, final Block block) {
    final Child spec = createSpec(name, block);
    addChild(spec);

    return spec;
  }

  private Child createSpec(final String name, final Block block) {
    final Description specDescription =
        Description.createTestDescription(this.description.getClassName(), sanitise(name));

    final NotifyingBlock specBlockInContext = NotifyingBlock.wrap(block);

    PreConditionBlock preConditionBlock =
        PreConditionBlock.with(this.preconditions.forChild(), block);

    return new Spec(specDescription, specBlockInContext, this).applyPreConditions(preConditionBlock,
        this.tagging);
  }

  private void addChild(final Child child) {
    this.children.add(child);
  }

  void beforeAll(final Block block) {
    addHook(new HookContext(before(new IdempotentBlock(block)), false, false, true));
  }

  void afterAll(final Block block) {
    addHook(new HookContext(after(block), false, true, false));
  }

  void beforeEach(final Block block) {
    addHook(new HookContext(before(block), true, false, false));
  }

  void afterEach(final Block block) {
    addHook(new HookContext(after(block), true, false, false));
  }

  /**
   * Adds a hook to be the first one executed before the block.
   * This is the default. Hooks should be executed in the order they
   * are declared in the test.
   * @param hook to add
   */
  void addHook(final HookContext hook) {
    this.hooks.addFirst(hook);
  }

  /**
   * Insert a hook closest to the start of the chain of responsibility. This is for situations
   * where a hook is only creatable after test definition, and needs to be run first.
   * @param hook to add
   */
  void insertHook(final HookContext hook) {
    this.hooks.add(hook);
  }

  Hooks getHooksFor(final Child child) {
    Hooks allHooks = parent.getInheritableHooks().plus(hooks);

    return child instanceof Atomic ? allHooks.forAtomic() : allHooks.forNonAtomic();
  }

  @Override
  public Hooks getInheritableHooks() {
    // only the atomic hooks can be used by the children of this suite,
    // all other hooks would be executed at suite level only - either for
    // each child of the suite, or once

    return parent.getInheritableHooks().plus(hooks.forAtomic());
  }

  /**
   * Set the suite to require certain tags of all tests below.
   *
   * @param tags required tags - suites must have at least one of these if any are specified
   */
  void includeTags(final String... tags) {
    this.tagging.include(tags);
  }

  /**
   * Set the suite to exclude certain tags of all tests below.
   *
   * @param tags excluded tags - suites and specs must not have any of these if any are specified
   */
  void excludeTags(final String... tags) {
    this.tagging.exclude(tags);
  }

  void applyPreConditions(Block block) {
    this.preconditions = Child.findApplicablePreconditions(block);
    applyPreConditions(block, this.tagging);
  }

  @Override
  public void focus(final Child child) {
    this.focusedChildren.add(child);
    focus();
  }

  @Override
  public void focus() {
    if (this.ignored) {
      return;
    }

    this.parent.focus(this);
  }

  @Override
  public void ignore() {
    this.ignored = true;
  }

  @Override
  public boolean isIgnored() {
    return this.ignored;
  }

  @Override
  public void run(final RunNotifier notifier) {
    if (testCount() == 0) {
      notifier.fireTestIgnored(this.description);
      runChildren(notifier);
    } else {
      runSuite(notifier);
    }
  }

  private void runSuite(final RunNotifier notifier) {
    hooks.once().runAround(description, notifier, () -> doRunSuite(notifier));
  }

  private void doRunSuite(final RunNotifier notifier) {

    NotifyingBlock.run(this.description, notifier, () -> {
      runChildren(notifier);
    });
  }

  private void runChildren(final RunNotifier notifier) {
    this.childRunner.runChildren(this, notifier);
  }

  protected void runChild(final Child child, final RunNotifier notifier) {
    if (this.focusedChildren.isEmpty() || this.focusedChildren.contains(child)) {
      hooks.forThisLevel().runAround(child.getDescription(), notifier,
          () -> runChildWithHooksInNotifierBlock(child, notifier));
    } else {
      notifier.fireTestIgnored(child.getDescription());
    }
  }

  private void runChildWithHooksInNotifierBlock(final Child child, final RunNotifier notifier) {
    NotifyingBlock.run(child.getDescription(), notifier,
        () -> getHooksFor(child).runAround(child.getDescription(), notifier,
            () -> child.run(notifier)));
  }

  @Override
  public Description getDescription() {
    final Description copy = this.description.childlessCopy();
    this.children.forEach((child) -> copy.addChild(child.getDescription()));

    return copy;
  }

  @Override
  public int testCount() {
    return this.children.stream().mapToInt(Child::testCount).sum();
  }

  void removeAllChildren() {
    this.children.clear();
  }

  private static void defaultChildRunner(final Suite suite, final RunNotifier runNotifier) {
    suite.children.forEach((child) -> suite.runChild(child, runNotifier));
  }

  private String sanitise(final String name) {
    String sanitised = name.replaceAll("\\(", "[")
        .replaceAll("\\)", "]");

    int suffix = 1;
    String deDuplicated = sanitised;
    while (this.namesUsed.contains(deDuplicated)) {
      deDuplicated = sanitised + "_" + suffix++;
    }
    this.namesUsed.add(deDuplicated);

    return deDuplicated;
  }

  public void aroundEach(Hook consumer) {
    addHook(new HookContext(consumer, true, false, false));
  }

  public void aroundAll(Hook consumer) {
    addHook(new HookContext(consumer, false, true, false));
  }
}
