package dev.capslock.meljudge

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

class MainSuite extends munit.FunSuite:
  private val fixedClock: () => Long = () => 1788491565000L // 2026-09-04T03:12:45Z

  private val tmp = FunFixture[Path](
    setup = _ => Files.createTempDirectory("meljudge-test"),
    teardown = dir =>
      Files.list(dir).forEach(p => Files.delete(p))
      Files.delete(dir),
  )

  private def write(dir: Path, name: String, content: String): Path =
    val p = dir.resolve(name)
    Files.write(p, content.getBytes(StandardCharsets.UTF_8))
    p

  private def payload(command: String, tool: String = "Bash"): String =
    ujson.write(ujson.Obj("tool_name" -> tool, "tool_input" -> ujson.Obj("command" -> command)))

  private def run(stdin: String, args: String*): Main.Output =
    Main.run(() => stdin, args, env = Map.empty, clock = fixedClock)

  private def decisionOf(out: Main.Output): (String, String) =
    val o = ujson.read(out.stdout)("hookSpecificOutput")
    (o("permissionDecision").str, o("permissionDecisionReason").str)

  private val rules = """ask       prefix [git]
    |pass      prefix [git status, git diff]
    |block     prefix [rm -rf]
    |""".stripMargin

  tmp.test("deny names the command and the rule line") { dir =>
    val cfg = write(dir, "rules", rules)
    val out = run(payload("ls && rm -rf /tmp/x"), "--config", cfg.toString)
    assertEquals(decisionOf(out), ("deny", "meljudge: \"rm -rf /tmp/x\" matches block rule at line 3"))
    assertEquals(out.stderr, "")
  }

  tmp.test("ask names the command and the rule line") { dir =>
    val cfg = write(dir, "rules", rules)
    val out = run(payload("git status; git commit -m x"), "--config", cfg.toString)
    assertEquals(decisionOf(out), ("ask", "meljudge: \"git commit -m x\" matches ask rule at line 1"))
  }

  tmp.test("allow lists the pass rule lines") { dir =>
    val cfg = write(dir, "rules", rules)
    val out = run(payload("git status | head"), "--config", cfg.toString)
    // `head` is unmatched, so the line delegates.
    assertEquals(out, Main.Output())
    val out2 = run(payload("git status && git diff"), "--config", cfg.toString)
    assertEquals(decisionOf(out2), ("allow", "meljudge: every command matches a pass rule (lines 2)"))
  }

  tmp.test("uncertain words are rendered as ?") { dir =>
    val cfg = write(dir, "rules", rules)
    val out = run(payload("git push $REMOTE"), "--config", cfg.toString)
    assertEquals(decisionOf(out), ("ask", "meljudge: \"git push ?\" matches ask rule at line 1"))
  }

  tmp.test("delegate prints nothing") { dir =>
    val cfg = write(dir, "rules", rules)
    assertEquals(run(payload("ls"), "--config", cfg.toString), Main.Output())
  }

  tmp.test("unparseable command line delegates with a note on stderr") { dir =>
    val cfg = write(dir, "rules", rules)
    val out = run(payload("echo 'unterminated"), "--config", cfg.toString)
    assertEquals(out.stdout, "")
    assert(out.stderr.startsWith("meljudge: cannot parse command line:"), out.stderr)
  }

  tmp.test("output is one JSON line") { dir =>
    val cfg = write(dir, "rules", rules)
    val out = run(payload("rm -rf x"), "--config", cfg.toString)
    assert(out.stdout.endsWith("\n"))
    assertEquals(out.stdout.trim.count(_ == '\n'), 0)
    assertEquals(ujson.read(out.stdout)("hookSpecificOutput")("hookEventName").str, "PreToolUse")
  }

  test("non-Bash tools and empty commands are ignored"):
    assertEquals(run(payload("rm -rf /", tool = "Read"), "--config", "/nonexistent"), Main.Output())
    assertEquals(run(payload(""), "--config", "/nonexistent"), Main.Output())
    assertEquals(run("""{"tool_name":"Bash"}""", "--config", "/nonexistent"), Main.Output())

  test("undecodable input asks"):
    val (d, r) = decisionOf(run("not json", "--config", "/nonexistent"))
    assertEquals(d, "ask")
    assert(r.startsWith("meljudge: failed to decode hook input"), r)

  test("missing rule file asks"):
    assertEquals(
      decisionOf(run(payload("ls"), "--config", "/nonexistent/rules")),
      ("ask", "meljudge: rule file not found: /nonexistent/rules"),
    )

  tmp.test("broken rule file asks with the line") { dir =>
    val cfg = write(dir, "rules", "pass prefix [ls]\nblock all prefix [rm]\n")
    assertEquals(
      decisionOf(run(payload("ls"), "--config", cfg.toString)),
      ("ask", s"meljudge: rule file error ($cfg): line 2: all cannot be combined with other conditions"),
    )
  }

  test("invalid arguments ask"):
    assertEquals(decisionOf(run(payload("ls"), "--bogus")), ("ask", "meljudge: invalid arguments: Unexpected option: --bogus"))
    assertEquals(decisionOf(run(payload("ls"), "--config")), ("ask", "meljudge: invalid arguments: Missing value for option: --config"))

  test("--version"):
    assertEquals(run("", "--version"), Main.Output(stdout = s"meljudge version ${Main.version}\n"))

  test("rule file path resolution"):
    assertEquals(Main.resolveConfigPath(Some("/x/r"), Map("HOME" -> "/h")).map(_.toString), Right("/x/r"))
    assertEquals(
      Main.resolveConfigPath(None, Map("XDG_CONFIG_HOME" -> "/xdg", "HOME" -> "/h")).map(_.toString),
      Right("/xdg/meljudge/meljudge.conf"),
    )
    assertEquals(
      Main.resolveConfigPath(None, Map("XDG_CONFIG_HOME" -> "", "HOME" -> "/h")).map(_.toString),
      Right("/h/.config/meljudge/meljudge.conf"),
    )
    assert(Main.resolveConfigPath(None, Map.empty).isLeft)

  tmp.test("log rules append one line per adopted rule") { dir =>
    val log = dir.resolve("sub").resolve("audit.log")
    val cfg = write(dir, "rules", s"""set log "$log"
      |block log prefix [rm]
      |pass  log prefix [gh]
      |pass      prefix [gh pr]
      |""".stripMargin)
    val out = run(payload("gh auth status; gh pr list; rm x"), "--config", cfg.toString)
    assertEquals(decisionOf(out)._1, "deny")
    val lines = new String(Files.readAllBytes(log), StandardCharsets.UTF_8)
    assertEquals(
      lines,
      "2026-09-04T03:12:45Z deny rule=3 pass gh auth status\n"
        + "2026-09-04T03:12:45Z deny rule=2 block rm x\n",
    )
    // A second invocation appends.
    run(payload("rm y"), "--config", cfg.toString)
    assertEquals(new String(Files.readAllBytes(log), StandardCharsets.UTF_8).linesIterator.size, 3)
    Files.delete(log)
    Files.delete(log.getParent)
  }

  tmp.test("log goes to stderr when set log is absent") { dir =>
    val cfg = write(dir, "rules", "block log prefix [rm]\npass log prefix [ls]\n")
    val out = run(payload("ls; rm x"), "--config", cfg.toString)
    assertEquals(decisionOf(out)._1, "deny")
    assertEquals(
      out.stderr,
      "2026-09-04T03:12:45Z deny rule=2 pass ls\n"
        + "2026-09-04T03:12:45Z deny rule=1 block rm x\n",
    )
    val quiet = run(payload("pwd"), "--config", cfg.toString)
    assertEquals(quiet, Main.Output())
  }

  tmp.test("unwritable log asks") { dir =>
    val blocker = write(dir, "file", "")
    val cfg = write(dir, "rules", s"""set log "$blocker/audit.log"
      |block log prefix [rm]
      |""".stripMargin)
    val (d, r) = decisionOf(run(payload("rm x"), "--config", cfg.toString))
    assertEquals(d, "ask")
    assert(r.startsWith("meljudge: cannot write log:"), r)
  }

  test("utcTimestamp"):
    assertEquals(Main.utcTimestamp(0L), "1970-01-01T00:00:00Z")
    assertEquals(Main.utcTimestamp(1788491565000L), "2026-09-04T03:12:45Z")
    assertEquals(Main.utcTimestamp(951868799000L), "2000-02-29T23:59:59Z")

class MainCliSuite extends munit.FunSuite:
  private val tmp = FunFixture[Path](
    setup = _ => Files.createTempDirectory("meljudge-cli"),
    teardown = dir =>
      Files.list(dir).forEach(p => Files.delete(p))
      Files.delete(dir),
  )

  private def write(dir: Path, name: String, content: String): Path =
    val p = dir.resolve(name)
    Files.write(p, content.getBytes(StandardCharsets.UTF_8))
    p

  private def run(stdin: String, args: String*): Main.Output =
    Main.run(() => stdin, args, env = Map.empty, clock = () => 0L)

  private val rules = """ask       prefix [git]
    |pass      prefix [git status, git diff]
    |pass log  prefix [gh pr create]
    |block     prefix [rm -rf]
    |""".stripMargin

  tmp.test("plain prints the decision and a per-command table") { dir =>
    val cfg = write(dir, "rules", rules)
    val out = run("", "--config", cfg.toString, "--plain", "git status && rm -rf /tmp/x; ls")
    assertEquals(
      out.stdout,
      "deny: \"rm -rf /tmp/x\" matches block rule at line 4\n"
        + "  pass       line 2   git status\n"
        + "  block      line 4   rm -rf /tmp/x\n"
        + "  -          -        ls\n",
    )
    assertEquals(out.stderr, "")
    assertEquals(out.exitCode, 2)
  }

  tmp.test("plain exit codes follow the decision") { dir =>
    val cfg = write(dir, "rules", rules)
    assertEquals(run("", "--config", cfg.toString, "--plain", "git status").exitCode, 0)
    assertEquals(run("", "--config", cfg.toString, "--plain", "git commit").exitCode, 1)
    assertEquals(run("", "--config", cfg.toString, "--plain", "rm -rf x").exitCode, 2)
    val d = run("", "--config", cfg.toString, "--plain", "ls")
    assertEquals(d.exitCode, 3)
    assertEquals(d.stdout, "delegate: no decision; the normal permission flow applies\n  -          -        ls\n")
  }

  tmp.test("plain joins arguments and falls back to stdin") { dir =>
    val cfg = write(dir, "rules", rules)
    val fromArgs = run("", "--config", cfg.toString, "--plain", "git", "status")
    val fromStdin = run("git status\n", "--plain", "--config", cfg.toString)
    assertEquals(fromArgs, fromStdin)
    assertEquals(fromArgs.exitCode, 0)
  }

  tmp.test("plain shows log rules but writes no log") { dir =>
    val log = dir.resolve("audit.log")
    val cfg = write(dir, "rules", s"set log \"$log\"\n$rules")
    val out = run("", "--config", cfg.toString, "--plain", "gh pr create")
    assertEquals(out.stdout.linesIterator.toVector(1), "  pass log   line 4   gh pr create")
    assertEquals(out.stderr, "")
    assert(!Files.exists(log))
  }

  tmp.test("plain reports errors on stderr with sysexits codes") { dir =>
    val broken = write(dir, "broken", "block prefix rm\n")
    val e = run("", "--config", broken.toString, "--plain", "ls")
    assertEquals(e.exitCode, 65)
    assert(e.stderr.startsWith(s"meljudge: rule file error ($broken): line 1: syntax error"), e.stderr)
    assertEquals(e.stdout, "")
    val cfg = write(dir, "rules", rules)
    val u = run("", "--config", cfg.toString, "--plain", "echo 'x")
    assertEquals(u.exitCode, 65)
    assert(u.stderr.startsWith("meljudge: cannot parse command line:"), u.stderr)
    val missing = run("", "--config", "/nonexistent", "--plain", "ls")
    assertEquals((missing.exitCode, missing.stderr), (65, "meljudge: rule file not found: /nonexistent\n"))
  }

  tmp.test("plain uses the configured shfmt path") { dir =>
    val cfg = write(dir, "rules", "set shfmt /nonexistent/shfmt\nblock all\n")
    val out = run("", "--config", cfg.toString, "--plain", "ls")
    assertEquals(out.exitCode, 69)
    assert(out.stderr.startsWith("meljudge: parser failure:"), out.stderr)
  }

  tmp.test("check reports ok with the rule count") { dir =>
    val cfg = write(dir, "rules", rules)
    assertEquals(run("", "--check", "--config", cfg.toString), Main.Output(stdout = s"ok: $cfg (4 rules)\n"))
  }

  tmp.test("check reports errors with exit 65") { dir =>
    val cfg = write(dir, "rules", "set log a\nset log b\n")
    assertEquals(
      run("", "--check", "--config", cfg.toString),
      Main.Output(stderr = s"meljudge: rule file error ($cfg): line 2: setting log is set twice\n", exitCode = 65),
    )
    assertEquals(run("", "--check", "--config", "/nonexistent").exitCode, 65)
  }

  test("argument errors in terminal modes exit 64"):
    assertEquals(
      run("", "--plain", "--check"),
      Main.Output(stderr = "meljudge: invalid arguments: --plain and --check cannot be combined\nTry 'meljudge --help'.\n", exitCode = 64),
    )
    assertEquals(run("", "--check", "--plain").exitCode, 64)
    assertEquals(run("", "--check", "stray").exitCode, 64)
    assertEquals(run("", "--plain", "--bogus").exitCode, 64)

  test("single-dash arguments are rejected unless after --"):
    assertEquals(run("", "--plain", "-n").exitCode, 64)
    assert(run("", "--plain", "-n").stderr.contains("Unexpected option: -n"))
    assert(run("", "--plain", "-n").stderr.endsWith("Try 'meljudge --help'.\n"))
    val hook = run("""{"tool_name":"Bash","tool_input":{"command":"ls"}}""", "-v")
    assertEquals(hook.exitCode, 0)
    assert(hook.stdout.contains("Unexpected option: -v"), hook.stdout)

  tmp.test("-- passes a dash-leading command line to plain") { dir =>
    val cfg = write(dir, "rules", "block prefix [-n]\n")
    val out = run("", "--config", cfg.toString, "--plain", "--", "-n", "x")
    assertEquals(out.exitCode, 2)
    assertEquals(out.stdout.linesIterator.next(), "deny: \"-n x\" matches block rule at line 1")
  }

  test("--help prints usage and must be the only argument"):
    assertEquals(run("", "--help"), Main.Output(stdout = Main.usage))
    assert(Main.usage.startsWith("Usage: meljudge"), Main.usage)
    assert(Main.usage.contains("--plain"), Main.usage)
    assertEquals(run("", "--check", "--help").exitCode, 64)
    assertEquals(run("", "--help", "--bogus").exitCode, 64)

  tmp.test("stdin is not read unless the mode needs it") { dir =>
    val cfg = write(dir, "rules", rules)
    val untouched: () => String = () => fail("stdin must not be read")
    def runNoStdin(args: String*) = Main.run(untouched, args, env = Map.empty, clock = () => 0L)
    assertEquals(runNoStdin("--help").exitCode, 0)
    assertEquals(runNoStdin("--version").exitCode, 0)
    assertEquals(runNoStdin("--bogus").exitCode, 0) // hook-mode argument error asks without reading
    assertEquals(runNoStdin("--plain", "-n").exitCode, 64)
    assertEquals(runNoStdin("--check", "--config", cfg.toString).exitCode, 0)
    assertEquals(runNoStdin("--config", cfg.toString, "--plain", "git", "status").exitCode, 0)
  }

  test("stray positional arguments in hook mode ask"):
    val out = run("""{"tool_name":"Bash","tool_input":{"command":"ls"}}""", "stray")
    assertEquals(out.exitCode, 0)
    assert(out.stdout.contains("\"ask\""), out.stdout)
    assert(out.stdout.contains("unexpected argument: stray"), out.stdout)
