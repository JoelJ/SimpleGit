package com.joelj.jenkins;

import com.joelj.jenkins.changes.GitChangeLogParser;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.*;
import hudson.scm.*;
import net.sf.json.JSONObject;
import org.apache.commons.io.FileUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.export.Exported;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Serializable;

/**
 * User: Joel Johnson
 * Date: 1/26/13
 * Time: 10:43 AM
 */
public class SimpleGitScm extends SCM implements Serializable {
	public static final String NEWLINE = "%n";
	public static final String HASH = "%H";
	public static final String COMMITTER_NAME = "%cn";
	public static final String AUTHOR_NAME = "%an";
	public static final String COMMITTER_EMAIL = "%ce";
	public static final String AUTHOR_EMAIL = "%ae";

	private String host;
	private String branch;
	private String revisionRangeStart;
	private String revisionRangeEnd;
	private boolean expandMerges;
	private boolean showMergeCommits;

	@DataBoundConstructor
	public SimpleGitScm(String host, String branch, String revisionRangeStart, String revisionRangeEnd) {
		this.host = host;
		this.branch = branch;
		this.revisionRangeEnd = revisionRangeEnd == null || revisionRangeEnd.trim().isEmpty() ? "HEAD" : revisionRangeEnd;
		this.revisionRangeStart = revisionRangeStart == null || revisionRangeStart.trim().isEmpty() ? this.revisionRangeEnd+"^1" : revisionRangeStart;
		this.expandMerges = false;
		this.showMergeCommits = true;
	}

	@Override
	protected PollingResult compareRemoteRevisionWith(AbstractProject<?, ?> project, Launcher launcher, FilePath workspace, TaskListener listener, SCMRevisionState baseline) throws IOException, InterruptedException {
		return null;
	}

	@Override
	public boolean checkout(AbstractBuild<?, ?> build, Launcher launcher, FilePath workspace, BuildListener listener, File changelogFile) throws IOException, InterruptedException {
		PrintStream logger = listener.getLogger();
		logger.println("SimpleGit: checking out");
		EnvVars environment = build.getEnvironment(listener);
		String hostExpanded = environment.expand(host);
		String branchExpanded = environment.expand(branch);

		String revisionRangeEndExpanded = environment.expand(revisionRangeEnd);
		revisionRangeEndExpanded = revisionRangeEndExpanded == null || revisionRangeEndExpanded.isEmpty() ? "HEAD" : revisionRangeEndExpanded;

		String revisionRangeStartExpanded = environment.expand(revisionRangeStart);
		revisionRangeStartExpanded = revisionRangeStartExpanded == null || revisionRangeStartExpanded.isEmpty() ? revisionRangeEndExpanded+"^1" : revisionRangeStartExpanded;

		Git git = new Git(getDescriptor().getExecutablePath(), workspace, listener);
		FilePath filePath = new FilePath(workspace, ".git");
		if(filePath.exists()) {
			git.reset();
			git.clean();
			git.pull("origin", branchExpanded);
			git.checkout(branchExpanded, revisionRangeEndExpanded);
		} else {
			git.cloneRepo(hostExpanded);
			git.checkout(branchExpanded, revisionRangeEndExpanded);
		}
		logger.println(git.showHead());

		addGitVariablesToBuild(build, git);

		FileUtils.writeStringToFile(changelogFile, git.whatChanged(revisionRangeStartExpanded, revisionRangeEndExpanded, getExpandMerges(), getShowMergeCommits()));

		return true;
	}

	private void addGitVariablesToBuild(AbstractBuild<?, ?> build, Git git) throws IOException, InterruptedException {
		String log = git.log("-n1", "--pretty=" + HASH + NEWLINE + COMMITTER_NAME + NEWLINE + AUTHOR_NAME + NEWLINE + COMMITTER_EMAIL + NEWLINE + AUTHOR_EMAIL);

		String[] split = log.split("\n");
		String currentRevision = split[0];
		String committer = split[1];
		String author = split[2];
		String committerEmail = split[3];
		String authorEmail = split[4];

		String commitMessage = git.log("-n1", "--pretty=%B");

		build.addAction(new GitVariablesAction("SIMPLE_GIT_", currentRevision, committer, author, committerEmail, authorEmail, commitMessage));
	}

	@Override
	public SCMRevisionState calcRevisionsFromBuild(AbstractBuild<?, ?> build, Launcher launcher, TaskListener listener) throws IOException, InterruptedException {
		return null;
	}

	@Override
	public ChangeLogParser createChangeLogParser() {
		return new GitChangeLogParser(true);
	}

	@Override
	public boolean requiresWorkspaceForPolling() {
		return false;
	}

	@Exported
	public String getHost() {
		return host;
	}

	@Exported
	public String getBranch() {
		return branch;
	}

	@Exported
	public String getRevisionRangeStart() {
		return revisionRangeStart;
	}

	@Exported
	public String getRevisionRangeEnd() {
		return revisionRangeEnd;
	}

	public boolean getExpandMerges() {
		return expandMerges;
	}

	public boolean getShowMergeCommits() {
		return showMergeCommits;
	}

	@Override
	public DescriptorImpl getDescriptor() {
		return (DescriptorImpl)super.getDescriptor();
	}

	@Extension
	public static final class DescriptorImpl extends SCMDescriptor<SimpleGitScm> {
		private String executablePath;

		public DescriptorImpl() {
			super(SimpleGitScm.class, null);
			load();
		}

		@Override
		public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
			req.bindJSON(this, formData);
			save();
			return true;
		}

		public String getDisplayName() {
			return "Simple Git";
		}

		public String getExecutablePath() {
			return executablePath;
		}

		@SuppressWarnings("UnusedDeclaration")
		public void setExecutablePath(String value) {
			this.executablePath = value;
		}

		public SCM newInstance(StaplerRequest req, JSONObject formData) throws FormException {
			return super.newInstance(req, formData);
		}

	}
}