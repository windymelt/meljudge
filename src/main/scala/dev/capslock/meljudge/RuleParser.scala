package dev.capslock.meljudge

import fastparse.*
import NoWhitespace.*

/** Parses a rule file into a [[RuleSet]]. The grammar and the static
  * validation rules are specified in docs/rules.md.
  */
object RuleParser:

  def parse(source: String): Either[ConfigError, RuleSet] =
    fastparse.parse(source, file(using _)) match
      case Parsed.Success(statements, _) => validate(source, statements)
      case f: Parsed.Failure =>
        val (line, col) = lineCol(source, f.index)
        Left(ConfigError(line, s"syntax error at column $col: ${f.trace().label}"))

  // ---------------------------------------------------------------------
  // Grammar

  private enum Statement:
    case Set(name: String, value: String, line: Int)
    case RuleStmt(rule: Rule)

  private def hs[$: P]: P[Unit] = P(CharsWhileIn(" \t", 1))
  private def hsOpt[$: P]: P[Unit] = P(CharsWhileIn(" \t", 0))
  private def comment[$: P]: P[Unit] = P("#" ~ CharsWhile(_ != '\n', 0))
  private def ws[$: P]: P[Unit] = P((CharsWhileIn(" \t\n", 1) | comment).rep)

  private def bareword[$: P]: P[String] =
    P(CharsWhile(c => !" \t\n,[]\"#".contains(c), 1).!)

  private def escape[$: P]: P[String] =
    P("\\" ~/ CharPred(c => c == '"' || c == '\\').!)

  private def quoted[$: P]: P[String] =
    P("\"" ~/ (CharsWhile(c => c != '"' && c != '\\' && c != '\n', 1).! | escape).rep ~ "\"")
      .map(_.mkString)

  private def word[$: P]: P[String] = P(quoted | bareword)

  private def pattern[$: P]: P[Pattern] =
    P(word ~ (hs ~ word).rep).map((h, t) => Pattern(h +: t.toVector))

  private def list[$: P]: P[Vector[Pattern]] =
    P("[" ~/ ws ~ pattern.rep(sep = ws ~ "," ~ ws) ~ ws ~ "]").map(_.toVector)

  private def prefixCond[$: P]: P[Condition] = P("prefix" ~/ hsOpt ~ list).map(Condition.Prefix(_))
  private def hasCond[$: P]: P[Condition] = P("has" ~/ hsOpt ~ list).map(Condition.Has(_))

  private def condition[$: P]: P[Condition] =
    P(("all" ~ &(hs | lineEnd)).map(_ => Condition.All) | prefixCond | hasCond)

  private def action[$: P]: P[Action] =
    P(StringIn("pass", "block", "ask", "delegate").! ~ &(hs)).map {
      case "pass"     => Action.Pass
      case "block"    => Action.Block
      case "ask"      => Action.Ask
      case "delegate" => Action.Delegate
    }

  private def logFlag[$: P]: P[Boolean] =
    P((hs ~ "log" ~ &(hs)).!.?).map(_.isDefined)

  private def reasonMod[$: P]: P[String] = P(hs ~ "reason" ~/ hs ~ quoted)

  private def rule[$: P]: P[Statement] =
    P(Index ~ action ~/ logFlag ~ (hs ~ condition).rep(1) ~ reasonMod.?)
      .map((idx, a, l, cs, r) => Statement.RuleStmt(Rule(a, l, cs.toVector, idx, r)))

  private def set[$: P]: P[Statement] =
    P(Index ~ "set" ~ hs ~/ bareword ~ hs ~ word).map((idx, n, v) => Statement.Set(n, v, idx))

  private def statement[$: P]: P[Statement] = P(set | rule)

  private def lineEnd[$: P]: P[Unit] = P(hsOpt ~ comment.? ~ &("\n" | End))

  private def line[$: P]: P[Option[Statement]] =
    P(hsOpt ~ statement.? ~/ lineEnd)

  private def file[$: P]: P[Seq[Statement]] =
    P(Start ~ line.rep(sep = "\n") ~ End).map(_.flatten)

  // ---------------------------------------------------------------------
  // Validation

  private def validate(source: String, statements: Seq[Statement]): Either[ConfigError, RuleSet] =
    // Statements carry character offsets; convert them to line numbers first.
    def lineOf(offset: Int): Int = lineCol(source, offset)._1
    var settings = Settings()
    val rules = Vector.newBuilder[Rule]
    var error: Option[ConfigError] = None

    def fail(line: Int, msg: String): Unit =
      if error.isEmpty then error = Some(ConfigError(line, msg))

    statements.foreach {
      case Statement.Set(name, value, offset) =>
        val line = lineOf(offset)
        name match
          case "shfmt" =>
            if settings.shfmt.isDefined then fail(line, "setting shfmt is set twice")
            else settings = settings.copy(shfmt = Some(value))
          case "log" =>
            if settings.log.isDefined then fail(line, "setting log is set twice")
            else settings = settings.copy(log = Some(value))
          case other => fail(line, s"unknown setting: $other")
      case Statement.RuleStmt(r0) =>
        val r = r0.copy(line = lineOf(r0.line))
        validateRule(r).foreach(msg => fail(r.line, msg))
        rules += r
    }

    val allRules = rules.result()
    error.toLeft(RuleSet(settings, allRules))

  private def validateRule(r: Rule): Option[String] =
    val hasAll = r.conditions.contains(Condition.All)
    if hasAll && r.conditions.size > 1 then Some("all cannot be combined with other conditions")
    else if r.reason.contains("") then Some("reason is empty")
    else
      r.conditions.collectFirst {
        case Condition.Prefix(ps) if ps.isEmpty => "prefix list is empty"
        case Condition.Has(ps) if ps.isEmpty    => "has list is empty"
        case c if patternsOf(c).exists(_.words.isEmpty)            => "pattern has no words"
        case c if patternsOf(c).exists(_.words.exists(_.isEmpty)) => "pattern has an empty word"
        case Condition.Prefix(ps) if ps.exists(_.words.head.contains('/')) =>
          "first word of a prefix pattern must not contain /"
      }

  private def patternsOf(c: Condition): Vector[Pattern] =
    c match
      case Condition.All        => Vector.empty
      case Condition.Prefix(ps) => ps
      case Condition.Has(ps)    => ps

  private def lineCol(source: String, index: Int): (Int, Int) =
    val before = source.take(index)
    val line = before.count(_ == '\n') + 1
    val col = index - (before.lastIndexOf('\n') + 1) + 1
    (line, col)
