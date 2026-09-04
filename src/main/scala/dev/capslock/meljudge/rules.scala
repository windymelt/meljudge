package dev.capslock.meljudge

/** Data model of the rule language. See docs/rules.md for the specification.
  */
enum Action:
  case Pass, Block, Ask, Delegate

/** An argv prefix. The first word names the command; the rest are matched
  * exactly against the following argv words.
  */
final case class Pattern(words: Vector[String])

enum Condition:
  case All
  /** Some pattern matches the start of argv. */
  case Prefix(patterns: Vector[Pattern])
  /** Some pattern matches a contiguous run of argv words at any offset. */
  case Has(patterns: Vector[Pattern])

/** @param line
  *   1-based line number of the rule in the rule file, used in log entries
  *   and diagnostics
  * @param reason
  *   explanation attached with `reason "..."`, prepended to the decision
  *   reason when this rule decides a line
  */
final case class Rule(
    action: Action,
    log: Boolean,
    conditions: Vector[Condition],
    line: Int,
    reason: Option[String] = None,
)

final case class Settings(
    shfmt: Option[String] = None,
    log: Option[String] = None,
)

final case class RuleSet(settings: Settings, rules: Vector[Rule])

/** A rule file that failed to load. `line` is 1-based. */
final case class ConfigError(line: Int, message: String):
  override def toString: String = s"line $line: $message"
