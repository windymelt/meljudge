package dev.capslock.meljudge

class ShfmtCommandParserSuite extends munit.FunSuite:
  private val parser = ShfmtCommandParser()

  private def parse(src: String): Vector[SimpleCommand] =
    parser.parse(src) match
      case Right(cmds) => cmds
      case Left(err)   => fail(s"parse failed: $err")

  private def cmd(words: String*): SimpleCommand =
    SimpleCommand(words.toVector.map(Word.lit))

  test("simple command"):
    assertEquals(parse("git push"), Vector(cmd("git", "push")))

  test("empty input"):
    assertEquals(parse(""), Vector.empty)

  test("pipeline and chains extract every command"):
    assertEquals(
      parse("git log | head && ls; pwd"),
      Vector(cmd("git", "log"), cmd("head"), cmd("ls"), cmd("pwd")),
    )

  test("command substitution is extracted as a sibling"):
    assertEquals(
      parse("echo $(gh auth token)"),
      Vector(
        SimpleCommand(Vector(Word.lit("echo"), Word.uncertain)),
        cmd("gh", "auth", "token"),
      ),
    )

  test("process substitution is extracted"):
    assertEquals(
      parse("diff <(git show a) b"),
      Vector(
        SimpleCommand(Vector(Word.lit("diff"), Word.uncertain, Word.lit("b"))),
        cmd("git", "show", "a"),
      ),
    )

  test("nested substitution"):
    assertEquals(
      parse("echo $(echo $(git push))"),
      Vector(
        SimpleCommand(Vector(Word.lit("echo"), Word.uncertain)),
        SimpleCommand(Vector(Word.lit("echo"), Word.uncertain)),
        cmd("git", "push"),
      ),
    )

  test("parameter expansion makes a word uncertain"):
    assertEquals(
      parse("git $SUBCOMMAND"),
      Vector(SimpleCommand(Vector(Word.lit("git"), Word.uncertain))),
    )

  test("glob, tilde, and escape characters make a word uncertain"):
    for src <- List("rm *", "ls ~/x", """g\it push""") do
      val words = parse(src).head.words
      assert(words.exists(!_.literal), s"expected an uncertain word in $src")

  test("quoting still yields a literal word"):
    assertEquals(parse("""git "pu"sh"""), Vector(cmd("git", "push")))
    assertEquals(parse("git 'push'"), Vector(cmd("git", "push")))

  test("dollar single quotes are uncertain"):
    assertEquals(
      parse("""echo $'a\n'"""),
      Vector(SimpleCommand(Vector(Word.lit("echo"), Word.uncertain))),
    )

  test("double quotes containing expansions are uncertain"):
    assertEquals(
      parse("""echo "a$B""""),
      Vector(SimpleCommand(Vector(Word.lit("echo"), Word.uncertain))),
    )

  test("output redirects are flagged"):
    for src <- List(
        "echo hi > f",
        "echo hi >> f",
        "echo hi &> f",
        "echo hi &>> f",
        "echo hi >| f",
        "echo hi <> f",
        "echo hi >&f",
      )
    do assert(parse(src).head.outputRedirect, s"expected outputRedirect for $src")

  test("input redirects and fd manipulation are not flagged"):
    for src <- List(
        "echo hi < f",
        "echo hi 2>&1",
        "echo hi >&2",
        "echo hi >&-",
        "cat <<< str",
        "cat <<EOF\nbody\nEOF",
      )
    do assert(!parse(src).head.outputRedirect, s"unexpected outputRedirect for $src")

  test("a redirect on a compound command propagates to inner commands"):
    val cmds = parse("{ git push; ls; } > f")
    assertEquals(cmds.map(_.words.head.text), Vector("git", "ls"))
    assert(cmds.forall(_.outputRedirect))

  test("substitutions in redirect targets run without the redirect context"):
    assertEquals(
      parse("echo x > $(mktemp).log"),
      Vector(
        SimpleCommand(Vector(Word.lit("echo"), Word.lit("x")), outputRedirect = true),
        cmd("mktemp"),
      ),
    )

  test("substitutions in heredoc bodies are extracted"):
    assertEquals(
      parse("cat <<EOF\n$(git push)\nEOF"),
      Vector(cmd("cat"), cmd("git", "push")),
    )

  test("for loop items execute substitutions"):
    assertEquals(
      parse("for i in $(seq 3); do echo $i; done"),
      Vector(
        cmd("seq", "3"),
        SimpleCommand(Vector(Word.lit("echo"), Word.uncertain)),
      ),
    )

  test("c-style loop headers execute substitutions"):
    assertEquals(
      parse("for ((i = $(width); i < 5; i++)); do render; done"),
      Vector(cmd("width"), cmd("render")),
    )

  test("case subject and patterns execute substitutions"):
    assertEquals(
      parse("case $(a) in $(b)) c ;; esac"),
      Vector(cmd("a"), cmd("b"), cmd("c")),
    )

  test("array subscripts execute substitutions"):
    assertEquals(
      parse("a[$(x)]=1"),
      Vector(SimpleCommand(Vector.empty, envPrefix = true), cmd("x")),
    )

  test("env assignment prefixes are flagged and their values extracted"):
    assertEquals(
      parse("FOO=$(x) bar"),
      Vector(SimpleCommand(Vector(Word.lit("bar")), envPrefix = true), cmd("x")),
    )

  test("function bodies are visited"):
    assertEquals(parse("f() { git push; }"), Vector(cmd("git", "push")))

  test("if and while clauses are visited"):
    assertEquals(
      parse("if a; then b; else c; fi"),
      Vector(cmd("a"), cmd("b"), cmd("c")),
    )
    assertEquals(
      parse("if a; then b; elif c; then d; else e; fi"),
      Vector(cmd("a"), cmd("b"), cmd("c"), cmd("d"), cmd("e")),
    )
    assertEquals(parse("while a; do b; done"), Vector(cmd("a"), cmd("b")))

  test("substitutions inside let and test clauses are extracted"):
    assertEquals(parse("let x=$(a)+1"), Vector(cmd("a")))
    assertEquals(parse("[[ $(a) = b ]]"), Vector(cmd("a")))

  test("invalid bash is Unparseable"):
    parser.parse("echo \"unclosed") match
      case Left(ParseError.Unparseable(msg)) => assert(msg.nonEmpty)
      case other                             => fail(s"expected Unparseable, got $other")

  test("a missing shfmt binary is a ParserFailure"):
    ShfmtCommandParser("/nonexistent/shfmt").parse("ls") match
      case Left(ParseError.ParserFailure(_)) => ()
      case other => fail(s"expected ParserFailure, got $other")
