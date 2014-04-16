package com.joelj.jenkins;

import hudson.FilePath;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import org.apache.commons.io.*;
import org.apache.commons.lang.StringUtils;

import java.io.*;
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

	public void reset(String... parameters) throws IOException, InterruptedException {
		List<String> list = new ArrayList<String>(parameters.length + 1);
		list.add("reset");
		list.add("--hard");
		Collections.addAll(list, parameters);

		executeCommand(list);
	}

	public void clean() throws IOException, InterruptedException {
		executeCommand("clean", "-f", "-d", "-x");
	}

	public void pull(String remote, String branch) throws IOException, InterruptedException {
		executeCommand("pull", remote, branch);
	}

	public void fetch(String remote) throws IOException, InterruptedException {
		executeCommand("fetch", remote);
	}

	public void fetch(String remote, String refSpec) throws IOException, InterruptedException {
		executeCommand("fetch", remote, refSpec);
	}

	public void checkout(String commitish) throws IOException, InterruptedException {
		executeCommand("checkout", commitish);
	}

	public void cloneRepo(String host) throws IOException, InterruptedException {
		executeCommand("clone", host, ".");
	}

	/**
	 * @return Null if no remote with the given name is found. Otherwise, the URL of the given remote.
	 */
	public String remoteGetUrl(String remote) throws IOException, InterruptedException {
		String remotes = executeCommand("remote", "-v");
		Scanner scanner = new Scanner(remotes);
		while(scanner.hasNext()) {
			String checkingRemote = scanner.next().trim();
			String checkingUrl = scanner.next().trim();
			scanner.next(); // this is a throwaway, but we need to advance the cursor. This is "(fetch)" or "(pull)"

			if(checkingRemote.trim().equals(remote)) {
				return checkingUrl;
			}
		}

		return null;
	}

	public void remoteSetUrl(String remote, String url) throws IOException, InterruptedException {
		executeCommand("remote", "set-url", remote, url);
	}

	private String executeCommand(Collection<String> command) throws IOException, InterruptedException {
		return executeCommand(command.toArray(new String[command.size()]));
	}

	private String executeCommand(String... command) throws IOException, InterruptedException {
		return getWorkspace().act(new GitFileCallable(getGitExecutable(), listener, command));
	}

	/**
	 * Adds the given refspec to the given remote for fetching.
	 * If the given remote doesn't exist, the config file will be read, but nothing will be changed.
	 * @param remote i.e. "origin". The remote to have the refspec added to.
	 * @throws IOException
	 * @throws InterruptedException
	 */
	public void addFetch(final String remote, final String refspec) throws IOException, InterruptedException {
		if(listener != null) {
			listener.getLogger().println("adding refspec '" + refspec + "' to remote '" + remote + "'");
		}

		FilePath gitDir = getWorkspace().child(".git");
		gitDir.act(new AddFetchCallable(refspec, remote));
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

	private static class AddFetchCallable implements FilePath.FileCallable<Object> {
		private final String refspec;
		private final String remote;

		public AddFetchCallable(String refspec, String remote) {
			this.refspec = refspec;
			this.remote = remote;
		}

		public Object invoke(File gitRoot, VirtualChannel virtualChannel) throws IOException, InterruptedException {
			String lineToAdd = "fetch = " + refspec;

			File configFile = new File(gitRoot, "config");
			File newConfigFile = File.createTempFile("temp", "config");

			Scanner scanner = new Scanner(configFile);

			try {
				PrintWriter write = new PrintWriter(newConfigFile);
				try {
					while(scanner.hasNextLine()) {
						String line = scanner.nextLine();
						String trimmedLine = line.trim();

						if(!trimmedLine.equals(lineToAdd)) {
							write.println(line);
						}

						if(trimmedLine.equals("[remote \"" + remote + "\"]")) {
							write.println("\t"+lineToAdd);
						}

					}
				} finally {
					write.close();
				}
			} finally {
				scanner.close();
			}

			if(!configFile.delete()) {
				throw new IOException("could not delete git config file:" + configFile.getAbsolutePath());
			}
			FileUtils.moveFile(newConfigFile, configFile);
			return null;
		}
	}
}
