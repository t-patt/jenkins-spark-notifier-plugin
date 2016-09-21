package com.jenkins.plugins.sparknotify;

import java.io.IOException;

public class SparkNotifyException extends IOException {
	private static final long serialVersionUID = 1L;

	public SparkNotifyException(final String message) {
		super(message);
	}

	public SparkNotifyException(final String message, final Throwable t) {
		super(message, t);
	}
}
