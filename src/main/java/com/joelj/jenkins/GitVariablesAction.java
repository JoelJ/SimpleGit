package com.joelj.jenkins;

import hudson.EnvVars;
import hudson.model.AbstractBuild;
import hudson.model.Action;
import hudson.model.EnvironmentContributingAction;

import java.util.HashMap;
import java.util.Map;

/**
 * User: Joel Johnson
 * Date: 1/26/13
 * Time: 12:18 PM
 */
public class GitVariablesAction implements EnvironmentContributingAction {
	private Map<String, String> map;
	public GitVariablesAction(String prefix, String head, String committer, String author, String committerEmail, String authorEmail, String commitMessage) {
		map = new HashMap<String, String>();
		map.put(prefix+"HEAD", head);
		map.put(prefix+"COMMITTER", committer);
		map.put(prefix+"AUTHOR", author);
		map.put(prefix+"COMMITTER_EMAIL", committerEmail);
		map.put(prefix+"AUTHOR_EMAIL", authorEmail);
		map.put(prefix+"COMMIT_MESSAGE", commitMessage);
	}

	public void buildEnvVars(AbstractBuild<?, ?> build, EnvVars env) {
		env.putAll(map);
	}

	public String getIconFileName() {
		return null;
	}

	public String getDisplayName() {
		return null;
	}

	public String getUrlName() {
		return null;
	}
}
