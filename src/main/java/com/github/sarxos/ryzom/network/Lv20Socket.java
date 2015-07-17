package com.github.sarxos.ryzom.network;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;


/**
 * LV-20 Ryzom Network WebSocket. The messages in this class has been reverse-engineered from the chat service traffic.
 * 
 * @author Bartosz Firyn (sarxos)
 */
@WebSocket(maxTextMessageSize = 64 * 1024)
public class Lv20Socket {

	/**
	 * I'm the logger.
	 */
	private static final Logger LOG = LoggerFactory.getLogger(Lv20Socket.class);

	/**
	 * I'm the traffic logger.
	 */
	private static final Logger LOG_TRAFFIC = LoggerFactory.getLogger(Lv20Socket.class.getName() + ".traffic");

	private static final String HMAC_SHA1_ALGORITHM = "HmacSHA1";
	private static final String HMAC_SHA1_KEY = UUID.randomUUID().toString();
	private static final ObjectMapper MAPPER = new ObjectMapper();

	// chat IDs

	public static final String CHAT_TELL = "tell";
	public static final String CHAT_UNIVERSE = "all";
	public static final String CHAT_EN = "en";
	public static final String CHAT_DE = "de";
	public static final String CHAT_FR = "fr";

	/**
	 * Handle payload with 'o' prefix (server telling us that connection is open).
	 * 
	 * @author Bartosz Firyn (sarxos)
	 */
	private final class PrefixHandlerO extends Lv20PrefixHandler {

		public PrefixHandlerO() {
			super('o');
		}

		@Override
		protected void handle(String message) {
			ryzomConnectionOpen = true;
		}
	}

	/**
	 * Handle heartbeat (just log it).
	 * 
	 * @author Bartosz Firyn (sarxos)
	 */
	private final class PrefixHandlerH extends Lv20PrefixHandler {

		public PrefixHandlerH() {
			super('h');
		}

		@Override
		protected void handle(String message) {
			LOG.debug("Heartbeat");
		}
	}

	/**
	 * Handle 'a' prefix (this is when server answer to the payload we've sent).
	 *
	 * @author Bartosz Firyn (sarxos)
	 */
	private final class PrefixHandlerA extends Lv20PrefixHandler {

		public PrefixHandlerA() {
			super('a');
		}

		@Override
		protected void handle(String message) {
			process(message);
		}
	}

	/**
	 * Process 'connected' response from server.
	 * 
	 * @author Bartosz Firyn (sarxos)
	 */
	private final class MessageProcessorConnected extends Lv20MessageHandler {

		public MessageProcessorConnected() {
			super("connected");
		}

		@Override
		protected boolean test(Map<String, Object> message) {
			return path(message, "session") != null;
		}

		@Override
		protected void handle(Map<String, Object> message) {
			ryzomSession = path(message, "session");
		}
	}

	/**
	 * Process 'ping' request from server.
	 * 
	 * @author Bartosz Firyn (sarxos)
	 */
	private final class MessageProcessorPing extends Lv20MessageHandler {

		public MessageProcessorPing() {
			super("ping");
		}

		@Override
		protected boolean test(Map<String, Object> message) {
			return true;
		}

		@Override
		protected void handle(Map<String, Object> message) {
			executor.execute(Lv20Socket.this::msgPong);
		}
	}

	/**
	 * Process 'added' response from the server. This happen when user is logged in.
	 * 
	 * @author Bartosz Firyn (sarxos)
	 */
	private final class MessageProcessorUsersAdded extends Lv20MessageHandler {

		// a["{\"msg\":\"added\",\"collection\":\"users\",\"id\":\"RN62wtdTrNjjDEFrP\",\"fields\":{\"profile\":{\"lang\":\"en\",\"email\":\"pianola@mailinator.com\",\"created_at\":1436995355},\"username\":\"Jenamessenger\"}}"]

		public MessageProcessorUsersAdded() {
			super("added");
		}

		@Override
		protected boolean test(Map<String, Object> message) {
			return collection(message, "users") && path(message, "fields/profile/email") != null && path(message, "fields/username") != null;
		}

		@Override
		protected void handle(Map<String, Object> message) {

			ryzomUserResourceId = path(message, "id");
			ryzomUserEmail = path(message, "fields/profile/email");
			ryzomUserName = path(message, "fields/username");

			executor.execute(Lv20Socket.this::msgSubMeteorAutoupdateClientVersions);
			executor.execute(Lv20Socket.this::msgSubMeteorLoginServiceConfiguration);
			executor.execute(Lv20Socket.this::msgSubIntercomHash);
			// executor.execute(Lv20Socket.this::msgSubI18n);
			executor.execute(Lv20Socket.this::msgSubLatestUniversChats);
			executor.execute(Lv20Socket.this::msgSubLatestTellChats);
			executor.execute(Lv20Socket.this::msgSubNextEvents);
			// executor.execute(Lv20Socket.this::msgSubGlobals);
			executor.execute(Lv20Socket.this::msgSubUserData);

		}
	}

	/**
	 * Process 'changed' response from the server. This happen when user data on server is modified.
	 * 
	 * @author Bartosz Firyn (sarxos)
	 */
	private final class MessageProcessorUsersChanged extends Lv20MessageHandler {

		// a["{\"msg\":\"changed\",\"collection\":\"users\",\"id\":\"RN62wtdTrNjjDEFrP\",\"fields\":{\"game\":{\"c60\":16977,\"cid\":11962608,\"guildId\":0,\"priv\":\"\"},\"status\":{\"lastLogin\":{\"$date\":1437165554271},\"online\":true}}}"]

		public MessageProcessorUsersChanged() {
			super("changed");
		}

		@Override
		protected boolean test(Map<String, Object> message) {
			boolean valid = true;
			valid &= collection(message, "users");
			valid &= path(message, "id").equals(ryzomUserResourceId);
			valid &= path(message, "fields/game/cid") != null;
			valid &= path(message, "fields/game/guildId") != null;
			return valid;
		}

		@Override
		protected void handle(Map<String, Object> message) {

			if (ryzomUserCid == -1 && ryzomUserGuildId == -1) {
				executor.execute(Lv20Socket.this::msgSubLatestGuidChats);
			}

			ryzomUserCid = path(message, "fields/game/cid");
			ryzomUserGuildId = path(message, "fields/game/guildId");
		}
	}

	/**
	 * Process 'removed' response from the server. This happen when user logs out.
	 * 
	 * @author Bartosz Firyn (sarxos)
	 */
	private final class MessageProcessorUsersRemoved extends Lv20MessageHandler {

		// a["{\"msg\":\"removed\",\"collection\":\"users\",\"id\":\"RN62wtdTrNjjDEFrP\"}"]

		public MessageProcessorUsersRemoved() {
			super("removed");
		}

		@Override
		protected boolean test(Map<String, Object> message) {
			return collection(message, "users") && path(message, "id") != null;
		}

		@Override
		protected void handle(Map<String, Object> message) {

			if (!path(message, "id").equals(ryzomUserResourceId)) {
				throw new IllegalStateException("Invalid user resource ID received on logout, exoected to be " + ryzomUserResourceId);
			}

			ryzomUserName = null;
			ryzomUserEmail = null;
			ryzomUserResourceId = null;
			ryzomUserCid = -1;
			ryzomUserGuildId = -1;
		}
	}

	private final ExecutorService executor;
	private final AtomicInteger number = new AtomicInteger(0);
	private final List<Lv20PrefixHandler> prefixHandlers = new LinkedList<>();
	private final List<Lv20MessageHandler> messageHandlers = new LinkedList<>();

	private volatile Session session;

	private volatile boolean ryzomConnectionOpen = false;
	private volatile String ryzomSession;
	private volatile String ryzomUserName;
	private volatile String ryzomUserEmail;
	private volatile String ryzomUserResourceId;
	private volatile int ryzomUserGuildId = -1;
	private volatile int ryzomUserCid = -1;
	private volatile long timestamp;

	public Lv20Socket(ExecutorService executor) {

		this.executor = executor;

		addPrefixHandler(new PrefixHandlerO());
		addPrefixHandler(new PrefixHandlerH());
		addPrefixHandler(new PrefixHandlerA());

		addMessageProcessor(new MessageProcessorConnected());
		addMessageProcessor(new MessageProcessorPing());
		addMessageProcessor(new MessageProcessorUsersAdded());
		addMessageProcessor(new MessageProcessorUsersChanged());
		addMessageProcessor(new MessageProcessorUsersRemoved());
	}

	public void addPrefixHandler(Lv20PrefixHandler handler) {
		prefixHandlers.add(handler);
	}

	public void addMessageProcessor(Lv20MessageHandler processor) {
		messageHandlers.add(processor);
	}

	@OnWebSocketClose
	public void onClose(int statusCode, String reason) {
		LOG.debug("Ryzom WebSocket is now closed");
		session = null;
	}

	@OnWebSocketConnect
	public void onConnect(Session session) throws IOException {

		LOG.debug("Ryzom WebSocket is now connected");
		LOG.trace("Session is {}", session);

		this.session = session;
		this.timestamp = System.currentTimeMillis();
	}

	@OnWebSocketMessage
	public void onMessage(String message) {
		try {
			receive(message);
		} catch (Exception e) {
			LOG.error(e.getMessage(), e);
			throw e;
		}
	}

	public boolean isRyzomConnectionOpen() {
		return ryzomConnectionOpen;
	}

	public boolean isRyzomSessionEstablished() {
		return ryzomSession != null;
	}

	public String getRyzomSession() {
		return ryzomSession;
	}

	public String getRyzomUserName() {
		return ryzomUserName;
	}

	public int getRyzomUserGuildId() {
		return ryzomUserGuildId;
	}

	public int getRyzomUserCid() {
		return ryzomUserCid;
	}

	public String getRyzomUserEmail() {
		return ryzomUserEmail;
	}

	public boolean isRyzomUserLoggedIn() {
		boolean login = true;
		login &= ryzomUserName != null;
		login &= ryzomUserEmail != null;
		login &= ryzomUserResourceId != null;
		return login;
	}

	public boolean isRyzomUserLoggedOut() {
		boolean logout = true;
		logout &= ryzomUserName == null;
		logout &= ryzomUserEmail == null;
		logout &= ryzomUserResourceId == null;
		return logout;
	}

	private void send(Map<String, Object> map) {

		// null session can happen only when client is started but socket not yet connected, just
		// wait for the session to appear

		while (session == null) {

			LOG.trace("No session, loop awaiting");

			try {
				Thread.sleep(10);
			} catch (InterruptedException e) {
				return;
			}
		}

		String message = serialize(map);

		LOG_TRAFFIC.trace("[-->]: {}", message);

		synchronized (session) {
			if (session.isOpen()) {
				try {
					session.getRemote().sendString(message);
				} catch (IOException e) {
					throw new IllegalStateException(e);
				}
			} else {
				throw new IllegalStateException("Ooops, the session is not open");
			}
		}
	}

	private void receive(String payload) {

		Character prefix = payload.charAt(0);
		String message = payload.substring(1);

		LOG_TRAFFIC.trace("[<-{}]: {}", prefix, message);

		long count = prefixHandlers
			.stream()
			.filter(handler -> prefix.equals(handler.getPrefix()))
			.peek(handler -> handler.handle(message))
			.count();

		if (count == 0) {
			throw new IllegalStateException("Unhandled prefix " + prefix);
		}
	}

	private void process(String message) {

		Map<String, Object> map = unserialize(message);

		long count = messageHandlers
			.stream()
			.filter(processor -> processor.matches(map))
			.peek(processor -> processor.handle(map))
			.count();

		if (count == 0) {
			LOG.debug("Message has not been processed: {}", map);
		}
	}

	/**
	 * Serialize map to array of escaped JSON strings where every JSON represents map passed as the
	 * argument.
	 * 
	 * @param map the map
	 * @return Message JSON string
	 */
	private static String serialize(Map<String, Object> map) {
		try {
			return MAPPER.writeValueAsString(Arrays.asList(MAPPER.writeValueAsString(map)));
		} catch (JsonProcessingException e) {
			throw new IllegalStateException(e);
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> unserialize(String message) {

		Map<String, Object> map = null;
		String msg = null;

		try {
			msg = (String) MAPPER.readValue(message, ArrayList.class).get(0);
			map = MAPPER.readValue(msg, HashMap.class);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}

		return map;
	}

	/**
	 * @return Next numeric ID
	 */
	private String idnum() {
		return Integer.toString(number.incrementAndGet());
	}

	/**
	 * @return Unique ID
	 */
	private static String identity() {
		SecretKeySpec signingKey = new SecretKeySpec(HMAC_SHA1_KEY.getBytes(), HMAC_SHA1_ALGORITHM);
		try {
			Mac mac = Mac.getInstance(HMAC_SHA1_ALGORITHM);
			mac.init(signingKey);
			byte[] hmac = mac.doFinal(UUID.randomUUID().toString().getBytes());
			return new String(Base64.getEncoder().encode(hmac), "UTF8")
				.substring(0, 17)
				.replaceAll("/", "a")
				.replaceAll("\\+", "b")
				.replaceAll("=", "c");
		} catch (Exception e) {
			throw new IllegalStateException("Failed to generate HMAC", e);
		}
	}

	// ---------------------- messages --------------------------

	// ["{\"msg\":\"connect\",\"version\":\"pre2\",\"support\":[\"pre2\",\"pre1\"]}"]

	protected final void msgConnect() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("msg", "connect");
		map.put("version", "pre2");
		map.put("support", new Object[] { "pre2", "pre1" });
		send(map);
	}

	// ["{\"msg\":\"sub\",\"id\":\"9vywbKZowez7Ks6vA\",\"name\":\"meteor_autoupdate_clientVersions\",\"params\":[],\"route\":null}"]

	protected final void msgSubMeteorAutoupdateClientVersions() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("msg", "sub");
		map.put("id", identity());
		map.put("name", "meteor_autoupdate_clientVersions");
		map.put("params", new Object[0]);
		map.put("route", null);
		send(map);
	}

	// ["{\"msg\":\"sub\",\"id\":\"JGQrEBLyFknkeK64A\",\"name\":\"meteor.loginServiceConfiguration\",\"params\":[],\"route\":null}"]

	protected final void msgSubMeteorLoginServiceConfiguration() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("msg", "sub");
		map.put("id", identity());
		map.put("name", "meteor.loginServiceConfiguration");
		map.put("params", new Object[0]);
		map.put("route", null);
		send(map);
	}

	// ["{\"msg\":\"sub\",\"id\":\"84vcaioKamcKuK82E\",\"name\":\"intercomHash\",\"params\":[],\"route\":null}"]

	protected final void msgSubIntercomHash() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("msg", "sub");
		map.put("id", identity());
		map.put("name", "intercomHash");
		map.put("params", new Object[0]);
		map.put("route", null);
		send(map);
	}

	// ["{\"msg\":\"sub\",\"id\":\"MPSvr8wrD8CDyZESh\",\"name\":\"i18n\",\"params\":[\"en\"],\"route\":null}"]

	protected final void msgSubI18n() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("msg", "sub");
		map.put("id", identity());
		map.put("name", "i18n");
		map.put("params", new Object[] { "en" });
		map.put("route", null);
		send(map);
	}

	// ["{\"msg\":\"sub\",\"id\":\"t2YqbWuvRRxQEqun7\",\"name\":\"latestUniversChats\",\"params\":[],\"route\":null}"]

	protected final void msgSubLatestUniversChats() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("msg", "sub");
		map.put("id", identity());
		map.put("name", "latestUniversChats");
		map.put("params", new Object[0]);
		map.put("route", null);
		send(map);
	}

	// ["{\"msg\":\"sub\",\"id\":\"WLtjFM5KYC7ByatBh\",\"name\":\"latestTellChats\",\"params\":[],\"route\":null}"]

	protected final void msgSubLatestTellChats() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("msg", "sub");
		map.put("id", identity());
		map.put("name", "latestTellChats");
		map.put("params", new Object[0]);
		map.put("route", null);
		send(map);
	}

	// ["{\"msg\":\"sub\",\"id\":\"x25XiqYmop7CNEemG\",\"name\":\"latestGuildChats\",\"params\":[0],\"route\":null}"]

	protected final void msgSubLatestGuidChats() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("msg", "sub");
		map.put("id", identity());
		map.put("name", "latestGuildChats");
		map.put("params", new Object[] { ryzomUserGuildId });
		map.put("route", null);
		send(map);
	}

	// ["{\"msg\":\"sub\",\"id\":\"Y4n58s8S65miP6Bdg\",\"name\":\"nextEvents\",\"params\":[],\"route\":null}"]

	protected final void msgSubNextEvents() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("msg", "sub");
		map.put("id", identity());
		map.put("name", "nextEvents");
		map.put("params", new String[0]);
		map.put("route", null);
		send(map);
	}

	// ["{\"msg\":\"sub\",\"id\":\"v7SwezbiAHzCNmmJJ\",\"name\":\"documentsUnlocked\",\"params\":[\"en\",null],\"route\":null}"]

	protected final void msgSubDocumentsUnlocked() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("msg", "sub");
		map.put("id", identity());
		map.put("name", "documentsUnlocked");
		map.put("params", new String[] { "en", null });
		map.put("route", null);
		send(map);
	}

	// ["{\"msg\":\"sub\",\"id\":\"neRAZv67km2BawjqN\",\"name\":\"documents\",\"params\":[],\"route\":null}"]

	protected final void msgSubDocuments() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("msg", "sub");
		map.put("id", identity());
		map.put("name", "documents");
		map.put("params", new String[0]);
		map.put("route", null);
		send(map);
	}

	// ["{\"msg\":\"sub\",\"id\":\"yxwv2mHqdGiKGakW9\",\"name\":\"documentsUnlocking\",\"params\":[],\"route\":null}"]

	protected final void msgSubDocumentsUnlocking() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("msg", "sub");
		map.put("id", identity());
		map.put("name", "documentsUnlocking");
		map.put("params", new String[0]);
		map.put("route", null);
		send(map);
	}

	// ["{\"msg\":\"sub\",\"id\":\"FH83NmYp7fGxLZto3\",\"name\":\"globals\",\"params\":[],\"route\":null}"]

	protected final void msgSubGlobals() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("msg", "sub");
		map.put("id", identity());
		map.put("name", "globals");
		map.put("params", new String[0]);
		map.put("route", null);
		send(map);
	}

	// ["{\"msg\":\"sub\",\"id\":\"NvZL5PCaxiL38gHov\",\"name\":\"ladders\",\"params\":[],\"route\":null}"]

	protected final void msgSubLadders() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("msg", "sub");
		map.put("id", identity());
		map.put("name", "ladders");
		map.put("params", new String[0]);
		map.put("route", null);
		send(map);
	}

	// ["{\"msg\":\"sub\",\"id\":\"cWsHpRxdAhYCGfCX2\",\"name\":\"laddersMine\",\"params\":[],\"route\":null}"]

	protected final void msgSubLaddersMine() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("msg", "sub");
		map.put("id", identity());
		map.put("name", "laddersMine");
		map.put("params", new String[0]);
		map.put("route", null);
		send(map);
	}

	// ["{\"msg\":\"sub\",\"id\":\"yq8iBQWRFRT6PG2mo\",\"name\":\"userData\",\"params\":[],\"route\":null}"]

	protected final void msgSubUserData() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("msg", "sub");
		map.put("id", identity());
		map.put("name", "userData");
		map.put("params", new String[0]);
		map.put("route", null);
		send(map);
	}

	// ["{\"msg\":\"pong\"}"]

	protected final void msgPong() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("msg", "pong");
		send(map);
	}

	// ["{\"msg\":\"method\",\"method\":\"user-status-idle\",\"params\":[1436980809850],\"id\":\"3\"}"]

	protected final void msgMethodUserStatusIdle() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("msg", "method");
		map.put("method", "user-status-idle");
		map.put("params", new long[] { timestamp });
		map.put("id", idnum());
		send(map);
	}

	// ["{\"msg\":\"method\",\"method\":\"user-status-active\",\"params\":[1436980809850],\"id\":\"2\"}"]

	protected final void msgMethodUserStatusActive() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("msg", "method");
		map.put("method", "user-status-active");
		map.put("params", new long[] { timestamp });
		map.put("id", idnum());
		send(map);
	}

	// ["{\"msg\":\"method\",\"method\":\"login\",\"params\":[{\"ryzom\":true,\"username\":\"the-user-name\",\"password\":\"the-user-passwd\",\"lang\":\"en\"}],\"id\":\"1\"}"]

	protected final void msgMethodLogin(String username, String password) {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("ryzom", true);
		params.put("username", username);
		params.put("password", password);
		params.put("lang", "en");
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("msg", "method");
		map.put("method", "login");
		map.put("params", Arrays.asList(params));
		map.put("id", idnum());
		send(map);
	}

	// ["{\"msg\":\"method\",\"method\":\"logout\",\"params\":[],\"id\":\"3\"}"]

	protected final void msgMethodLogout() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("msg", "method");
		map.put("method", "logout");
		map.put("params", new Object[0]);
		map.put("id", idnum());
		send(map);
	}

	// ["{\"msg\":\"method\",\"method\":\"chat\",\"params\":[\"all\",\"How are you?\"],\"id\":\"15\"}"]

	protected final void msgMethodChat(String chat, String text) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("msg", "method");
		map.put("method", "chat");
		map.put("params", new String[] { chat, text });
		map.put("id", idnum());
		send(map);
	}

	// ["{\"msg\":\"method\",\"method\":\"chat\",\"params\":[\"tell\",\"Kopeas bubu\"],\"id\":\"7\"}"]

}