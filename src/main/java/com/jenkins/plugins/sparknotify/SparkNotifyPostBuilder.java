package com.jenkins.plugins.sparknotify;

import static com.cloudbees.plugins.credentials.CredentialsMatchers.firstOrNull;
import static com.cloudbees.plugins.credentials.CredentialsMatchers.withId;
import static com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentials;

import java.io.IOException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.ws.rs.core.Response.Status;

import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractDescribableImpl;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Descriptor;
import hudson.model.Job;
import hudson.security.ACL;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.ListBoxModel.Option;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;

public class SparkNotifyPostBuilder extends Recorder {
	private static final String JOB_FAILURE = "FAILURE";
	private static final String JOB_SUCCESS = "SUCCESS";
	private static final String JOB_ABORTED = "ABORTED";
	private static final String JOB_UNSTABLE = "UNSTABLE";

	private List<SparkRoom> roomList;
	private final boolean disable;
	private final boolean skipOnFailure;
	private final boolean skipOnSuccess;
	private final boolean skipOnAborted;
	private final boolean skipOnUnstable;
	private String message;
	private String messageType;
	private String publishContent;
	private String credentialsId;

	public static final class SparkRoom extends AbstractDescribableImpl<SparkRoom> {
		private final String rName;
		private final String rId;

		public String getRName() {
			return rName;
		}

		public String getRId() {
			return rId;
		}

		@DataBoundConstructor
		public SparkRoom(final String rName, final String rId) {
			this.rName = rName;
			this.rId = rId;
		}

		@Extension
		public static class DescriptorImpl extends Descriptor<SparkRoom> {
			@Override
			public String getDisplayName() {
				return "";
			}
		}
	}

	/**
	 * Constructor
	 */
	@DataBoundConstructor
	public SparkNotifyPostBuilder(final boolean disable, final boolean skipOnFailure, final boolean skipOnSuccess, final boolean skipOnAborted, final boolean skipOnUnstable,
			final String publishContent, final String messageType, final List<SparkRoom> roomList, final String credentialsId) {
		this.disable = disable;
		this.skipOnFailure = skipOnFailure;
		this.skipOnSuccess = skipOnSuccess;
		this.skipOnAborted = skipOnAborted;
		this.skipOnUnstable = skipOnUnstable;
		this.publishContent = publishContent;
		this.messageType = messageType;
		this.roomList = roomList;
		this.credentialsId = credentialsId;
	}

	public String getPublishContent() {
		return publishContent;
	}

	@DataBoundSetter
	public void setPublishContent(final String publishContent) {
		this.publishContent = publishContent;
	}

	public String getMessageType() {
		return messageType;
	}

	public boolean isDisable() {
		return disable;
	}

	public boolean isSkipOnFailure() {
		return skipOnFailure;
	}

	public boolean isSkipOnSuccess() {
		return skipOnSuccess;
	}

	public boolean isSkipOnAborted() {
		return skipOnAborted;
	}

	public boolean isSkipOnUnstable() {
		return skipOnUnstable;
	}

	public List<SparkRoom> getRoomList() {
		if (roomList == null) {
			roomList = new ArrayList<SparkRoom>();
		}
		return roomList;
	}

	public String getCredentialsId() {
		return credentialsId;
	}

	@DataBoundSetter
	public void setCredentialsId(final String credentialsId) {
		this.credentialsId = Util.fixEmpty(credentialsId);
	}

	/**
	 * @see hudson.tasks.BuildStepCompatibilityLayer#perform(hudson.model.AbstractBuild,
	 *      hudson.Launcher, hudson.model.BuildListener)
	 *
	 */
	@Override
	public boolean perform(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener)
			throws InterruptedException, IOException {
		if (disable) {
			listener.getLogger().println("Spark Notify Plugin Disabled!");
			return true;
		}

		EnvVars envVars = build.getEnvironment(listener);

		message = getPublishContent();
		if (!SparkMessage.isMessageValid(message)) {
			listener.getLogger().println("Skipping Spark notifications because no message was defined");
			return true;
		}

		// Little hack to replace the build result with env variable
		String result = build.getResult().toString();
		if (result != null && !result.toString().isEmpty()) {
			message = message.replace("${BUILD_RESULT}", result);
		} else {
			listener.getLogger().println("Could not get result");
			result = "";
		}

		if (skipOnSuccess && result.equals(JOB_SUCCESS)) {
			listener.getLogger().println("Skipping Spark notifications because job was successful");
			return true;
		}
		if (skipOnFailure && result.equals(JOB_FAILURE)) {
			listener.getLogger().println("Skipping Spark notifications because job failed");
			return true;
		}
		if (skipOnAborted && result.equals(JOB_ABORTED)) {
			listener.getLogger().println("Skipping Spark notifications because job was aborted");
			return true;
		}
		if (skipOnUnstable && result.equals(JOB_UNSTABLE)) {
			listener.getLogger().println("Skipping Spark notifications because job is unstable");
			return true;
		}

		if (messageType == null || messageType.isEmpty()) {
			messageType = "text";
		}

		if (roomList == null || roomList.isEmpty()) {
			listener.getLogger().println("Skipping Spark notifications because no rooms were defined");
			return true;
		}

		SparkMessageType sparkMessageType = SparkMessageType.valueOf(messageType.toUpperCase());

		SparkNotifier notifier = new SparkNotifier(getCredentials(credentialsId), envVars);

		boolean isProblemSendingMessage = false;

		for (int k = 0; k < roomList.size(); k++) {
			listener.getLogger().println("Sending message to Spark Room: " + roomList.get(k).getRId());
			try {
				int responseCode = notifier.sendMessage(roomList.get(k).getRId(), message, sparkMessageType);
				if (responseCode != Status.OK.getStatusCode()) {
					listener.getLogger().println("Could not post message, response code: " + responseCode);
					isProblemSendingMessage = true;
				}
			} catch (SocketException e) {
				listener.getLogger().println("Could not post message because Spark API server did not provide a response; This is likely intermittent");
				isProblemSendingMessage = true;
			} catch (SparkNotifyException e) {
				listener.getLogger().println("Could not post message because token could not be generated, did you select the right credential?");
				isProblemSendingMessage = true;
			} catch (RuntimeException e) {
				listener.getLogger().println("Could not post message because of an unknown issue, please contact the Administrators");
				isProblemSendingMessage = true;
			}
		}

		if (isProblemSendingMessage) {
			listener.getLogger().println("Issues occured posting messages");
		} else {
			listener.getLogger().println("Spark messages posted successfully");
		}

		return true;
	}

	@Override
	public BuildStepMonitor getRequiredMonitorService() {
		return BuildStepMonitor.NONE;
	}

	@Override
	public boolean needsToRunAfterFinalized() {
		// This is here to ensure that the reported build status is actually
		// correct. If we were to return false here,
		// other build plugins could still modify the build result, making the
		// sent out Spark notification incorrect.
		return true;
	}

	// Overridden for better type safety.
	@Override
	public SparkNotifyPostBuilderDescriptor getDescriptor() {
		return (SparkNotifyPostBuilderDescriptor) super.getDescriptor();
	}

	@Extension
	public static final class SparkNotifyPostBuilderDescriptor extends BuildStepDescriptor<Publisher> {
		/**
		 * Constructor
		 */
		public SparkNotifyPostBuilderDescriptor() {
			super(SparkNotifyPostBuilder.class);
			load();
		}

		/**
		 * @see hudson.model.Descriptor#configure(org.kohsuke.stapler.StaplerRequest,
		 *      net.sf.json.JSONObject)
		 */
		@Override
		public boolean configure(final StaplerRequest req, final JSONObject formData) throws FormException {
			save();
			return true;
		}

		public FormValidation doMessageCheck(@QueryParameter final String message) {
			if (SparkMessage.isMessageValid(message)) {
				return FormValidation.ok();
			} else {
				return FormValidation.error("Message cannot be null");
			}
		}

		public FormValidation doRoomIdCheck(@QueryParameter final String roomId) {
			if (SparkMessage.isRoomIdValid(roomId)) {
				return FormValidation.ok();
			} else {
				return FormValidation.error("Invalid Room Id; See help message");
			}
		}

		/**
		 * @see hudson.tasks.BuildStepDescriptor#isApplicable(java.lang.Class)
		 */
		@SuppressWarnings("rawtypes")
		@Override
		public boolean isApplicable(final Class<? extends AbstractProject> jobType) {
			return true;
		}

		public ListBoxModel doFillCredentialsIdItems(@AncestorInPath final Job<?, ?> project, @QueryParameter final String serverURI) {
			return new StandardListBoxModel()
					.withEmptySelection()
					.withMatching(CredentialsMatchers.instanceOf(StringCredentials.class),
							CredentialsProvider.lookupCredentials(StringCredentials.class, project, ACL.SYSTEM, URIRequirementBuilder.fromUri(serverURI).build()));
		}

		public ListBoxModel doFillMessageTypeItems(@QueryParameter final String messageType) {
			return new ListBoxModel(new Option("text", "text", messageType.matches("text")),
					new Option("markdown", "markdown", messageType.matches("markdown")),
					new Option("html", "html", messageType.matches("html")));
		}

		/**
		 * @see hudson.model.Descriptor#getDisplayName()
		 */
		@Override
		public String getDisplayName() {
			return "Notify Spark Rooms";
		}
	}

	private Credentials getCredentials(final String credentialsId) {
		return firstOrNull(
				lookupCredentials(
						Credentials.class,
						Jenkins.getInstance(),
						ACL.SYSTEM,
						Collections.<DomainRequirement> emptyList()),
				withId(credentialsId));
	}
}
