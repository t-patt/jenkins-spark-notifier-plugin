package com.jenkins.plugins.sparknotify;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.jenkinsci.plugins.plaincredentials.StringCredentials;

import com.cloudbees.plugins.credentials.Credentials;
import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;
import com.jenkins.plugins.sparknotify.SparkMessage.SparkMessageBuilder;

import hudson.EnvVars;

public class SparkNotifier {
	private static final String SPARK_MSG_POST_URL = "https://api.ciscospark.com/v1/messages";
	private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{(.+?)\\}");
	private static final Client DEFAULT_CLIENT = ClientBuilder.newBuilder().register(JacksonJsonProvider.class).build();

	private final Credentials credentials;
	private final EnvVars env;

	public SparkNotifier(final Credentials credentials, final EnvVars env) {
		this.credentials = credentials;
		this.env = env;
	}

	public int sendMessage(final String roomId, String message, final SparkMessageType messageType) throws IOException {
		message = replaceEnvVars(message, env);

		SparkMessage messageData = new SparkMessageBuilder().roomId(roomId).message(message).messageType(messageType).build();

		Response response = DEFAULT_CLIENT.target(SPARK_MSG_POST_URL).request(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + getMachineAccountToken())
				.post(Entity.json(messageData));

		return response.getStatus();
	}

	private String getMachineAccountToken() throws SparkNotifyException {
		if (credentials instanceof StringCredentials) {
			StringCredentials tokenCredential = ((StringCredentials) credentials);
			String token = tokenCredential.getSecret().getPlainText();
			if (token == null || token.isEmpty()) {
				throw new SparkNotifyException("Token cannot be null");
			}
			return token;
		} else {
			throw new SparkNotifyException("Invalid credential type, can only use 'Secret text' (bot token)");
		}
	}

	private String replaceEnvVars(String message, final EnvVars env) {
		Matcher matcher = ENV_PATTERN.matcher(message);
		while (matcher.find()) {
			String var = matcher.group(1);
			message = message.replace("${" + var + "}", env.get(var, ""));
		}

		return message;
	}
}