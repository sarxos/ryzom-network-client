package com.github.sarxos.ryzom.network;

import java.io.Closeable;
import java.net.URI;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * This is Java client for LV-20 Ryzom Network, crafted by the Matis engineers after studies
 * performed on very ancient amber cubes found at the Megacorp SAT01-B satellite crash site near
 * Sandy Workshop of Aeden Aqueous.
 * 
 * @author Bartosz Firyn (sarxos)
 */
public class Lv20Client implements Closeable {

	/**
	 * The chat type.
	 */
	public static enum Chat {

		/**
		 * Universe chat.
		 */
		UNIVERSE(Lv20Socket.CHAT_UNIVERSE),

		/**
		 * English chat.
		 */
		ENGLISH(Lv20Socket.CHAT_EN),

		/**
		 * German chat.
		 */
		DEUTSCH(Lv20Socket.CHAT_DE),

		/**
		 * France chat.
		 */
		FRANCAIS(Lv20Socket.CHAT_FR);

		/**
		 * Chat type as defined in socket.
		 */
		final String id;

		private Chat(String id) {
			this.id = id;
		}

		public String getId() {
			return id;
		}
	}

	/**
	 * I'm the logger, babe!
	 */
	private static final Logger LOG = LoggerFactory.getLogger(Lv20Client.class);

	/**
	 * Chat service endpoint.
	 */
	private static final URI WS_URI = URI.create("ws://megacorp.io/sockjs/252/agrjomew/websocket");

	/**
	 * Used to generate unique client IDs.
	 */
	private static final AtomicInteger CLIENT_NUMBER = new AtomicInteger(0);

	/**
	 * The max amount of time the connect operation may take. The value is
	 * {@value #MAX_CONNECT_TIME} milliseconds.
	 */
	private static final int MAX_CONNECT_TIME = 5000;

	/**
	 * The max amount of time the login operation may take. The value is {@value #MAX_LOGIN_TIME}
	 * milliseconds.
	 */
	private static final int MAX_LOGIN_TIME = 5000;

	/**
	 * The max amount of time the logout operation may take. The value is {@value #MAX_LOGOUT_TIME}
	 * milliseconds. It's greater than other ones because logout may take long time to execute (have
	 * no idea why).
	 */
	private static final int MAX_LOGOUT_TIME = 15000;

	/**
	 * Threads factory used by the executor service.
	 */
	private final class ClientThreadFactory implements ThreadFactory {

		AtomicInteger threadNumber = new AtomicInteger(10000);

		@Override
		public Thread newThread(Runnable r) {
			Thread t = new Thread(r, "LV-20-" + clientId + "-thread-" + threadNumber.incrementAndGet());
			t.setDaemon(true);
			return t;
		}
	}

	/**
	 * Executor service for asynchronous messaging.
	 */
	private final ExecutorService executor = Executors.newCachedThreadPool(new ClientThreadFactory());

	/**
	 * Unique client ID.
	 */
	private final int clientId = CLIENT_NUMBER.incrementAndGet();

	/**
	 * WS client.
	 */
	private final WebSocketClient wsClient = new WebSocketClient(executor);

	/**
	 * The WS itself.
	 */
	private final Lv20Socket wsSocket = new Lv20Socket(executor);

	/**
	 * Connecting in progress.
	 */
	private final AtomicBoolean connecting = new AtomicBoolean(false);

	/**
	 * Connects to the LV-20 Ryzom Network chat service.
	 * 
	 * @return True if connection was established, false otherwise
	 */
	public boolean connect() {

		// connect only once at first invocation, every next call will return true

		if (!connecting.compareAndSet(false, true)) {
			return true;
		}

		// spoof some headers data

		ClientUpgradeRequest request = new ClientUpgradeRequest();
		request.addExtensions("permessage-deflate");
		request.getHeaders().put("Host", Arrays.asList("megacorp.io"));
		request.getHeaders().put("User-Agent", Arrays.asList("Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:39.0) Gecko/20100101 Firefox/39.0"));
		request.getHeaders().put("Origin", Arrays.asList("http://megacorp.io"));
		request.getHeaders().put("Accept", Arrays.asList("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"));
		request.getHeaders().put("Accept-Language", Arrays.asList("pl,en-US;q=0.7,en;q=0.3"));
		request.getHeaders().put("Accept-Encoding", Arrays.asList("gzip, deflate"));

		wsClient.setConnectTimeout(MAX_CONNECT_TIME);

		try {
			wsClient.start();
			wsClient.connect(wsSocket, WS_URI, request);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}

		// await for the 'open' message (server notifies us about the connection to server open and
		// ready to begin communication with)

		if (!await(wsSocket::isRyzomConnectionOpen, MAX_CONNECT_TIME)) {
			LOG.error("Unable to confirm connection in {} ms", MAX_CONNECT_TIME);
			return false;
		}

		// send 'connect' message

		executor.execute(wsSocket::msgConnect);

		// and await for the new session being established

		if (!await(wsSocket::isRyzomSessionEstablished, MAX_CONNECT_TIME)) {
			LOG.error("Unable to establish Ryzom session within {} ms", MAX_CONNECT_TIME);
			return false;
		}

		LOG.debug("Ryzom session ({}) is now established", wsSocket.getRyzomSession());

		// tell server i'm active

		executor.execute(wsSocket::msgMethodUserStatusActive);

		LOG.info("LV-20 Ryzom Network client is now connected");

		return true;
	}

	/**
	 * Login to LV-20 Ryzom Network.
	 * 
	 * @param name the toon name
	 * @param password the password
	 * @return True if user was logged in, false otherwise
	 */
	public boolean login(final String name, final String password) {

		// return false if not connected

		if (!wsSocket.isRyzomSessionEstablished()) {
			connect();
		}

		// send login message and loop awaiting for the response

		LOG.debug("Trying to log user {} with password {}", name, password);

		executor.execute((Runnable) () -> {
			wsSocket.msgMethodLogin(name, password);
		});

		boolean logged = await(wsSocket::isRyzomUserLoggedIn, MAX_LOGIN_TIME);

		if (logged) {
			LOG.info("User {} has been logged into the LV-20 Ryzom Network", name);
		} else {
			LOG.error("User {} was unable to login within {} ms", name, MAX_LOGIN_TIME);
		}

		return logged;
	}

	/**
	 * Logout from the LV-20 Ryzom Network and return true if logout was successful.
	 * 
	 * @return True if user was logged off, false otherwise
	 */
	public boolean logout() {

		if (!wsSocket.isRyzomUserLoggedIn()) {
			throw new IllegalStateException("Cannot logout because user is not logged in");
		}

		String username = wsSocket.getRyzomUserName();

		executor.execute(wsSocket::msgMethodLogout);

		if (!await(wsSocket::isRyzomUserLoggedOut, MAX_LOGOUT_TIME)) {
			LOG.error("Unable to logout user in {} ms", MAX_LOGIN_TIME);
			return false;
		}

		LOG.info("User {} has been logged out from the LV-20 Ryzom Network", username);

		return true;
	}

	/**
	 * Send private tell message to the given toon.
	 * 
	 * @param who the toon name
	 * @param text the message content
	 */
	public void tell(String who, String text) {

		if (who == null) {
			throw new IllegalArgumentException("Toon name must not be null!");
		}
		if (text == null) {
			throw new IllegalArgumentException("Text to be send must not be null!");
		}

		// ignore blank text messages (the blank messages are the ones that consist of the
		// whitespace characters only)

		if (StringUtil.isBlank(text)) {
			return;
		}

		if (!wsSocket.isRyzomUserLoggedIn()) {
			throw new IllegalStateException("Cannot send tell because user is not logged in");
		}

		executor.execute((Runnable) () -> {
			wsSocket.msgMethodChat(Lv20Socket.CHAT_TELL, who.trim() + " " + text.trim());
		});
	}

	/**
	 * Send message to the given chat.
	 * 
	 * @param chat the chat type
	 * @param text the message content
	 */
	public void send(Chat chat, String text) {

		if (chat == null) {
			throw new IllegalArgumentException("Chat type must not be null!");
		}
		if (text == null) {
			throw new IllegalArgumentException("Text to be send must not be null!");
		}

		// ignore blank text messages (the blank messages are the ones that consist of the
		// whitespace characters only)

		if (StringUtil.isBlank(text)) {
			return;
		}

		executor.execute((Runnable) () -> {
			wsSocket.msgMethodChat(chat.getId(), text.trim());
		});
	}

	/**
	 * Await until the supplier method return true.
	 * 
	 * @param method the supplier method
	 * @param timeout the max time to wait (in milliseconds)
	 * @return True if supplier method returned true,false if timeout occurred
	 */
	private boolean await(Supplier<Boolean> method, int timeout) {

		int delay = 100;
		int milliseconds = 0;

		while (!method.get()) {
			try {
				Thread.sleep(delay);
			} catch (InterruptedException e) {
				return false;
			}
			if ((milliseconds += delay) >= timeout) {
				return false;
			}
		}

		return true;
	}

	/**
	 * Logout from the LV-20 Ryzom Network and disconnects from chat service. This method do nothing
	 * if user has not been logged in.
	 */
	@Override
	public void close() {

		if (!connecting.compareAndSet(true, false)) {
			return;
		}

		if (wsSocket.isRyzomUserLoggedIn()) {
			logout();
		}

		try {
			wsClient.stop();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}

		LOG.info("LV-20 Ryzom Network client has been disconnected");
	}
}
