# Rule language

This document is the authoritative specification of the meljudge rule
language. Code and tests follow this document; when they disagree, this
document wins and the code is wrong.

The design follows OpenBSD `pf.conf`: a flat list of rules, each an action
followed by keyword-led conditions, evaluated with last-match-wins semantics.

## Example

```
# Global settings
set shfmt "/usr/local/bin/shfmt"
set log "/home/me/.local/state/meljudge/log"

ask       prefix [git]
pass      prefix [git status, git diff, git log]
pass log  prefix [gh]
delegate  prefix [gh auth]
block     prefix [rm -rf]
block     prefix [git push --force] reason "Force pushes are forbidden here. Push to a new branch instead."
```

Because the last matching rule wins, broad rules come first and the most
specific rules come last. Here `block` is placed last so that `ask prefix [git]`
cannot override it for `git push --force`.

## Syntax

The file is a sequence of lines. A line is empty, a comment, a `set`
statement, or a rule. There is one statement per line, except that the
bracketed list of a `prefix` or `has` condition may span multiple lines.

```
file       := (line NEWLINE)* line?
line       := HS? (statement HS?)? comment?
comment    := "#" [^\n]*
statement  := set | rule

set        := "set" HS name HS word
name       := bareword

rule       := action (HS "log")? (HS condition)+ (HS "reason" HS quoted)?
action     := "pass" | "block" | "ask" | "delegate"
condition  := "all" | ("prefix" | "has") HS? list
list       := "[" WS? pattern (WS? "," WS? pattern)* WS? "]"
pattern    := word (HS word)*

word       := bareword | quoted
bareword   := [^ \t\n,\[\]"#]+
quoted     := '"' ( [^"\\\n] | '\"' | '\\' )* '"'

HS         := [ \t]+           (horizontal space)
WS         := ([ \t\n] | comment)+
```

Notes:

- `HS?` denotes optional horizontal space. Keywords, words, and conditions
  are separated by at least one horizontal space.
- A `pattern` is a sequence of words on a single line. Words are separated by
  horizontal space; the pattern ends at `,` or `]`.
- Inside a quoted word, `\"` denotes a double quote and `\\` denotes a
  backslash. Any other backslash sequence is a syntax error. A quoted word
  may be empty only as a `set` value, never inside a pattern.
- A word must be quoted when it contains a space, tab, `,`, `[`, `]`, `"`, or
  `#`. Quoting is otherwise optional and has no effect on matching.
- Keywords are case-sensitive.

## Static validation

Loading fails with an error that names the offending line when:

- `all` appears together with any other condition in the same rule.
- A `prefix` or `has` list is empty, or a pattern in it has no words.
- The first word of a `prefix` pattern contains `/`. Prefix patterns name
  commands, not paths (see *Matching*).
- A `set` names an unknown setting, or the same setting is set twice.
- A `reason` string is empty.

## Settings

| Name    | Value                                    | Default           |
|---------|------------------------------------------|-------------------|
| `shfmt` | Path of the `shfmt` executable            | `shfmt` on `PATH` |
| `log`   | Path of the file that `log` rules append to | standard error    |

## Semantics

### Units of evaluation

A Bash command line is parsed into the *simple commands* that may execute
(see `CommandParser`). This includes commands inside command and process
substitutions, which are evaluated as siblings of the command that contains
them. A simple command has an argv; each argv word is either *literal* (its
value is known statically) or *uncertain* (it contains parameter expansion,
command substitution, globbing, tilde expansion, or escapes).

Rules are evaluated against each simple command independently. Simple
commands with an empty argv (assignment-only statements such as `FOO=bar`)
are not evaluated.

### Conditions

A rule matches a simple command when every condition of the rule holds for
it.

- `all` always holds.
- `prefix [p1, p2, ...]` holds when at least one pattern matches the start of
  the command's argv.
- `has [p1, p2, ...]` holds when at least one pattern matches anywhere in the
  command's argv.

A `prefix` pattern matches a command when:

1. The command's argv has at least as many words as the pattern.
2. Every argv word inside the compared prefix is literal. An uncertain word
   never satisfies a pattern word.
3. The first pattern word matches argv[0] as described under *Matching*.
4. Each remaining pattern word equals the corresponding argv word exactly.

Matching never inspects argv beyond the pattern's length. A `prefix` pattern
matches only at the start of argv: `prefix [git status]` matches neither
`sudo git status` nor `echo git status`.

A `has` pattern matches a command when its words equal a contiguous run of
literal argv words at any offset. `has [rm -rf]` matches `sudo rm -rf x` and
`has [--force]` matches `git push --force origin`. Words are compared exactly,
including at offset 0; `has` gives argv[0] no special treatment, so use
`prefix` to name a command. An uncertain word never takes part in a match,
but the search continues at other offsets. Because `has` looks at every
position, a `pass has [...]` rule is broad; prefer `prefix` for `pass`.

### Matching argv[0]

How the first pattern word is compared to argv[0] depends on the rule's
action, so that spelling a path cannot be used to dodge a restrictive rule or
to impersonate a permitted command.

- For `block` and `ask`, the pattern word is compared to the *basename* of
  argv[0]. `/usr/bin/git` and `./git` both match a pattern `git`.
- For `pass` and `delegate`, the pattern word must equal argv[0] exactly.
  Only a bare `git` matches a pattern `git`; `./git` does not.

### Per-command verdict

For each simple command, rules are evaluated in file order. The verdict of
the command is the action of the *last* matching rule. If no rule matches,
the command is *unmatched*.

### Line decision

The decision for the whole command line is derived from the per-command
verdicts, in this order:

1. If any command's verdict is `block`, the decision is **deny**.
2. Otherwise, if any command's verdict is `ask`, the decision is **ask**.
3. Otherwise, if there is at least one evaluated command and every command's
   verdict is `pass`, the decision is **allow**.
4. Otherwise, the decision is **delegate**: meljudge emits no decision and
   the normal permission flow applies. This covers `delegate` verdicts,
   unmatched commands, and command lines with no evaluated commands.

Consequently a single `pass` never covers a whole line: every command in the
line must be passed for the line to be allowed, and a `block` anywhere in the
line denies it.

### Reasons

`reason "text"` attaches an explanation to a rule. It does not influence the
decision. When the rule that decides a line is a `block` or `ask` rule with a
reason, the decision reason sent to Claude Code starts with that text,
followed by the mechanical explanation in parentheses:

```
meljudge: Force pushes are forbidden here. Push to a new branch instead. ("git push --force" matches block rule at line 5)
```

Claude Code shows a deny reason to the model and an ask reason to the user,
so write the text as guidance for whoever will read it, and do not put
secrets in it. A reason on a `pass` or `delegate` rule is accepted but has no
visible effect, because those decisions carry no reason to a reader.

### Logging

`log` is a modifier and does not influence the decision. When the last
matching rule for a command carries `log`, one log entry is written (see *Log
format*): appended to the file named by `set log`, or written to standard
error when `set log` is absent. `block log` therefore records denied
commands.

### Failure handling

- If the rule file fails to load, or a file named by `--config` is missing,
  the decision is **ask**, and the reason names the error. A missing default
  file is created instead (see *Rule file location*).
- If the command line cannot be parsed as Bash, the decision is
  **delegate**; the normal permission flow still sees the full text.
- If the parser backend cannot be run, the decision is **ask**.

## Rule file location

The rule file is looked up in this order:

1. The path given by `--config PATH`.
2. `$XDG_CONFIG_HOME/meljudge/meljudge.conf`, when `XDG_CONFIG_HOME` is set.
3. `$HOME/.config/meljudge/meljudge.conf`.

When no `--config` is given and the default file does not exist, meljudge
creates it (and its directory) with a commented template whose only rule is
`delegate all`, so a fresh installation behaves like Claude Code without
meljudge. The creation is reported once: in hook mode the decision for that
call is **ask** with a reason naming the created file; `--plain` and
`--check` print a notice on standard error and then proceed. A file named by
`--config` is never created.

## Hook protocol

meljudge is a Claude Code `PreToolUse` hook. It reads the hook payload as
JSON on standard input and always exits 0.

- When `tool_name` is not `Bash`, or `tool_input.command` is missing or
  empty, meljudge prints nothing.
- For a **deny**, **ask**, or **allow** decision it prints one JSON object:

  ```json
  {"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"meljudge: ..."}}
  ```

  The reason names the command and the rule line that decided a deny or an
  ask, preceded by the rule's `reason` text when it has one (see *Reasons*),
  and the pass rule lines for an allow. Uncertain argv words are shown as `?`.
- For a **delegate** decision it prints nothing, so the normal permission
  flow applies.

Invalid arguments and undecodable hook input are reported as **ask** with the
error in the reason, like a broken rule file.

## Log format

Each entry is one line, appended to the file named by `set log` or written to
standard error when that setting is absent:

```
2026-09-04T03:12:45Z deny rule=8 block rm -rf /
```

The fields are the UTC timestamp, the line decision, the line number of the
adopted rule, its action, and the command's argv with uncertain words shown
as `?`. If the log file cannot be written, the decision is **ask** with the
error in the reason.

## Command line

```
meljudge [--config PATH]                 hook mode (default)
meljudge [--config PATH] --plain [CMD...] judge a command line (or stdin) and print the result
meljudge [--config PATH] --check          load the rule file and report errors
meljudge --help | --version
```

Every argument that starts with `-` (other than a lone `-`) must be one of the
options above; anything else is rejected as an unknown argument. `--` ends
option parsing so that a `--plain` command line may begin with a dash.
Positional arguments are accepted only with `--plain`. `--help` prints the
usage and exits 0; it must be the only argument.

### Hook mode

The default. Behaves as described under *Hook protocol* and always exits 0.

### Plain mode

`--plain` judges one Bash command line given as the remaining arguments
(joined with single spaces). When there are none, or the only argument is `-`,
the command line is read from standard input; because that may block on a
terminal, meljudge first prints `meljudge: CMD is empty; reading the command
line from standard input` on standard error.

Plain mode prints the decision and the reason on the first line, then one line
per evaluated simple command showing the adopted rule's action (with `log`
when the rule carries it), its line number, and the command's argv:

```
deny: "rm -rf /tmp/x" matches block rule at line 4
  pass       line 2   git status
  block      line 4   rm -rf /tmp/x
  -          -        ls
```

Unmatched commands show `-` in both columns. A delegate decision prints
`delegate: no decision; the normal permission flow applies`. Plain mode never
writes log entries; `log` rules are only shown in the table.

The exit code encodes the outcome:

| Exit | Meaning                                              |
|------|------------------------------------------------------|
| 0    | allow                                                |
| 1    | ask                                                  |
| 2    | deny                                                 |
| 3    | delegate                                             |
| 64   | invalid arguments                                    |
| 65   | the rule file failed to load, or the command line is not valid Bash |
| 69   | the parser backend could not be run                  |

Errors are printed to standard error as `meljudge: <message>`; argument
errors add a second line pointing to `--help`.

### Check mode

`--check` loads the rule file and exits 0 after printing
`ok: <path> (<n> rules)`, or exits 65 with the error on standard error. It is
the counterpart of `pfctl -nf`.

`--plain` and `--check` cannot be combined.
