package com.mrsmith.tool;

import com.mrsmith.config.ShellConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellCommandClassifierTest {

    private final ShellCommandClassifier classifier = new ShellCommandClassifier();

    @Test
    void safeCommandRequiresNoApproval() {
        assertFalse(classifier.classify("ls -la").requiresApproval());
    }

    @Test
    void safeBuiltinsRequireNoApproval() {
        assertFalse(classifier.classify("cd && pwd && echo hi").requiresApproval());
    }

    @Test
    void dangerousCommandRequiresApprovalWithBinaryKey() {
        ShellCommandClassifier.Classification c = classifier.classify("rm -rf target");
        assertTrue(c.requiresApproval());
        assertEquals(List.of("rm"), c.keys());
    }

    @Test
    void unknownCommandRequiresApproval() {
        ShellCommandClassifier.Classification c = classifier.classify("frobnicate x");
        assertTrue(c.requiresApproval());
        assertEquals(List.of("frobnicate"), c.keys());
    }

    @Test
    void chainWithDangerousPartRequiresApproval() {
        ShellCommandClassifier.Classification c = classifier.classify("ls && rm -rf target");
        assertTrue(c.requiresApproval());
        assertEquals(List.of("rm"), c.keys());
    }

    @Test
    void chainWithUnknownPartRequiresApproval() {
        ShellCommandClassifier.Classification c = classifier.classify("ls && frobnicate x");
        assertTrue(c.requiresApproval());
        assertEquals(ShellCommandClassifier.Verdict.UNKNOWN, c.verdict());
        assertEquals(List.of("frobnicate"), c.keys());
    }

    @Test
    void redirectionMarksDangerousWithWholeCommandKey() {
        ShellCommandClassifier.Classification c = classifier.classify("cat f > out");
        assertTrue(c.requiresApproval());
        assertEquals(List.of("cat >"), c.keys());
    }

    @Test
    void quotedGreaterThanIsNotRedirection() {
        assertFalse(classifier.classify("echo \">\"").requiresApproval());
    }

    @Test
    void gitStatusIsSafe() {
        assertFalse(classifier.classify("git status").requiresApproval());
    }

    @Test
    void gitCommitIsDangerousWithSubcommandKey() {
        ShellCommandClassifier.Classification c = classifier.classify("git commit -m x");
        assertTrue(c.requiresApproval());
        assertEquals(List.of("git commit"), c.keys());
    }

    @Test
    void gitUnknownSubcommandIsDangerous() {
        ShellCommandClassifier.Classification c = classifier.classify("git nope");
        assertTrue(c.requiresApproval());
        assertEquals(List.of("git nope"), c.keys());
    }

    @Test
    void bareGitIsDangerous() {
        assertTrue(classifier.classify("git").requiresApproval());
    }

    @Test
    void chainKeysCoverEachDangerousSegment() {
        ShellCommandClassifier.Classification c = classifier.classify("git add x && git commit -m y");
        assertTrue(c.requiresApproval());
        assertEquals(List.of("git add", "git commit"), c.keys());
    }

    @Test
    void blankCommandIsSafe() {
        assertFalse(classifier.classify("   ").requiresApproval());
        assertFalse(classifier.classify(null).requiresApproval());
    }

    @Test
    void configPromotesSubcommandToSafe() {
        ShellCommandClassifier configurable = new ShellCommandClassifier(
                new ShellConfig(List.of("kubectl get"), List.of()));
        assertFalse(configurable.classify("kubectl get pods").requiresApproval());
        ShellCommandClassifier.Classification c = configurable.classify("kubectl apply -f x");
        assertTrue(c.requiresApproval());
        assertEquals(List.of("kubectl apply"), c.keys());
    }

    @Test
    void configWholeBinaryPromotesToSafe() {
        ShellCommandClassifier configurable = new ShellCommandClassifier(
                new ShellConfig(List.of("ps"), List.of()));
        assertFalse(configurable.classify("ps aux").requiresApproval());
    }

    @Test
    void configDangerousOverridesBuiltinSafe() {
        ShellCommandClassifier configurable = new ShellCommandClassifier(
                new ShellConfig(List.of(), List.of("echo")));
        assertTrue(configurable.classify("echo hi").requiresApproval());
    }

    @Test
    void configDangerousSubcommandOverridesHarmless() {
        ShellCommandClassifier configurable = new ShellCommandClassifier(
                new ShellConfig(List.of("kubectl get"), List.of("kubectl get")));
        assertTrue(configurable.classify("kubectl get pods").requiresApproval());
    }

    @Test
    void findWithDeleteFlagRequiresApproval() {
        ShellCommandClassifier.Classification c = classifier.classify("find . -delete");
        assertTrue(c.requiresApproval());
        assertEquals(List.of("find -delete"), c.keys());
    }

    @Test
    void findWithoutFlagsIsSafe() {
        assertFalse(classifier.classify("find . -name '*.java'").requiresApproval());
    }

    @Test
    void findExecRequiresApproval() {
        ShellCommandClassifier.Classification c = classifier.classify("find . -exec rm {} \\;");
        assertTrue(c.requiresApproval());
        assertEquals(List.of("find -exec"), c.keys());
    }

    @Test
    void sortWithOutputFlagRequiresApproval() {
        ShellCommandClassifier.Classification c = classifier.classify("sort -o out.txt f");
        assertTrue(c.requiresApproval());
        assertEquals(List.of("sort -o"), c.keys());
    }

    @Test
    void sortWithoutOutputFlagIsSafe() {
        assertFalse(classifier.classify("sort f").requiresApproval());
    }

    @Test
    void appendRedirectRequiresApproval() {
        assertTrue(classifier.classify("echo hi >> log").requiresApproval());
    }

    @Test
    void stderrRedirectRequiresApproval() {
        assertTrue(classifier.classify("echo hi 2> err").requiresApproval());
    }
}
