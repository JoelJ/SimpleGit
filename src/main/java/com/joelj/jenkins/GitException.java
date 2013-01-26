package com.joelj.jenkins;

import java.io.IOException;
import java.io.Serializable;

/**
 * User: Joel Johnson
 * Date: 1/26/13
 * Time: 11:06 AM
 */
public class GitException extends IOException implements Serializable {
	public GitException(int exitCode, String message) {
		super("Git exited with a value of: " + exitCode + ". " + message.trim());
	}
}
