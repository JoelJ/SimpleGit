package com.joelj.jenkins;

import com.cloudbees.jenkins.plugins.sshcredentials.*;
import com.cloudbees.plugins.credentials.*;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.*;
import hudson.plugins.git.GitChangeLogParser;
import hudson.scm.*;
import hudson.util.*;
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
	private String refSpec;
	private String revisionRangeStart;
	private String revisionRangeEnd;
	private boolean expandMerges;
	private boolean showMergeCommits;
	private boolean clearWorkspace;
	private boolean gitLogging;
	private String credentials;
	private Integer numberOfRetryClones;

	// Deprecated fields are fields that were in older versions that we don't support anymore.
	// But they have to remain here so jenkins doesn't puke when trying to load them
	@SuppressWarnings("UnusedDeclaration")
	@Deprecated
	private transient String branch;

	@DataBoundConstructor
	public SimpleGitScm(String host, String refSpec, String revisionRangeStart, String revisionRangeEnd, boolean expandMerges, boolean showMergeCommits, boolean clearWorkspace, boolean gitLogging, String credentials, Integer numberOfRetryClones) {
		this.host = host;
		this.refSpec = refSpec;
		this.revisionRangeEnd = revisionRangeEnd == null || revisionRangeEnd.trim().isEmpty() ? "HEAD" : revisionRangeEnd;
		this.revisionRangeStart = revisionRangeStart == null || revisionRangeStart.trim().isEmpty() ? this.revisionRangeEnd+"^" : revisionRangeStart;

		this.expandMerges = expandMerges;
		this.showMergeCommits = showMergeCommits;
		this.clearWorkspace = clearWorkspace;
		this.gitLogging = gitLogging;
		this.credentials = credentials;

		this.numberOfRetryClones = numberOfRetryClones;
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

		String revisionRangeEndExpanded = environment.expand(revisionRangeEnd);
		revisionRangeEndExpanded = revisionRangeEndExpanded == null || revisionRangeEndExpanded.isEmpty() ? "HEAD" : revisionRangeEndExpanded;

		String revisionRangeStartExpanded = environment.expand(revisionRangeStart);
		revisionRangeStartExpanded = revisionRangeStartExpanded == null || revisionRangeStartExpanded.isEmpty() ? revisionRangeEndExpanded+"^1" : revisionRangeStartExpanded;

		String refSpecExpanded = environment.expand(refSpec);

		if(clearWorkspace) {
			logger.println("Clear Workspace enabled: deleting contents of " + workspace.getRemote() + ".");
			workspace.deleteContents();
		}

		String gitExecutablePath = getDescriptor().getExecutablePath();
		if(gitExecutablePath == null || gitExecutablePath.isEmpty()) {
			throw new NullPointerException("No git executable path is specified. Configure one under 'Simple Git' in the global configuration");
		}

		SSHUserPrivateKey sshCredentials = findSshCredentials();
		Git git = new Git(gitExecutablePath, workspace, gitLogging ? listener : null, sshCredentials);
		FilePath gitDir = new FilePath(workspace, ".git");

		int numberOfRetryClones = getNumberOfRetryClones();
		for(int i = 0; i < numberOfRetryClones; i++) {
			try {
				if (gitDir.exists()) {
					attemptCheckoutFromExistingWorkspace(workspace, logger, hostExpanded, revisionRangeEndExpanded, refSpecExpanded, git);
				}

				if (!gitDir.exists()) { // not an else-if because the previous if-statement potentially just deletes the workspace.
					checkoutFromNewClone(hostExpanded, revisionRangeEndExpanded, refSpecExpanded, git);
				}

				break;
			} catch (IOException e) {
				listener.error("Error while cloning or checking out from git repository:");
				listener.error("-----------------");
				listener.error(e.getLocalizedMessage());
				listener.error("-----------------");
				listener.error("");

				if((i+1) < numberOfRetryClones) {
					listener.error("Cleaning and retrying (" + (i+1) + ").");
					workspace.deleteContents();
					listener.getLogger().println("\n");
				} else {
					return false;
				}
			}
		}

		logger.println(git.showHead());

		addGitVariablesToBuild(build, git);

		FileUtils.writeStringToFile(changelogFile, git.whatChanged(revisionRangeStartExpanded, revisionRangeEndExpanded, getExpandMerges(), getShowMergeCommits()));

		return true;
	}

	/**
	 * Attempts to switch to the host/revision with the existing workspace.
	 * if any errors occur, the workspace is cleared.
	 */
	private void attemptCheckoutFromExistingWorkspace(FilePath workspace, PrintStream logger, String hostExpanded, String revisionRangeEndExpanded, String refSpecExpanded, Git git) throws IOException, InterruptedException {
		// Make sure we have no changed files in the workspace
		git.reset();
		git.clean();

		// Make sure we switch origin to the right URL if it's changed
		String remoteUrl = git.remoteGetUrl("origin");
		if(remoteUrl != null && !remoteUrl.equals(hostExpanded)) {
			git.remoteSetUrl("origin", hostExpanded);
		}

		if(refSpecExpanded == null || refSpecExpanded.isEmpty()) {
			git.fetch("origin");
		} else {
			git.fetch("origin", refSpecExpanded.split("\n"));
		}

		git.checkout(revisionRangeEndExpanded);
		git.revParse("HEAD");
	}

	private void checkoutFromNewClone(String hostExpanded, String revisionRangeEndExpanded, String refSpecExpanded, Git git) throws IOException, InterruptedException {
		git.cloneRepo(hostExpanded);

		if(refSpecExpanded == null || refSpecExpanded.isEmpty()) {
			git.fetch("origin");
		} else {
			git.fetch("origin", refSpecExpanded.split("\n"));
		}

		git.checkout(revisionRangeEndExpanded);
		git.revParse("HEAD");
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
	public String getRefSpec() {
		return refSpec;
	}

	@Exported
	@Deprecated
	public String getBranch() {
		//noinspection deprecation
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

	@Exported
	public boolean getExpandMerges() {
		return expandMerges;
	}

	@Exported
	public boolean getShowMergeCommits() {
		return showMergeCommits;
	}

	@Exported
	public boolean getClearWorkspace() {
		return clearWorkspace;
	}

	@Exported
	public boolean getGitLogging() {
		return gitLogging;
	}

	@Exported
	public String getCredentials() {
		return credentials;
	}

	@Exported
	public int getNumberOfRetryClones() {
		return numberOfRetryClones == null || numberOfRetryClones <= 0 ? 1 : numberOfRetryClones;
	}

	public SSHUserPrivateKey findSshCredentials() {
		if(getCredentials() != null && !getCredentials().isEmpty()) {
			for (Credentials credentials : SystemCredentialsProvider.getInstance().getCredentials()) {
				if(credentials instanceof SSHUserPrivateKey) {
					SSHUserPrivateKey sshCredentials = (SSHUserPrivateKey) credentials;
					if(sshCredentials.getId().equals(getCredentials())) {
						return sshCredentials;
					}
				}
			}
		}

		return null;
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

		@SuppressWarnings("UnusedDeclaration")
		public ListBoxModel doFillCredentialsItems() {
			ListBoxModel items = new ListBoxModel();
			items.add("None", "");

			for (Credentials credentials : SystemCredentialsProvider.getInstance().getCredentials()) {
				if(credentials instanceof SSHUserPrivateKey) {
					SSHUserPrivateKey sshCredentials = (SSHUserPrivateKey) credentials;
					items.add(sshCredentials.getDescription(), sshCredentials.getId());
				}
			}
			return items;
		}
	}
}
