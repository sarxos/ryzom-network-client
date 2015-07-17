# LV-20 Ryzom Network Client

This is Java client for LV-20 Ryzom Network, crafted by the Matis engineers after 
studies performed on very ancient amber cubes found at the Megacorp SAT01-B satellite crash site 
near Sandy Workshop of Aeden Aqueous.

This is Karavan technology, not to be used by Kamis.

## Example


```java
public static void main(String[] args) throws InterruptedException {

	// client is closeable, so it can be used in either try/resources section
	// or user can close it manually, depending on the application needs

	try (Lv20Client client = new Lv20Client()) {

		// login to ryzom network, please note that you need to provide toon name
		// here, not the account

		client.login("ToonName", "SecretPassword");

		// send private tell message to the other toon (named ninbox in this example)

		client.tell("Ninbox", "Hi Ninbox, please remember about the meeting.");

		// send message to the given chat

		client.send(Chat.UNIVERSE, "Hello Atys!");

		// sleep a little bit before closing client just to make sure that 
		// message has been sent and the web socket can be closed without 
		// any waiting messages in the buffer

		Thread.sleep(1000);
	}
}
```

## Requirements

* Java 8+
* Ryzom account with at least one toon
* Need to be Karavan Follower, this software is not for Kamis

### License

The MIT License (MIT)

Copyright (c) 2015 Bartosz Firyn

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

