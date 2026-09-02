# meljudge

meljudge is a PreToolUse hook for [Claude Code](https://code.claude.com/) that inspects Bash commands before they run, written in Scala 3 and compiled with Scala Native.

Inspired by [masawada/metsuke](https://github.com/masawada/metsuke).

## Status

Work in progress. The rule definition format is under design.

## Requirements

- [shfmt](https://github.com/mvdan/sh) on `PATH`. Command lines are parsed
  by `shfmt --tojson`, whose JSON output encodes redirect operators as
  numeric token values that are not a documented stable interface; this
  project is verified against shfmt v3.12.0.

## Development

```console
$ sbt "test"
```

Building requires sbt 2.x and clang (for Scala Native).

## License

MIT
