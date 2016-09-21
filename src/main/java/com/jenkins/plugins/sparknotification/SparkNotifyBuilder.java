package com.jenkins.plugins.sparknotification;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
import hudson.model.BuildListener;
import hudson.model.Descriptor;
import hudson.model.Job;
import hudson.security.ACL;
import hudson.model.AbstractBuild;
import hudson.model.AbstractDescribableImpl;
import hudson.model.AbstractProject;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.ListBoxModel.Option;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import java.io.IOException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import javax.ws.rs.core.Response.Status;

import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder; 
import static com.cloudbees.plugins.credentials.CredentialsMatchers.withId; 
import static com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentials; 
import static com.cloudbees.plugins.credentials.CredentialsMatchers.firstOrNull;

public class SparkNotifyBuilder extends Builder {

	private List<SparkRoom> roomList;
	private boolean disable;
	private String message;
	private String messageType;
	private String publishContent;
	private String credentialsId;

	public static final class SparkRoom extends AbstractDescribableImpl<SparkRoom> {
		private String rName;
		private String rId;

		public String getRName() {
			return rName;
		}

		public String getRId() {
			return rId;
		}

		@DataBoundConstructor
		public SparkRoom(String rName, String rId) {
			this.rName = rName;
			this.rId = rId;
		}

		@Extension
		public static class DescriptorImpl extends Descriptor<SparkRoom> {
			public String getDisplayName() {
				return "";
			}
		}
	}

	/**
	 * Constructor
	 */
	@DataBoundConstructor
	public SparkNotifyBuilder(boolean disable, String publishContent, String messageType, List<SparkRoom> roomList, String credentialsId) {
		this.disable = disable;
		this.publishContent = publishContent;
		this.messageType = messageType;
		this.roomList = roomList;
		this.credentialsId = credentialsId;
	}

	public String getPublishContent() {
		return publishContent;
	}

	@DataBoundSetter
	public void setPublishContent(String publishContent) {
		this.publishContent = publishContent;
	}

	public String getMessageType() {
		return messageType;
	}

	public boolean isDisable() {
		return disable;
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
	public void setCredentialsId(String credentialsId) {
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
		if (message == null || message.isEmpty()) {
			listener.getLogger().println("Skipping Spark notifications because no message was defined");
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
		return BuildStepMonitor.BUILD;
	}

	// Overridden for better type safety.
	@Override
	public SparkNotifyBuilderDescriptor getDescriptor() {
		return (SparkNotifyBuilderDescriptor) super.getDescriptor();
	}

	@Extension
	public static final class SparkNotifyBuilderDescriptor extends BuildStepDescriptor<Builder> {
		/**
		 * Constructor
		 */
		public SparkNotifyBuilderDescriptor() {
			super(SparkNotifyBuilder.class);
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
		
		public FormValidation doMessageCheck(@QueryParameter String message) {
			if (SparkMessage.isMessageValid(message)) {
				return FormValidation.ok();
			} else {
				return FormValidation.error("Message cannot be null");
			}
		}
			
		public FormValidation doRoomIdCheck(@QueryParameter String roomId) {
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

		public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Job<?, ?> project, @QueryParameter String serverURI) {
			return new StandardListBoxModel()
					.withEmptySelection()
					.withMatching(CredentialsMatchers.instanceOf(StringCredentials.class),
							CredentialsProvider.lookupCredentials(StringCredentials.class, project, ACL.SYSTEM, URIRequirementBuilder.fromUri(serverURI).build()));
		}

		public ListBoxModel doFillMessageTypeItems(@QueryParameter String messageType) {
			return new ListBoxModel(new Option("text", "text", messageType.matches("text")),
					new Option("markup", "markup", messageType.matches("markup")),
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

	private Credentials getCredentials(String credentialsId) { 
        return firstOrNull( 
                lookupCredentials( 
                        Credentials.class, 
                        Jenkins.getInstance(), 
                        ACL.SYSTEM, 
                        Collections.<DomainRequirement>emptyList() 
                ), 
                withId(credentialsId) 
        ); 
	}
}
