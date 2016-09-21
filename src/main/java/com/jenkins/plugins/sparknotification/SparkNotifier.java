package com.jenkins.plugins.sparknotification;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;

import hudson.EnvVars;

import com.jenkins.plugins.sparknotification.SparkMessage.SparkMessageBuilder;
import com.cloudbees.plugins.credentials.Credentials;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

public class SparkNotifier {
	private static final String SPARK_MSG_POST_URL = "https://api.ciscospark.com/v1/messages";
	private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{(.+?)\\}");
	private static final Client DEFAULT_CLIENT = ClientBuilder.newBuilder().register(JacksonJsonProvider.class).build();

	private final Credentials credentials;
	private final EnvVars env;
	
	/**
	 * Constructor
	 * 
	 * @param credentials
	 * @param env
	 */
	public SparkNotifier(Credentials credentials, EnvVars env) {
		this.credentials = credentials;
		this.env = env;
	}
	
	/**
	 * Sends spark message
	 * 
	 * @param roomId
	 * @param message
	 * @param messageType
	 * @return
	 * @throws IOException
	 */
	public int sendMessage(String roomId, String message, SparkMessageType messageType) throws IOException {
		message = replaceEnvVars(message, env);

		SparkMessage messageData = new SparkMessageBuilder().roomId(roomId).message(message).messageType(messageType).build();

		Response response = DEFAULT_CLIENT.target(SPARK_MSG_POST_URL).request(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + getMachineAccountToken())
				.post(Entity.json(messageData));

		return response.getStatus();
	}
	
	/**
	 * Get bot token from secret text credential
	 * 
	 * @param credentials
	 * @return
	 * @throws SparkNotifyException
	 */
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

	/**
	 * Takes all env variables in message between ${ and }, then replaces with
	 * the value of env variable
	 * 
	 * @param message
	 * @param env
	 * @return
	 */
	private String replaceEnvVars(String message, EnvVars env) {
		Matcher matcher = ENV_PATTERN.matcher(message);
		while (matcher.find()) {
			String var = matcher.group(1);
			message = message.replace("${" + var + "}", env.get(var, ""));
		}
		
		return message;
	}
}
