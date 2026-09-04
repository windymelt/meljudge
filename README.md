# meljudge

meljudge is a PreToolUse hook for [Claude Code](https://code.claude.com/) that
inspects Bash commands with a real shell parser before they run, and decides
with rules written in a small language modelled on OpenBSD `pf.conf`.

Claude Code's built-in permission rules already split compound commands
(`&&`, `;`, pipes) and require each subcommand to match. meljudge digs deeper:
it parses the command line into a full AST with [shfmt](https://github.com/mvdan/sh),
so it also sees commands hidden in command substitutions, loop headers, and
redirect targets; it matches rules on argv words instead of by glob; and it
lets you say, per command, whether Claude may run it silently, must ask, or
must be stopped.

meljudge is inspired by [masawada/metsuke](https://github.com/masawada/metsuke).

## How it decides

A rule is an action followed by conditions. The example below is a complete
rule file:

```
# ~/.config/meljudge/meljudge.conf
ask       prefix [git, gh]
pass      prefix [git status, git diff, git log, gh pr view, gh pr list]
pass log  prefix [gh pr create]
block     prefix [gh auth token, rm -rf]
block     prefix [git push --force] reason "Force pushes are forbidden. Push to a new branch and open a PR."
```

meljudge extracts every simple command from the Bash call, including those
inside `$(...)` and `<(...)`, and evaluates the rules against each of them.
Rules are read top to bottom and **the last matching rule wins**, so broad
rules go first and the most specific ones last.

- `pass` allows the command.
- `block` denies it.
- `ask` shows a permission dialog, even in auto mode.
- `delegate` leaves the command to the normal permission flow.

The decision for the whole call follows from the per-command verdicts: a
`block` anywhere denies the call; otherwise an `ask` anywhere asks; otherwise
the call is allowed only if *every* command was passed. Anything else
(unmatched commands, `delegate`, or an empty call) is left to Claude Code's
own permission rules and classifier.

With the file above, `git status --short` runs without a prompt, `git commit`
asks, `gh auth token` is blocked even when buried in a pipeline or a `$(...)`,
`gh pr create` runs and is logged, and `ls` is left to Claude Code.

Some details of the matching:

- `prefix [git push]` matches an argv that *starts with* `git` `push`.
  Words after the pattern are not inspected, so `git push origin main`
  matches too. `sudo git push` does not.
- `has [--force]` matches an argv that contains `--force` *anywhere*, and
  `has [rm -rf]` needs the two words next to each other. Conditions on one
  rule are ANDed: `block prefix [git push] has [--force]` denies only
  forced pushes.
- Words whose value is not known until runtime (`$VAR`, `$(...)`, globs,
  tilde expansion, escapes) never satisfy a pattern word.
- `reason "..."` on a `block` or `ask` rule is put in front of the decision
  reason. Claude sees a deny reason, so it can tell the model what to do
  instead; the user sees an ask reason.
- `block` and `ask` compare the basename of argv[0], so `/usr/bin/git push`
  is still caught. `pass` and `delegate` require a bare command name, so a
  local `./git` cannot impersonate an allowed command.
- If the rule file is missing or broken, every Bash call degrades to **ask**
  with the error in the reason. A misconfigured meljudge gets loud, not
  silently disabled. Only an unparseable command line is delegated.

The full syntax and semantics, including `set` options and the log format,
are specified in [docs/rules.md](docs/rules.md).

## Install

Releases are not published yet. Build the native executable from source
(requires sbt 2.x and clang to build, and `shfmt` on `PATH` at run time):

```console
$ git clone https://github.com/windymelt/meljudge.git
$ cd meljudge
$ sbt nativeLink
$ install -m 755 target/out/native0.5/scala-3.9.0/meljudge/meljudge ~/.local/bin/meljudge
$ meljudge --help
```

## Configure

Write your rules to `~/.config/meljudge/meljudge.conf`. The file is looked up
in this order:

1. `--config <path>`
2. `$XDG_CONFIG_HOME/meljudge/meljudge.conf`
3. `$HOME/.config/meljudge/meljudge.conf`

If the default file does not exist when meljudge first runs, it is created
with a commented template whose only rule is `delegate all`, so nothing
changes until you add rules. The first Bash call after creation asks once,
with the file's path in the reason, so you know where to edit.

Check the file and try commands against it from a terminal before wiring the
hook up. `--check` reports syntax and validation errors with line numbers, and
`--plain` prints the decision together with the rule adopted for each command
(exit code 0 for allow, 1 for ask, 2 for deny, 3 for delegate):

```console
$ meljudge --check
ok: /home/me/.config/meljudge/meljudge.conf (4 rules)
$ meljudge --plain 'git status && echo $(gh auth token)'
deny: "gh auth token" matches block rule at line 5
  pass       line 3   git status
  -          -        echo ?
  block      line 5   gh auth token
```

Rules with the `log` modifier write one line per matched command. By default
the lines go to standard error; set a file to keep an audit trail:

```
set log "/home/me/.local/state/meljudge/log"
```

## Register the hook

Add a PreToolUse hook to `~/.claude/settings.json`:

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Bash",
        "hooks": [
          {
            "type": "command",
            "command": "/home/me/.local/bin/meljudge"
          }
        ]
      }
    ]
  }
}
```

### Protect the configuration from the agent

meljudge is only meaningful if Claude cannot rewrite its rules or its binary.
Deny Claude's file-editing tools access to both, and deny writes from the
sandboxed shell as well, since they are different write paths:

```json
{
  "permissions": {
    "deny": [
      "Edit(~/.config/meljudge/**)",
      "Edit(~/.local/bin/meljudge)"
    ]
  },
  "sandbox": {
    "enabled": true,
    "allowUnsandboxedCommands": false,
    "failIfUnavailable": true,
    "filesystem": {
      "denyWrite": [
        "~/.local/bin/meljudge",
        "~/.config/meljudge"
      ]
    }
  }
}
```

Merge this with the hook registration above into one `settings.json`.

## Limitations

`block` rules are **best-effort, not a security boundary**. Static analysis
cannot fully resolve what Bash will execute: brace expansion (`{git,push}`),
escaped command names (`g\it push`), and arithmetic re-evaluation of variable
values can hide a command from the parser. Keep critical denies in Claude
Code's own `permissions.deny` as an additional layer.

`pass` emits a real `allow`, so keep pass patterns as long as you can. A
pattern grants every invocation that starts with it, whatever the trailing
arguments are.

## Development

The rule language is specified in [docs/rules.md](docs/rules.md); the
specification is authoritative, and code and tests follow it. Command lines
are parsed by `shfmt --tojson`, whose JSON output encodes redirect operators
as numeric token values that are not a documented stable interface; this
project is verified against shfmt v3.12.0.

```console
$ sbt test          # unit and golden tests (needs shfmt on PATH)
$ sbt nativeLink    # build the executable
```

The test runner's output is not forwarded by the sbt 2 thin client; use
`sbt --server -batch test` to see failure details.

## License

BSD 3-Clause. See [LICENSE](LICENSE).
