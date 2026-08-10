package com.mrsmith.tool;

import com.mrsmith.config.ShellConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Heuristic shell command classifier. Not a security boundary: it only decides
 * whether the shell approval prompt is shown. DANGEROUS and UNKNOWN both
 * require approval; the distinction is used for the prompt label.
 */
public final class ShellCommandClassifier {

    enum Verdict { SAFE, DANGEROUS, UNKNOWN }

    record Classification(Verdict verdict, List<String> keys) {

        static Classification safe() {
            return new Classification(Verdict.SAFE, List.of());
        }

        boolean requiresApproval() {
            return verdict != Verdict.SAFE;
        }
    }

    private static final Set<String> SAFE = Set.of(
            "ls", "cat", "pwd", "echo", "printf", "head", "tail", "wc", "grep",
            "find", "diff", "sort", "uniq", "cut", "tr", "file", "stat", "du",
            "df", "which", "readlink", "basename", "dirname", "date", "cal",
            "whoami", "uname", "hostname", "env", "printenv", "id", "tree",
            "cd", "export", "set", "unset");

    private static final Set<String> DANGEROUS = Set.of(
            "rm", "rmdir", "mv", "cp", "touch", "mkdir", "chmod", "chown", "ln",
            "dd", "tee", "sed", "truncate", "install", "patch", "shred",
            "unlink", "mount", "umount");

    private static final Map<String, Set<String>> SAFE_SUBCOMMANDS = Map.of(
            "git", Set.of("status", "diff", "log", "show", "branch", "ls-files",
                    "rev-parse", "remote", "tag"));

    private static final Map<String, Set<String>> DANGEROUS_FLAGS = Map.of(
            "find", Set.of("-delete", "-exec", "-execdir", "-ok", "-okdir"),
            "sort", Set.of("-o"));

    private final Set<String> safeBinaries;
    private final Set<String> dangerousBinaries;
    private final Map<String, Set<String>> safeSubcommands;
    private final Map<String, Set<String>> dangerousSubcommands;

    public ShellCommandClassifier() {
        this(new ShellConfig(List.of(), List.of()));
    }

    public ShellCommandClassifier(ShellConfig config) {
        this.safeBinaries = new HashSet<>(SAFE);
        this.dangerousBinaries = new HashSet<>(DANGEROUS);
        this.safeSubcommands = new HashMap<>();
        SAFE_SUBCOMMANDS.forEach((binary, subs) ->
                safeSubcommands.put(binary, new HashSet<>(subs)));
        this.dangerousSubcommands = new HashMap<>();

        for (String spec : config.harmlessCommands()) {
            String[] parts = spec.trim().split("\\s+");
            if (parts.length <= 1) {
                safeBinaries.add(normalize(parts[0]));
            } else {
                safeSubcommands.computeIfAbsent(normalize(parts[0]), k -> new HashSet<>())
                        .add(normalize(parts[1]));
            }
        }
        for (String spec : config.dangerousCommands()) {
            String[] parts = spec.trim().split("\\s+");
            if (parts.length <= 1) {
                dangerousBinaries.add(normalize(parts[0]));
            } else {
                dangerousSubcommands.computeIfAbsent(normalize(parts[0]), k -> new HashSet<>())
                        .add(normalize(parts[1]));
            }
        }
    }

    public Classification classify(String command) {
        if (command == null || command.isBlank()) {
            return Classification.safe();
        }
        Parsed parsed = parse(command);
        Verdict verdict = Verdict.SAFE;
        List<String> keys = new ArrayList<>();
        StringBuilder redirectKey = new StringBuilder();
        for (Segment segment : parsed.segments) {
            String trimmed = segment.text.trim();
            if (trimmed.isEmpty()) {
                redirectKey.append(segment.separator);
                continue;
            }
            String[] words = trimmed.split("\\s+");
            String binary = normalize(words[0]);
            String subcommand = words.length > 1 ? normalize(words[1]) : null;
            String dangerousFlag = dangerousFlag(binary, words);
            Verdict v = classifySegment(binary, subcommand);
            if (dangerousFlag != null) {
                v = Verdict.DANGEROUS;
            }
            if (v == Verdict.DANGEROUS) {
                verdict = Verdict.DANGEROUS;
            } else if (v == Verdict.UNKNOWN && verdict == Verdict.SAFE) {
                verdict = Verdict.UNKNOWN;
            }
            boolean aware = subcommandAware(binary);
            redirectKey.append(canonical(binary, subcommand, aware, segment.redirect, dangerousFlag));
            redirectKey.append(segment.separator);
            if (v != Verdict.SAFE) {
                keys.add(canonical(binary, subcommand, aware, false, dangerousFlag));
            }
        }
        if (parsed.redirection) {
            return new Classification(Verdict.DANGEROUS, List.of(redirectKey.toString()));
        }
        return new Classification(verdict, List.copyOf(keys));
    }

    private Verdict classifySegment(String binary, String subcommand) {
        if (dangerousBinaries.contains(binary)) {
            return Verdict.DANGEROUS;
        }
        Set<String> dangerSubs = dangerousSubcommands.get(binary);
        Set<String> safeSubs = safeSubcommands.get(binary);
        if (subcommand != null) {
            if (dangerSubs != null && dangerSubs.contains(subcommand)) {
                return Verdict.DANGEROUS;
            }
            if (safeSubs != null) {
                return safeSubs.contains(subcommand) ? Verdict.SAFE : Verdict.DANGEROUS;
            }
        }
        if (safeBinaries.contains(binary)) {
            return Verdict.SAFE;
        }
        if (safeSubs != null) {
            return Verdict.DANGEROUS;
        }
        return Verdict.UNKNOWN;
    }

    private String dangerousFlag(String binary, String[] words) {
        Set<String> flags = DANGEROUS_FLAGS.get(binary);
        if (flags == null) {
            return null;
        }
        for (int i = 1; i < words.length; i++) {
            String word = normalize(words[i]);
            if (flags.contains(word)) {
                return word;
            }
        }
        return null;
    }

    private boolean subcommandAware(String binary) {
        return safeSubcommands.containsKey(binary) || dangerousSubcommands.containsKey(binary);
    }

    private String canonical(String binary, String subcommand, boolean aware, boolean redirect, String dangerousFlag) {
        StringBuilder sb = new StringBuilder(binary);
        if (aware && subcommand != null) {
            sb.append(' ').append(subcommand);
        } else if (dangerousFlag != null) {
            sb.append(' ').append(dangerousFlag);
        }
        if (redirect) {
            sb.append(" >");
        }
        return sb.toString();
    }

    private static String normalize(String word) {
        return word == null ? "" : word.toLowerCase(Locale.ROOT);
    }

    private record Segment(String text, String separator, boolean redirect) {
    }

    private record Parsed(List<Segment> segments, boolean redirection) {
    }

    private static Parsed parse(String command) {
        List<Segment> segments = new ArrayList<>();
        boolean redirection = false;
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                current.append(c);
                escaped = true;
                continue;
            }
            if (quote != 0) {
                current.append(c);
                if (quote == '\'' && c == '\'') {
                    quote = 0;
                } else if (quote == '"' && c == '"') {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                current.append(c);
                continue;
            }
            if (c == '>') {
                redirection = true;
                current.append(c);
                continue;
            }
            String sep = null;
            if (c == '&' && i + 1 < command.length() && command.charAt(i + 1) == '&') {
                sep = "&&";
                i++;
            } else if (c == '|' && i + 1 < command.length() && command.charAt(i + 1) == '|') {
                sep = "||";
                i++;
            } else if (c == '&') {
                sep = "&";
            } else if (c == '|') {
                sep = "|";
            } else if (c == ';' || c == '\n') {
                sep = ";";
            }
            if (sep != null) {
                segments.add(new Segment(current.toString(), sep, redirectionIn(current)));
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        segments.add(new Segment(current.toString(), "", redirectionIn(current)));
        return new Parsed(segments, redirection);
    }

    private static boolean redirectionIn(StringBuilder current) {
        return current.indexOf(">") >= 0;
    }
}
