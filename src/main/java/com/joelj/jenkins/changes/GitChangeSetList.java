package com.joelj.jenkins.changes;

import hudson.model.AbstractBuild;
import hudson.scm.ChangeLogSet;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * List of changeset that went into a particular build.
 *
 * Copied from the default Git plugin.
 *
 * @author Nigel Magnay
 */
public class GitChangeSetList extends ChangeLogSet<GitChangeSet> {
    private final List<GitChangeSet> changeSets;

    /*package*/ GitChangeSetList(AbstractBuild build, List<GitChangeSet> logs) {
        super(build);
        Collections.reverse(logs);  // put new things first
        this.changeSets = Collections.unmodifiableList(logs);
        for (GitChangeSet log : logs)
            log.setParent(this);
    }

    public boolean isEmptySet() {
        return changeSets.isEmpty();
    }

    public Iterator<GitChangeSet> iterator() {
        return changeSets.iterator();
    }

    public List<GitChangeSet> getLogs() {
        return changeSets;
    }
}
