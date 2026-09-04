package dev.capslock.meljudge

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, NoSuchFileException, Path, Paths, StandardOpenOption}

import cats.syntax.all.*
import com.monovore.decline.{Command, Opts}

/** Entry point. Implements the Claude Code PreToolUse hook protocol by
  * default, plus `--plain` and `--check` for use from a terminal. See
  * docs/rules.md, "Hook protocol" and "Command line".
  */
object Main:
  val version = "0.0.1"

  /** What one invocation writes and how it exits. In hook mode `exitCode` is
    * always 0 and `stdout` is empty when the decision is delegated.
    */
  final case class Output(stdout: String = "", stderr: String = "", exitCode: Int = 0)

  def main(args: Array[String]): Unit =
    val out = run(
      () => Io.readAll(System.in),
      args.toSeq,
      sys.env,
      clock = System.currentTimeMillis,
      notice = s => { System.err.print(s); System.err.flush() },
    )
    print(out.stdout)
    System.err.print(out.stderr)
    System.out.flush()
    System.err.flush()
    sys.exit(out.exitCode)

  /** `stdin` is read only when the mode needs it (hook mode, or `--plain`
    * without CMD), and only after the arguments have been parsed, so that an
    * argument error never blocks on a terminal. `notice` writes to standard
    * error immediately, before a possibly blocking read, unlike
    * [[Output.stderr]] which is written at the end.
    */
  def run(
      stdin: () => String,
      args: Seq[String],
      env: Map[String, String],
      clock: () => Long,
      parserFor: String => CommandParser = ShfmtCommandParser(_),
      notice: String => Unit = _ => (),
  ): Output =
    parseArgs(args) match
      case Left(msg) =>
        // Argument errors cannot know the intended mode for sure; treat the
        // presence of a terminal flag as a terminal invocation.
        if args.exists(a => a == "--plain" || a == "--check" || a == "--help") then
          cliError(64, s"invalid arguments: $msg\nTry 'meljudge --help'.")
        else ask(s"invalid arguments: $msg")
      case Right(None)                          => Output(stdout = usage)
      case Right(Some(a)) if a.version          => Output(stdout = s"meljudge version $version\n")
      case Right(Some(Args(cfg, _, Mode.Hook)))       => runHook(stdin(), cfg, env, clock, parserFor)
      case Right(Some(Args(cfg, _, Mode.Plain(Some(cmd))))) => runPlain(cmd, cfg, env, parserFor)
      case Right(Some(Args(cfg, _, Mode.Plain(None)))) =>
        notice("meljudge: CMD is empty; reading the command line from standard input\n")
        runPlain(stdin().stripSuffix("\n"), cfg, env, parserFor)
      case Right(Some(Args(cfg, _, Mode.Check)))      => runCheck(cfg, env)

  // ---------------------------------------------------------------------
  // Modes

  private def runHook(
      stdin: String,
      configFlag: Option[String],
      env: Map[String, String],
      clock: () => Long,
      parserFor: String => CommandParser,
  ): Output =
    decodeInput(stdin) match
      case Left(msg)   => ask(s"failed to decode hook input: $msg")
      case Right(None) => Output()
      case Right(Some(commandLine)) =>
        evaluate(commandLine, configFlag, env, parserFor) match
          case Left(Failure.RuleFile(msg))    => ask(msg)
          case Left(Failure.Backend(msg))     => ask(s"parser failure: $msg")
          case Left(Failure.Unparseable(msg)) => Output(stderr = s"meljudge: cannot parse command line: $msg\n")
          case Right((Loaded(path, _, true), _)) =>
            ask(s"no rule file was found, so a default one that delegates everything was created at $path; edit it to add rules")
          case Right((Loaded(_, rules, false), verdict)) =>
            writeLogs(rules.settings.log, verdict, clock) match
              case Left(err) => ask(s"cannot write log: $err")
              case Right(stderr) =>
                val out = emit(verdict)
                out.copy(stderr = stderr + out.stderr)

  private def runPlain(
      commandLine: String,
      configFlag: Option[String],
      env: Map[String, String],
      parserFor: String => CommandParser,
  ): Output =
    evaluate(commandLine, configFlag, env, parserFor) match
      case Left(Failure.RuleFile(msg))    => cliError(65, msg)
      case Left(Failure.Unparseable(msg)) => cliError(65, s"cannot parse command line: $msg")
      case Left(Failure.Backend(msg))     => cliError(69, s"parser failure: $msg")
      case Right((loaded, verdict)) =>
        val (name, reason) = describe(verdict)
        val table = verdict.commands.map { cv =>
          val action = cv.rule.map(r => r.action.toString.toLowerCase + (if r.log then " log" else "")).getOrElse("-")
          val line = cv.rule.map(r => s"line ${r.line}").getOrElse("-")
          f"  $action%-10s $line%-8s ${cmdText(cv.command)}\n"
        }.mkString
        val code = verdict.decision match
          case Decision.Allow    => 0
          case Decision.Ask      => 1
          case Decision.Deny     => 2
          case Decision.Delegate => 3
        Output(stdout = s"$name: $reason\n$table", stderr = createdNotice(loaded), exitCode = code)

  private def runCheck(configFlag: Option[String], env: Map[String, String]): Output =
    load(configFlag, env) match
      case Left(msg) => cliError(65, msg)
      case Right(loaded) =>
        Output(stdout = s"ok: ${loaded.path} (${loaded.rules.rules.size} rules)\n", stderr = createdNotice(loaded))

  private def createdNotice(loaded: Loaded): String =
    if loaded.created then s"meljudge: no rule file was found; created a default one at ${loaded.path}\n" else ""

  private def cliError(code: Int, msg: String): Output =
    Output(stderr = s"meljudge: $msg\n", exitCode = code)

  // ---------------------------------------------------------------------
  // Evaluation shared by hook and plain modes

  private enum Failure:
    case RuleFile(message: String)
    case Unparseable(message: String)
    case Backend(message: String)

  /** Result of loading the rule file; `created` reports that the default file
    * did not exist and was generated.
    */
  private final case class Loaded(path: Path, rules: RuleSet, created: Boolean)

  private def evaluate(
      commandLine: String,
      configFlag: Option[String],
      env: Map[String, String],
      parserFor: String => CommandParser,
  ): Either[Failure, (Loaded, Verdict)] =
    for
      loaded <- load(configFlag, env).left.map(Failure.RuleFile(_))
      rules = loaded.rules
      cmds <- parserFor(rules.settings.shfmt.getOrElse("shfmt")).parse(commandLine).left.map {
        case ParseError.Unparseable(msg)   => Failure.Unparseable(msg)
        case ParseError.ParserFailure(msg) => Failure.Backend(msg)
      }
    yield (loaded, Judge.judge(rules, cmds))

  // ---------------------------------------------------------------------
  // Arguments and input

  private enum Mode:
    case Hook
    case Plain(command: Option[String])
    case Check

  private final case class Args(config: Option[String], version: Boolean, mode: Mode)

  private val command: Command[Args] =
    val config = Opts
      .option[String]("config", metavar = "PATH", help = "Rule file. Default: $XDG_CONFIG_HOME/meljudge/meljudge.conf, else ~/.config/meljudge/meljudge.conf.")
      .orNone
    val version = Opts.flag("version", help = "Print the version and exit.").orFalse
    val plain = Opts
      .flag("plain", help = "Judge CMD (or standard input when CMD is omitted or -) and print the decision with the rule adopted for each command. Exit 0 allow, 1 ask, 2 deny, 3 delegate.")
      .orFalse
    val check = Opts.flag("check", help = "Load the rule file and report errors. Exit 0 ok, 65 error.").orFalse
    val cmd = Opts.arguments[String]("CMD").orNone
    val args = (config, version, plain, check, cmd).tupled.mapValidated {
      case (_, _, true, true, _) => "--plain and --check cannot be combined".invalidNel
      case (_, _, false, _, Some(rest)) => s"unexpected argument: ${rest.head}".invalidNel
      case (c, v, true, false, rest)    => Args(c, v, Mode.Plain(rest.map(_.toList.mkString(" ")).filter(_ != "-"))).validNel
      case (c, v, false, true, _)       => Args(c, v, Mode.Check).validNel
      case (c, v, false, false, _)      => Args(c, v, Mode.Hook).validNel
    }
    Command("meljudge", "PreToolUse hook for Claude Code. Without --plain or --check, reads a hook payload on standard input. See docs/rules.md for the rule language.")(args)

  /** Parses the command line. Returns `Right(None)` when `--help` was given. */
  private def parseArgs(args: Seq[String]): Either[String, Option[Args]] =
    command.parse(args) match
      case Right(a)                        => Right(Some(a))
      case Left(help) if help.errors.isEmpty => Right(None)
      case Left(help)                      => Left(help.errors.mkString("; "))

  private[meljudge] def usage: String = command.showHelp + "\n"

  /** Returns the Bash command line, or `None` when the hook input is not a
    * Bash invocation and must be delegated.
    */
  private def decodeInput(body: String): Either[String, Option[String]] =
    try
      val json = ujson.read(body)
      val toolName = json.obj.get("tool_name").flatMap(_.strOpt)
      val command = json.obj.get("tool_input").flatMap(_.objOpt).flatMap(_.get("command")).flatMap(_.strOpt)
      Right(
        if toolName.contains("Bash") then command.filter(_.nonEmpty)
        else None,
      )
    catch case e: Exception => Left(e.getMessage)

  // ---------------------------------------------------------------------
  // Rule file

  private[meljudge] def resolveConfigPath(flag: Option[String], env: Map[String, String]): Either[String, Path] =
    flag match
      case Some(p) => Right(Paths.get(p))
      case None =>
        env.get("XDG_CONFIG_HOME").filter(_.nonEmpty) match
          case Some(xdg) => Right(Paths.get(xdg, "meljudge", "meljudge.conf"))
          case None =>
            env.get("HOME").filter(_.nonEmpty) match
              case Some(home) => Right(Paths.get(home, ".config", "meljudge", "meljudge.conf"))
              case None       => Left("cannot resolve rule file path: neither XDG_CONFIG_HOME nor HOME is set")

  /** Resolves, reads, and parses the rule file. The default file is created
    * from [[template]] when it does not exist; a file named by `--config` is
    * not.
    */
  private def load(configFlag: Option[String], env: Map[String, String]): Either[String, Loaded] =
    for
      path <- resolveConfigPath(configFlag, env)
      created <- if configFlag.isEmpty && Files.notExists(path) then createDefault(path).map(_ => true) else Right(false)
      source <- readRules(path)
      rules <- RuleParser.parse(source).left.map(err => s"rule file error ($path): $err")
    yield Loaded(path, rules, created)

  private def readRules(path: Path): Either[String, String] =
    try Right(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
    catch
      case _: NoSuchFileException => Left(s"rule file not found: $path")
      case e: Exception           => Left(s"cannot read rule file $path: ${e.getMessage}")

  private def createDefault(path: Path): Either[String, Unit] =
    try
      Option(path.getParent).foreach(Files.createDirectories(_))
      Files.write(path, template.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW)
      Right(())
    catch case e: Exception => Left(s"cannot create rule file $path: ${e.getMessage}")

  private[meljudge] val template: String =
    """# meljudge rule file. Generated because none existed; edit freely.
      |#
      |# A rule is: ACTION [log] CONDITION...
      |#   ACTION    := pass | block | ask | delegate
      |#   CONDITION := all | prefix [CMD, CMD...]
      |# Rules are evaluated per command and the last matching rule wins, so put
      |# broad rules first and specific ones last. Example:
      |#
      |#   ask       prefix [git, gh]
      |#   pass      prefix [git status, git diff, git log]
      |#   block     prefix [gh auth token, rm -rf]
      |#   block     prefix [git push --force] reason "Push to a new branch instead."
      |#
      |# Full reference: https://github.com/windymelt/meljudge/blob/main/docs/rules.md
      |
      |delegate all
      |""".stripMargin

  // ---------------------------------------------------------------------
  // Output

  /** The decision name as used in the hook protocol, and the reason. */
  private def describe(verdict: Verdict): (String, String) =
    verdict.decision match
      case Decision.Delegate => ("delegate", "no decision; the normal permission flow applies")
      case Decision.Deny =>
        val CommandVerdict(c, Some(r)) = decidingCommand(verdict, Action.Block): @unchecked
        ("deny", withReason(r, s"\"${cmdText(c)}\" matches block rule at line ${r.line}"))
      case Decision.Ask =>
        val CommandVerdict(c, Some(r)) = decidingCommand(verdict, Action.Ask): @unchecked
        ("ask", withReason(r, s"\"${cmdText(c)}\" matches ask rule at line ${r.line}"))
      case Decision.Allow =>
        val lines = verdict.commands.flatMap(_.rule).map(_.line).distinct.sorted
        ("allow", s"every command matches a pass rule (lines ${lines.mkString(", ")})")

  private def emit(verdict: Verdict): Output =
    verdict.decision match
      case Decision.Delegate => Output()
      case _ =>
        val (name, reason) = describe(verdict)
        decision(name, reason)

  /** The rule's `reason` text, if any, followed by the mechanical explanation. */
  private def withReason(rule: Rule, mechanical: String): String =
    rule.reason match
      case Some(text) => s"$text ($mechanical)"
      case None       => mechanical

  private def decidingCommand(verdict: Verdict, action: Action): CommandVerdict =
    verdict.commands.find(_.rule.exists(_.action == action)).get

  private def decision(name: String, reason: String): Output =
    val json = ujson.Obj(
      "hookSpecificOutput" -> ujson.Obj(
        "hookEventName" -> "PreToolUse",
        "permissionDecision" -> name,
        "permissionDecisionReason" -> s"meljudge: $reason",
      ),
    )
    Output(stdout = ujson.write(json) + "\n")

  private def ask(reason: String): Output = decision("ask", reason)

  /** Renders argv for reasons and logs; uncertain words are shown as `?`. */
  private[meljudge] def cmdText(cmd: SimpleCommand): String =
    cmd.words.map(w => if w.literal then w.text else "?").mkString(" ")

  // ---------------------------------------------------------------------
  // Logging

  /** Writes one line per log entry to the log file, or returns the lines as
    * text for standard error when no log file is set. Returns an error
    * message when the file cannot be written.
    */
  private def writeLogs(logPath: Option[String], verdict: Verdict, clock: () => Long): Either[String, String] =
    val entries = verdict.logs
    if entries.isEmpty then Right("")
    else
      val stamp = utcTimestamp(clock())
      val dec = verdict.decision.toString.toLowerCase
      val text = entries.map { e =>
        s"$stamp $dec rule=${e.rule.line} ${e.rule.action.toString.toLowerCase} ${cmdText(e.command)}\n"
      }.mkString
      logPath match
        case None => Right(text)
        case Some(p) =>
          try
            val path = Paths.get(p)
            Option(path.getParent).foreach(Files.createDirectories(_))
            Files.write(
              path,
              text.getBytes(StandardCharsets.UTF_8),
              StandardOpenOption.CREATE,
              StandardOpenOption.APPEND,
            )
            Right("")
          catch case e: Exception => Left(s"$p: ${e.getMessage}")

  /** Formats epoch milliseconds as an ISO 8601 UTC timestamp with second
    * precision. Implemented by hand because Scala Native's javalib does not
    * provide java.time.
    */
  private[meljudge] def utcTimestamp(millis: Long): String =
    val secs = Math.floorDiv(millis, 1000L)
    val days = Math.floorDiv(secs, 86400L)
    val sod = Math.floorMod(secs, 86400L)
    // Civil-from-days (Howard Hinnant's algorithm).
    val z = days + 719468
    val era = Math.floorDiv(z, 146097L)
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if mp < 10 then mp + 3 else mp - 9
    val year = if m <= 2 then y + 1 else y
    f"$year%04d-$m%02d-$d%02d" + f"T${sod / 3600}%02d:${sod % 3600 / 60}%02d:${sod % 60}%02dZ"
