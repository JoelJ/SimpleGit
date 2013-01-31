package com.joelj.jenkins;

import hudson.FilePath;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.lf5.util.StreamUtils;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * User: Joel Johnson
 * Date: 1/26/13
 * Time: 10:43 AM
 */
public class Git {
	private final String gitExecutable;
	private final FilePath workspace;
	private final /*nullable*/ TaskListener listener;

	public Git(String gitExecutable, FilePath workspace, TaskListener listener) {
		this.gitExecutable = gitExecutable;
		this.workspace = workspace;
		this.listener = listener;
	}

	public String getGitExecutable() {
		return gitExecutable;
	}

	public FilePath getWorkspace() {
		return workspace;
	}

	public TaskListener getListener() {
		return listener;
	}

	public void reset() throws IOException, InterruptedException {
		executeCommand("reset", "--hard");
	}

	public void clean() throws IOException, InterruptedException {
		executeCommand("clean", "-f", "-d", "-x");
	}

	public void pull(String remote, String branch) throws IOException, InterruptedException {
		executeCommand("pull", remote, branch);
	}

	public void checkout(String commitish) throws IOException, InterruptedException {
		executeCommand("checkout", commitish);
	}

	public void cloneRepo(String host) throws IOException, InterruptedException {
		executeCommand("clone", host, ".");
	}

	private String executeCommand(Collection<String> command) throws IOException, InterruptedException {
		return executeCommand(command.toArray(new String[command.size()]));
	}

	private String executeCommand(String... command) throws IOException, InterruptedException {
		return getWorkspace().act(new GitFileCallable(getGitExecutable(), listener, command));
	}

	public String showHead() throws IOException, InterruptedException {
		return executeCommand("log", "-n1");
	}

	public String log(String... parameters) throws IOException, InterruptedException {
		List<String> list = new ArrayList<String>(parameters.length + 1);
		list.add("log");
		Collections.addAll(list, parameters);
		return executeCommand(list);
	}

	public String whatChanged(String revisionRangeStart, String revisionRangeEnd, boolean expandMerges, boolean includeMergeCommits) throws IOException, InterruptedException {
		List<String> list = new ArrayList<String>();
		list.add("whatchanged");
		if(includeMergeCommits) {
			list.add("--first-parent");
		}
		if(!expandMerges) {
			list.add("-m");
		}
		list.add("--pretty=raw");
		list.add("--no-abbrev");
		list.add("-M");

		list.add(revisionRangeStart+".."+revisionRangeEnd);
		return executeCommand(list);
	}

	/**
	 * Currently only being used for debugging
	 */
	public void revParse(String parameters) throws IOException, InterruptedException {
		if(listener != null) {
			String revParse = executeCommand("rev-parse", parameters);
			listener.getLogger().println(revParse);
		}
	}

	private static class GitFileCallable implements FilePath.FileCallable<String> {
		private final String gitPath;
		private final TaskListener listener;
		private final String[] command;

		public GitFileCallable(String gitPath, TaskListener listener, String... command) {
			this.gitPath = gitPath;
			this.listener = listener;
			this.command = command;
		}

		public String invoke(File workingDirectory, VirtualChannel channel) throws IOException, InterruptedException {
			List<String> command = new ArrayList<String>(this.command.length + 1);
			command.add(gitPath);
			Collections.addAll(command, this.command);

			if(listener != null) {
				listener.getLogger().println("\t- Executing: `" + StringUtils.join(command, " ")+"`");
			}

			ProcessBuilder processBuilder = new ProcessBuilder(command);
			processBuilder.redirectErrorStream(true);
			processBuilder.directory(workingDirectory);

			Process process = processBuilder.start();
			byte[] bytes = IOUtils.toByteArray(process.getInputStream());
			String result = new String(bytes);

			int exitCode = process.waitFor();
			if(exitCode != 0) {
				throw new GitException(exitCode, result);
			}

			return result;
		}
	}
}
