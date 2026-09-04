package dev.capslock.meljudge

class JudgeSuite extends munit.FunSuite:
  private def rules(src: String): RuleSet =
    RuleParser.parse(src) match
      case Right(rs) => rs
      case Left(err) => fail(s"parse failed: $err")

  private def cmd(words: String*): SimpleCommand =
    SimpleCommand(words.toVector.map(Word.lit))

  private def decide(src: String, cmds: SimpleCommand*): Decision =
    Judge.judge(rules(src), cmds.toVector).decision

  private val example = """set log /dev/null
    |ask       prefix [git]
    |pass      prefix [git status, git diff, git log]
    |pass log  prefix [gh]
    |delegate  prefix [gh auth]
    |block     prefix [rm -rf, git push --force]
    |""".stripMargin

  test("no commands delegate"):
    assertEquals(decide(example), Decision.Delegate)

  test("unmatched command delegates"):
    assertEquals(decide(example, cmd("ls")), Decision.Delegate)

  test("last matching rule wins per command"):
    assertEquals(decide(example, cmd("git", "status")), Decision.Allow)
    assertEquals(decide(example, cmd("git", "commit")), Decision.Ask)
    assertEquals(decide(example, cmd("git", "push", "--force")), Decision.Deny)

  test("a later broad rule overrides an earlier specific one"):
    val misordered = "block prefix [git push --force]\nask prefix [git]"
    assertEquals(decide(misordered, cmd("git", "push", "--force")), Decision.Ask)

  test("delegate rule overrides an earlier pass"):
    assertEquals(decide(example, cmd("gh", "pr", "list")), Decision.Allow)
    assertEquals(decide(example, cmd("gh", "auth", "token")), Decision.Delegate)

  test("block anywhere in the line denies"):
    assertEquals(decide(example, cmd("git", "status"), cmd("rm", "-rf", "/")), Decision.Deny)

  test("ask beats pass and delegate"):
    assertEquals(decide(example, cmd("git", "status"), cmd("git", "commit"), cmd("ls")), Decision.Ask)

  test("every command must pass for allow"):
    assertEquals(decide(example, cmd("git", "status"), cmd("git", "diff")), Decision.Allow)
    assertEquals(decide(example, cmd("git", "status"), cmd("ls")), Decision.Delegate)

  test("assignment-only commands are not evaluated"):
    assertEquals(decide(example, cmd("git", "status"), SimpleCommand(Vector.empty, envPrefix = true)), Decision.Allow)
    assertEquals(decide("block all", SimpleCommand(Vector.empty)), Decision.Delegate)

  test("all matches every evaluated command"):
    assertEquals(decide("block all", cmd("ls")), Decision.Deny)
    assertEquals(decide("block all\npass prefix [ls]", cmd("ls"), cmd("pwd")), Decision.Deny)

  test("pattern is an argv prefix"):
    assertEquals(decide("pass prefix [git status]", cmd("git", "status", "--short")), Decision.Allow)
    assertEquals(decide("pass prefix [git status]", cmd("git")), Decision.Delegate)
    assertEquals(decide("pass prefix [git status]", cmd("git", "statusx")), Decision.Delegate)

  test("uncertain words never satisfy a pattern word"):
    val uncertainSub = SimpleCommand(Vector(Word.lit("git"), Word.uncertain))
    assertEquals(decide("pass prefix [git status]", uncertainSub), Decision.Delegate)
    assertEquals(decide("block prefix [git push]", uncertainSub), Decision.Delegate)
    // Words beyond the pattern length are not inspected.
    assertEquals(decide("pass prefix [git]", uncertainSub), Decision.Allow)
    assertEquals(decide("block prefix [rm]", SimpleCommand(Vector(Word.uncertain))), Decision.Delegate)

  test("block and ask match the basename of argv[0]"):
    assertEquals(decide("block prefix [git]", cmd("/usr/bin/git", "push")), Decision.Deny)
    assertEquals(decide("block prefix [git]", cmd("./git")), Decision.Deny)
    assertEquals(decide("ask prefix [git]", cmd("/usr/bin/git")), Decision.Ask)
    assertEquals(decide("block prefix [git]", cmd("gitx")), Decision.Delegate)

  test("pass and delegate require a bare command name"):
    assertEquals(decide("pass prefix [git]", cmd("/usr/bin/git")), Decision.Delegate)
    assertEquals(decide("pass prefix [git]", cmd("./git")), Decision.Delegate)
    assertEquals(decide("block prefix [gh]\ndelegate prefix [gh]", cmd("./gh")), Decision.Deny)
    assertEquals(decide("block prefix [gh]\ndelegate prefix [gh]", cmd("gh")), Decision.Delegate)

  test("has matches a contiguous run at any offset"):
    assertEquals(decide("block has [--force]", cmd("git", "push", "--force", "origin")), Decision.Deny)
    assertEquals(decide("block has [rm -rf]", cmd("sudo", "rm", "-rf", "x")), Decision.Deny)
    assertEquals(decide("block has [rm -rf]", cmd("rm", "-rf", "x")), Decision.Deny)
    assertEquals(decide("block has [rm -rf]", cmd("rm", "x", "-rf")), Decision.Delegate)
    assertEquals(decide("block has [git push]", cmd("git", "pushx")), Decision.Delegate)

  test("has gives argv[0] no special treatment"):
    assertEquals(decide("block has [git]", cmd("/usr/bin/git", "push")), Decision.Delegate)
    assertEquals(decide("pass has [git]", cmd("./git")), Decision.Delegate)
    assertEquals(decide("pass has [git]", cmd("git")), Decision.Allow)

  test("has skips uncertain words but keeps searching"):
    val c = SimpleCommand(Vector(Word.lit("git"), Word.uncertain, Word.lit("--force")))
    assertEquals(decide("block has [--force]", c), Decision.Deny)
    assertEquals(decide("block has [git --force]", c), Decision.Delegate)
    assertEquals(decide("block has [x]", SimpleCommand(Vector(Word.uncertain))), Decision.Delegate)

  test("has and prefix are conjoined"):
    assertEquals(decide("block prefix [git push] has [--force]", cmd("git", "push", "--force")), Decision.Deny)
    assertEquals(decide("block prefix [git push] has [--force]", cmd("git", "push")), Decision.Delegate)
    assertEquals(decide("block prefix [git push] has [--force]", cmd("gh", "--force")), Decision.Delegate)

  test("multiple conditions are conjoined"):
    assertEquals(decide("block prefix [git] prefix [git push]", cmd("git", "push")), Decision.Deny)
    assertEquals(decide("block prefix [git] prefix [git push]", cmd("git", "pull")), Decision.Delegate)

  test("log entries come from the adopted rule only"):
    val rs = rules("""set log /dev/null
      |block log prefix [rm]
      |pass log  prefix [gh]
      |pass      prefix [gh pr]
      |""".stripMargin)
    val v = Judge.judge(rs, Vector(cmd("rm", "x"), cmd("gh", "auth"), cmd("gh", "pr", "list"), cmd("ls")))
    assertEquals(v.decision, Decision.Deny)
    assertEquals(v.logs.map(e => (e.command.words.map(_.text), e.rule.line)),
      Vector((Vector("rm", "x"), 2), (Vector("gh", "auth"), 3)))
