package com.joelj.jenkins;

import com.cloudbees.jenkins.plugins.sshcredentials.*;
import hudson.*;
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
	private final /*nullable*/ SSHUserPrivateKey sshCredentials;

	public Git(String gitExecutable, FilePath workspace, TaskListener listener, SSHUserPrivateKey sshCredentials) {
		this.gitExecutable = gitExecutable;
		this.workspace = workspace;
		this.listener = listener;
		this.sshCredentials = sshCredentials;
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
		executeCommand(sshCredentials, "pull", remote, branch);
	}

	public void fetch(String remote) throws IOException, InterruptedException {
		executeCommand(sshCredentials, "fetch", remote);
	}

	public void fetch(String remote, String... refSpecs) throws IOException, InterruptedException {
		List<String> parameters = new ArrayList<String>();
		parameters.add("fetch");
		parameters.add(remote);
		for (String refSpec : refSpecs) {
			String trimmed = refSpec.trim();
			if(!trimmed.isEmpty()) {
				parameters.add(trimmed);
			}
		}
		executeCommand(sshCredentials, parameters.toArray(new String[parameters.size()]));
	}

	public void checkout(String commitish) throws IOException, InterruptedException {
		executeCommand("checkout", commitish);
	}

	public void cloneRepo(String host) throws IOException, InterruptedException {
		executeCommand(sshCredentials, "clone", host, ".");
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
		return executeCommand(null, command);
	}

	/**
	 * Since there's slight overhead (creating temp files on the remote machine)
	 * 	for running commands with sshCredentials,
	 * only call this if the git command is actually going to use ssh.
	 * Such as pull, fetch, and clone.
	 */
	private String executeCommand(SSHUserPrivateKey sshCredentials, String... command) throws IOException, InterruptedException {
		if (sshCredentials != null) {
			String sshGit = sshCredentials.getPrivateKeys().get(0);

			//noinspection OctalInteger
			String pemFileRemote = createTempFile(getWorkspace(), sshGit, "ssh", ".pem", 0700);

			try {
				String gitSshWrapperContent = "#!/bin/bash\nssh -i '" + pemFileRemote + "' \"$@\"";

				//noinspection OctalInteger
				String gitSshScriptRemote = createTempFile(getWorkspace(), gitSshWrapperContent, "gitSsh", ".sh", 0755);

				try {
					return getWorkspace().act(new GitFileCallable(getGitExecutable(), gitSshScriptRemote, listener, command));
				} finally {
					deleteRemoteFile(getWorkspace(), gitSshScriptRemote);
				}
			} finally {
				deleteRemoteFile(getWorkspace(), pemFileRemote);
			}
		} else {
			return getWorkspace().act(new GitFileCallable(getGitExecutable(), listener, command));
		}
	}

	private String createTempFile(FilePath filePath, final String content, final String fileName, final String fileExtension, final int permissions) throws IOException, InterruptedException {
		return filePath.act(new CreateTempFileCallable(fileName, fileExtension, content, permissions));
	}

	private void deleteRemoteFile(FilePath filePath, final String filePathToDelete) throws IOException, InterruptedException {
		filePath.act(new DeleteRemoteFileCallable(filePathToDelete));
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
		String result = executeCommand(list);
		if(getListener() != null) {
			getListener().getLogger().println(result);
		}
		return result;
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
		private final String gitSshPath;
		private final TaskListener listener;
		private final String[] command;

		public GitFileCallable(String gitPath, TaskListener listener, String... command) {
			this(gitPath, null, listener, command);
		}

		public GitFileCallable(String gitPath, String gitSshPath, TaskListener listener, String... command) {
			this.gitPath = gitPath;
			this.gitSshPath = gitSshPath;
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
			if(gitSshPath != null) {
				processBuilder.environment().put("GIT_SSH", gitSshPath);
			}
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

	private static class CreateTempFileCallable implements FilePath.FileCallable<String> {
		private final String fileName;
		private final String fileExtension;
		private final String content;
		private final int permissions;

		public CreateTempFileCallable(String fileName, String fileExtension, String content, int permissions) {
			this.fileName = fileName;
			this.fileExtension = fileExtension;
			this.content = content;
			this.permissions = permissions;
		}

		public String invoke(File workspace, VirtualChannel channel) throws IOException, InterruptedException {
			File tempFile = File.createTempFile(fileName, fileExtension);

			FilePath tempFilePath = new FilePath(tempFile);
			tempFilePath.write(content, "UTF-8");
			tempFilePath.chmod(permissions);

			return tempFilePath.getRemote();
		}
	}

	private static class DeleteRemoteFileCallable implements FilePath.FileCallable<String> {
		private final String filePathToDelete;

		public DeleteRemoteFileCallable(String filePathToDelete) {
			this.filePathToDelete = filePathToDelete;
		}

		public String invoke(File workspace, VirtualChannel channel) throws IOException, InterruptedException {
			FilePath tempFilePath = new FilePath(new File(filePathToDelete));
			tempFilePath.delete();
			return null;
		}
	}
}
