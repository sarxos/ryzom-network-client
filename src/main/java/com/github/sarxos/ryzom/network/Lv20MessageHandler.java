package com.github.sarxos.ryzom.network;

import java.util.Map;


/**
 * Handle message of given type from server.
 * 
 * @author Bartosz Firyn (sarxos)
 */
public abstract class Lv20MessageHandler {

	/**
	 * The message type.
	 */
	private final String type;

	/**
	 * @param type the message type
	 */
	public Lv20MessageHandler(String type) {
		this.type = type;
	}

	/**
	 * @return Message type
	 */
	public String getType() {
		return type;
	}

	/**
	 * Invoked from {@link Lv20Socket} to filter handlers.
	 * 
	 * @param message the message
	 * @return True if this handler matches the message
	 */
	protected boolean matches(Map<String, Object> message) {
		try {
			if (message.get("msg").equals(type)) {
				return test(message);
			}
		} catch (Exception e) {
			// ignore
		}
		return false;
	}

	@SuppressWarnings("unchecked")
	protected <T> T path(Map<String, Object> message, String path) {

		Object tmp = message;
		for (String key : path.split("\\/")) {
			tmp = ((Map<String, Object>) tmp).get(key);
		}

		return (T) tmp;
	}

	protected boolean collection(Map<String, Object> message, String name) {
		return name.equals(message.get("collection"));
	}

	/**
	 * Test if given message can be processed by this message handler.
	 * 
	 * @param message the message to test
	 * @return True if message can be processed, false otherwise
	 */
	protected abstract boolean test(Map<String, Object> message);

	/**
	 * Handle given message.
	 * 
	 * @param message the message to be handled
	 */
	protected abstract void handle(Map<String, Object> message);
}
