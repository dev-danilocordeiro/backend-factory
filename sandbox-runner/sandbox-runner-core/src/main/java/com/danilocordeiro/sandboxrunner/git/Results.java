package com.danilocordeiro.sandboxrunner.git;

import com.danilocordeiro.sandboxrunner.CommitInfo;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reads commits and diff between base and a result ref with hardened git. */
final class Results {

    private Results() {
    }

    static Imported read(Path gitDir, String base, String ref) {
        if (!Git.tryRun(gitDir, "merge-base", "--is-ancestor", base, ref).ok()) {
            throw new PolicyException("result history does not descend from the base commit " + base);
        }
        List<CommitInfo> commits = new ArrayList<>();
        String log = Git.run(gitDir, "log", "--reverse", "--format=%H%x1f%an%x1f%s", base + ".." + ref);
        for (String line : log.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] f = line.split("\u001f", 3);
            commits.add(new CommitInfo(f[0], f.length > 1 ? f[1] : "", f.length > 2 ? f[2] : ""));
        }
        if (commits.isEmpty()) {
            return Imported.empty(base);
        }
        List<String> paths = new ArrayList<>();
        for (String p : Git.run(gitDir, "diff", "--no-ext-diff", "--name-only", "-z", base, ref).split("\u0000")) {
            if (!p.isBlank()) {
                paths.add(p);
            }
        }
        List<String> gitlinks = new ArrayList<>();
        for (String line : Git.run(gitDir, "diff", "--no-ext-diff", "--raw", base, ref).split("\n")) {
            // :<old mode> <new mode> <old sha> <new sha> <status>\t<path>
            String[] f = line.split("\t", 2);
            if (f.length == 2 && f[0].split(" ").length > 1 && f[0].split(" ")[1].equals("160000")) {
                gitlinks.add(f[1]);
            }
        }
        String diff = Git.run(gitDir, "diff", "--no-ext-diff", "--no-textconv", base, ref);
        return new Imported(gitDir, ref, base, commits, diff, paths, gitlinks);
    }
}
