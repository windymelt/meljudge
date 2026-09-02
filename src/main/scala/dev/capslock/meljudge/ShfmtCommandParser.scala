package dev.capslock.meljudge

import java.io.{IOException, InputStream}
import java.nio.charset.StandardCharsets

/** A [[CommandParser]] backed by `shfmt --tojson`, which serializes the
  * mvdan/sh AST as JSON.
  */
final class ShfmtCommandParser(shfmtPath: String = "shfmt") extends CommandParser:
  import ShfmtCommandParser.*

  def parse(source: String): Either[ParseError, Vector[SimpleCommand]] =
    runShfmt(source).flatMap(decode)

  private def runShfmt(source: String): Either[ParseError, String] =
    try
      val proc =
        new ProcessBuilder(shfmtPath, "--tojson", "-ln", "bash").start()
      val stdin = proc.getOutputStream
      stdin.write(source.getBytes(StandardCharsets.UTF_8))
      stdin.close()
      val out = readAll(proc.getInputStream)
      val err = readAll(proc.getErrorStream)
      val code = proc.waitFor()
      if code == 0 then Right(out)
      else Left(ParseError.Unparseable(err.trim))
    catch
      case e: IOException =>
        Left(ParseError.ParserFailure(s"failed to run $shfmtPath: ${e.getMessage}"))
      case e: InterruptedException =>
        Left(ParseError.ParserFailure(s"interrupted while running $shfmtPath"))

  private def decode(json: String): Either[ParseError, Vector[SimpleCommand]] =
    try Right(visitStmts(field(ujson.read(json), "Stmts"), redirected = false))
    catch
      case e: Exception =>
        Left(ParseError.ParserFailure(s"unexpected shfmt output: ${e.getMessage}"))

  private def visitStmts(stmts: ujson.Value, redirected: Boolean): Vector[SimpleCommand] =
    elems(stmts).flatMap(visitStmt(_, redirected))

  /** Processes one statement. `redirected` reports that an enclosing compound
    * command carries an output redirect; it propagates conservatively to
    * every inner command.
    */
  private def visitStmt(stmt: ujson.Value, redirected: Boolean): Vector[SimpleCommand] =
    val redirs = elems(field(stmt, "Redirs"))
    val red = redirected || redirs.exists(isOutputRedirect)
    // Substitutions buried in redirect targets and heredoc bodies still run;
    // extract them so rules can see them.
    visitCmd(field(stmt, "Cmd"), red) ++ redirs.flatMap(collectSubsts)

  private def visitCmd(cmd: ujson.Value, redirected: Boolean): Vector[SimpleCommand] =
    nodeType(cmd) match
      case "CallExpr" =>
        val words = elems(field(cmd, "Args")).map(evalWord)
        val self = SimpleCommand(
          words = words,
          outputRedirect = redirected,
          envPrefix = elems(field(cmd, "Assigns")).nonEmpty,
        )
        // Substitutions in arguments, assignment values, and array
        // subscripts execute; extract them as sibling commands.
        self +: (collectSubsts(field(cmd, "Args")) ++ collectSubsts(field(cmd, "Assigns")))
      case "BinaryCmd" =>
        visitStmt(field(cmd, "X"), redirected) ++ visitStmt(field(cmd, "Y"), redirected)
      case "Block" | "Subshell" =>
        visitStmts(field(cmd, "Stmts"), redirected)
      case "IfClause" =>
        visitIfClause(cmd, redirected)
      case "WhileClause" =>
        visitStmts(field(cmd, "Cond"), redirected)
          ++ visitStmts(field(cmd, "Do"), redirected)
      case "ForClause" =>
        // Both word iteration items and C-style loop headers execute their
        // substitutions.
        collectSubsts(field(cmd, "Loop"))
          ++ visitStmts(field(cmd, "Do"), redirected)
      case "CaseClause" =>
        // Bash expands substitutions in the subject word and case patterns.
        collectSubsts(field(cmd, "Word"))
          ++ elems(field(cmd, "Items")).flatMap { item =>
            collectSubsts(field(item, "Patterns"))
              ++ visitStmts(field(item, "Stmts"), redirected)
          }
      case "FuncDecl" =>
        visitStmt(field(cmd, "Body"), redirected = false)
      case "TimeClause" | "CoprocClause" =>
        visitStmt(field(cmd, "Stmt"), redirected)
      case "" =>
        Vector.empty
      case _ =>
        // DeclClause, LetClause, TestClause, etc.: extract only the inner
        // command substitutions so rules can see them.
        collectSubsts(cmd)

  /** Unlike other compound nodes, the `Else` chain (elif/else parts) is a
    * typed struct field and is serialized without a `Type` tag, so it cannot
    * go through [[visitCmd]]'s dispatch.
    */
  private def visitIfClause(clause: ujson.Value, redirected: Boolean): Vector[SimpleCommand] =
    if clause.objOpt.isEmpty then Vector.empty
    else
      visitStmts(field(clause, "Cond"), redirected)
        ++ visitStmts(field(clause, "Then"), redirected)
        ++ visitIfClause(field(clause, "Else"), redirected)

  /** Extracts commands buried in command/process substitutions anywhere under
    * `node`. Substitutions run in a subshell, so the enclosing redirect
    * context does not carry over.
    */
  private def collectSubsts(node: ujson.Value): Vector[SimpleCommand] =
    node match
      case obj: ujson.Obj =>
        nodeType(obj) match
          case "CmdSubst" | "ProcSubst" =>
            visitStmts(field(obj, "Stmts"), redirected = false)
          case _ =>
            obj.value.values.toVector.flatMap(collectSubsts)
      case arr: ujson.Arr =>
        arr.value.toVector.flatMap(collectSubsts)
      case _ =>
        Vector.empty

  /** Returns the literal value of a word, or [[Word.uncertain]] if the word
    * contains any expansion.
    */
  private def evalWord(word: ujson.Value): Word =
    val parts = elems(field(word, "Parts")).map(litPart)
    if parts.forall(_.isDefined) then Word.lit(parts.flatten.mkString)
    else Word.uncertain

  private def litPart(part: ujson.Value): Option[String] =
    nodeType(part) match
      case "Lit" =>
        val value = str(field(part, "Value"))
        // Characters that may trigger globbing, escaping, or tilde expansion
        // make the value uncertain.
        Option.when(!value.exists(uncertainChars.contains))(value)
      case "SglQuoted" =>
        // $'...' may contain escape sequences.
        Option.when(!bool(field(part, "Dollar")))(str(field(part, "Value")))
      case "DblQuoted" =>
        val inner = elems(field(part, "Parts")).map { p =>
          val value = str(field(p, "Value"))
          Option.when(nodeType(p) == "Lit" && !value.contains('\\'))(value)
        }
        Option.when(inner.forall(_.isDefined))(inner.flatten.mkString)
      case _ =>
        None

  /** Reports whether the redirect writes to a file. Fd duplication (e.g.
    * 2>&1) and input redirects are considered harmless.
    */
  private def isOutputRedirect(redir: ujson.Value): Boolean =
    field(redir, "Op").numOpt.map(_.toInt) match
      case Some(op) if fileWriteOps.contains(op) => true
      case Some(`opDplOut`) =>
        // >&N and >&- only manipulate fds; anything else behaves like >&file
        // and writes.
        val target = evalWord(field(redir, "Word"))
        !target.literal || !isFdRef(target.text)
      case _ => false

  private def isFdRef(s: String): Boolean =
    s == "-" || {
      val digits = s.stripSuffix("-")
      digits.nonEmpty && digits.forall(_.isDigit)
    }

private object ShfmtCommandParser:
  private val uncertainChars = Set('*', '?', '[', '\\', '~')

  // Numeric values of mvdan/sh v3 syntax token constants as serialized by
  // shfmt --tojson. They are not a documented stable interface; pin the shfmt
  // version and keep the redirect tests green when bumping it.
  // Verified against shfmt v3.12.0.
  private val opRdrOut = 54 // >
  private val opAppOut = 55 // >>
  private val opRdrInOut = 57 // <>
  private val opDplOut = 59 // >&
  private val opClbOut = 60 // >|
  private val opRdrAll = 64 // &>
  private val opAppAll = 65 // &>>
  private val fileWriteOps =
    Set(opRdrOut, opAppOut, opRdrInOut, opClbOut, opRdrAll, opAppAll)

  private def nodeType(v: ujson.Value): String =
    v.objOpt.flatMap(_.get("Type")).flatMap(_.strOpt).getOrElse("")

  private def field(v: ujson.Value, key: String): ujson.Value =
    v.objOpt.flatMap(_.get(key)).getOrElse(ujson.Null)

  private def elems(v: ujson.Value): Vector[ujson.Value] =
    v.arrOpt.map(_.toVector).getOrElse(Vector.empty)

  private def str(v: ujson.Value): String = v.strOpt.getOrElse("")

  private def bool(v: ujson.Value): Boolean = v.boolOpt.getOrElse(false)

  private def readAll(in: InputStream): String =
    val buf = new Array[Byte](8192)
    val out = new java.io.ByteArrayOutputStream()
    var n = in.read(buf)
    while n != -1 do
      out.write(buf, 0, n)
      n = in.read(buf)
    out.toString(StandardCharsets.UTF_8)
