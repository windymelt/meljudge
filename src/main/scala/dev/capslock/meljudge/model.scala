package dev.capslock.meljudge

/** A single argv element of a simple command. When `literal` is false, the
  * value is not known until runtime (it contains parameter expansion, command
  * substitution, globs, tilde expansion, escapes, etc.) and `text` is empty.
  */
final case class Word(text: String, literal: Boolean)

object Word:
  val uncertain: Word = Word("", literal = false)
  def lit(text: String): Word = Word(text, literal = true)

/** One simple command extracted from a command line. Commands found inside
  * command/process substitutions are extracted as siblings.
  *
  * @param outputRedirect
  *   the command, or an enclosing compound command, carries a redirect that
  *   writes to a file
  * @param envPrefix
  *   the command carries environment variable assignments
  */
final case class SimpleCommand(
    words: Vector[Word],
    outputRedirect: Boolean = false,
    envPrefix: Boolean = false,
)

enum ParseError:
  /** The command line itself is not valid bash. */
  case Unparseable(message: String)

  /** The parser backend failed; the command line may still be valid. */
  case ParserFailure(message: String)
