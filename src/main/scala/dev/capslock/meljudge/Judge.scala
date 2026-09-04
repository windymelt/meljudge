package dev.capslock.meljudge

/** The decision for a whole command line. See docs/rules.md, "Line
  * decision".
  */
enum Decision:
  case Allow, Deny, Ask, Delegate

/** The verdict of one simple command: the last matching rule, if any. */
final case class CommandVerdict(command: SimpleCommand, rule: Option[Rule])

/** One entry produced by a rule carrying `log`. */
final case class LogEntry(command: SimpleCommand, rule: Rule)

final case class Verdict(decision: Decision, commands: Vector[CommandVerdict]):
  def logs: Vector[LogEntry] =
    commands.collect { case CommandVerdict(c, Some(r)) if r.log => LogEntry(c, r) }

/** Evaluates a [[RuleSet]] against the simple commands of one command line.
  * The semantics are specified in docs/rules.md.
  */
object Judge:

  def judge(rules: RuleSet, commands: Vector[SimpleCommand]): Verdict =
    val evaluated = commands.filter(_.words.nonEmpty)
    val verdicts = evaluated.map(c => CommandVerdict(c, lastMatch(rules, c)))
    val actions = verdicts.map(_.rule.map(_.action))

    val decision =
      if actions.contains(Some(Action.Block)) then Decision.Deny
      else if actions.contains(Some(Action.Ask)) then Decision.Ask
      else if actions.nonEmpty && actions.forall(_.contains(Action.Pass)) then Decision.Allow
      else Decision.Delegate

    Verdict(decision, verdicts)

  /** The last rule that matches `cmd`, if any. */
  def lastMatch(rules: RuleSet, cmd: SimpleCommand): Option[Rule] =
    rules.rules.foldLeft(Option.empty[Rule]) { (acc, r) =>
      if matches(r, cmd) then Some(r) else acc
    }

  def matches(rule: Rule, cmd: SimpleCommand): Boolean =
    rule.conditions.forall {
      case Condition.All          => true
      case Condition.Prefix(pats) => pats.exists(matchesPattern(rule.action, _, cmd))
      case Condition.Has(pats)    => pats.exists(matchesAnywhere(_, cmd))
    }

  /** `has`: the pattern equals a contiguous run of literal argv words at some
    * offset. argv[0] gets no special treatment.
    */
  private def matchesAnywhere(pat: Pattern, cmd: SimpleCommand): Boolean =
    val n = pat.words.size
    (0 to cmd.words.size - n).exists { i =>
      val window = cmd.words.slice(i, i + n)
      window.forall(_.literal) && window.map(_.text) == pat.words
    }

  private def matchesPattern(action: Action, pat: Pattern, cmd: SimpleCommand): Boolean =
    val n = pat.words.size
    cmd.words.size >= n && {
      val prefix = cmd.words.take(n)
      prefix.forall(_.literal)
        && commandNameMatches(action, pat.words.head, prefix.head.text)
        && pat.words.tail.zip(prefix.tail).forall((p, w) => p == w.text)
    }

  /** `block` and `ask` match the basename of argv[0] so that a path cannot
    * dodge them; `pass` and `delegate` require a bare command name so that a
    * path cannot impersonate a permitted command.
    */
  private def commandNameMatches(action: Action, patWord: String, argv0: String): Boolean =
    action match
      case Action.Block | Action.Ask     => basename(argv0) == patWord
      case Action.Pass | Action.Delegate => argv0 == patWord

  private def basename(path: String): String =
    path.substring(path.lastIndexOf('/') + 1)
