package dev.capslock.meljudge

class RuleParserSuite extends munit.FunSuite:
  private def ok(src: String): RuleSet =
    RuleParser.parse(src) match
      case Right(rs) => rs
      case Left(err) => fail(s"parse failed: $err")

  private def err(src: String): ConfigError =
    RuleParser.parse(src) match
      case Left(e)   => e
      case Right(rs) => fail(s"expected an error, got $rs")

  private def prefix(pats: String*): Condition =
    Condition.Prefix(pats.toVector.map(p => Pattern(p.split(' ').toVector)))

  test("empty file"):
    assertEquals(ok(""), RuleSet(Settings(), Vector.empty))

  test("comments and blank lines"):
    val rs = ok("""
      |# leading comment
      |
      |block prefix [rm]   # trailing comment
      |   # indented comment
      |""".stripMargin)
    assertEquals(rs.rules, Vector(Rule(Action.Block, false, Vector(prefix("rm")), 4)))

  test("example from the specification"):
    val rs = ok("""set shfmt "/usr/local/bin/shfmt"
      |set log "/home/me/.local/state/meljudge/log"
      |
      |ask       prefix [git]
      |pass      prefix [git status, git diff, git log]
      |pass log  prefix [gh]
      |delegate  prefix [gh auth]
      |block     prefix [rm -rf, git push --force]
      |""".stripMargin)
    assertEquals(rs.settings, Settings(Some("/usr/local/bin/shfmt"), Some("/home/me/.local/state/meljudge/log")))
    assertEquals(
      rs.rules,
      Vector(
        Rule(Action.Ask, false, Vector(prefix("git")), 4),
        Rule(Action.Pass, false, Vector(prefix("git status", "git diff", "git log")), 5),
        Rule(Action.Pass, true, Vector(prefix("gh")), 6),
        Rule(Action.Delegate, false, Vector(prefix("gh auth")), 7),
        Rule(Action.Block, false, Vector(prefix("rm -rf", "git push --force")), 8),
      ),
    )

  test("all condition"):
    assertEquals(ok("block all").rules, Vector(Rule(Action.Block, false, Vector(Condition.All), 1)))

  test("multiple conditions are kept in order"):
    assertEquals(
      ok("block prefix [git] prefix [git push]").rules.head.conditions,
      Vector(prefix("git"), prefix("git push")),
    )

  test("quoted words"):
    val rs = ok("""pass prefix ["git commit -m", "a,b", "x]y", "say \"hi\"", "back\\slash", 'single]""")
    assertEquals(
      rs.rules.head.conditions,
      Vector(Condition.Prefix(Vector(
        Pattern(Vector("git commit -m")),
        Pattern(Vector("a,b")),
        Pattern(Vector("x]y")),
        Pattern(Vector("say \"hi\"")),
        Pattern(Vector("back\\slash")),
        Pattern(Vector("'single")),
      ))),
    )

  test("quoting is optional and does not affect the value"):
    assertEquals(ok("""pass prefix ["git" "status"]""").rules.head.conditions, Vector(prefix("git status")))

  test("prefix list may span lines with comments"):
    val rs = ok("""block prefix [
      |  rm -rf,   # dangerous
      |  git push --force
      |]
      |pass prefix [ls]
      |""".stripMargin)
    assertEquals(rs.rules.map(_.line), Vector(1, 5))
    assertEquals(rs.rules.head.conditions, Vector(prefix("rm -rf", "git push --force")))

  test("set value may be a bare word"):
    assertEquals(ok("set shfmt shfmt").settings.shfmt, Some("shfmt"))

  test("syntax error reports the line"):
    val e = err("pass prefix [ls]\nblock prefix rm\n")
    assertEquals(e.line, 2)
    assert(e.message.startsWith("syntax error"), e.message)

  test("unknown action is a syntax error"):
    assertEquals(err("allow prefix [ls]").line, 1)
    assertEquals(err("pass prefix [ls]\n\nallow prefix [ls]\n").line, 3)

  test("unknown backslash escape is a syntax error"):
    assertEquals(err("""pass prefix ["a\nb"]""").line, 1)

  test("all cannot be combined"):
    assertEquals(err("pass prefix [ls]\nblock all prefix [rm]").message, "all cannot be combined with other conditions")
    assertEquals(err("block prefix [rm] all").line, 1)

  test("empty prefix list"):
    assertEquals(err("block prefix []").message, "prefix list is empty")
    assertEquals(err("block prefix [ ]").message, "prefix list is empty")

  test("empty word in pattern"):
    assertEquals(err("""block prefix [""]""").message, "pattern has an empty word")
    assertEquals(err("""block prefix [git ""]""").message, "pattern has an empty word")

  test("first word must not contain a slash"):
    assertEquals(err("block prefix [/usr/bin/git]").message, "first word of a pattern must not contain /")
    assertEquals(ok("block prefix [cat ./x]").rules.size, 1)

  test("unknown setting"):
    assertEquals(err("set foo bar").message, "unknown setting: foo")

  test("duplicate setting"):
    assertEquals(err("set log a\nset log b"), ConfigError(2, "setting log is set twice"))

  test("log does not require set log"):
    assertEquals(ok("pass log prefix [gh]").rules.head.log, true)
    assertEquals(ok("set log x\nblock log prefix [rm]").rules.head.log, true)

  test("rule with no condition is a syntax error"):
    assertEquals(err("block").line, 1)
    assertEquals(err("block log").line, 1)

  test("condition keyword is required"):
    assertEquals(err("block [rm]").line, 1)
