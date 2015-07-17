package com.github.sarxos.ryzom.network;

public abstract class Lv20PrefixHandler {

	private final char prefix;

	public Lv20PrefixHandler(char prefix) {
		this.prefix = prefix;
	}

	public char getPrefix() {
		return prefix;
	}

	protected abstract void handle(String message);
}