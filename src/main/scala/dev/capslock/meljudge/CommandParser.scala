package dev.capslock.meljudge

/** Parses a whole shell command line and extracts every simple command that
  * may be executed, including commands buried in command/process
  * substitutions.
  */
trait CommandParser:
  def parse(source: String): Either[ParseError, Vector[SimpleCommand]]
