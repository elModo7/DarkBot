package org.darkstorm.minecraft.darkbot.connection;

import java.io.*;
import java.security.Security;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.SecretKey;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.darkstorm.minecraft.darkbot.MinecraftBot;
import org.darkstorm.minecraft.darkbot.event.*;
import org.darkstorm.minecraft.darkbot.event.EventListener;
import org.darkstorm.minecraft.darkbot.event.general.DisconnectEvent;
import org.darkstorm.minecraft.darkbot.event.io.*;
import org.darkstorm.minecraft.darkbot.protocol.*;
import org.darkstorm.minecraft.darkbot.util.*;

public class SocketConnectionHandler implements ConnectionHandler, EventListener {
	private final MinecraftBot bot;
	private final Protocol protocol;
	private final Queue<Packet> packetProcessQueue;
	private final Queue<Packet> packetWriteQueue;
	private final Connection connection;

	private final AtomicBoolean pauseReading, pauseWriting;

	private ReadTask readTask;
	private WriteTask writeTask;

	private SecretKey sharedKey;
	private boolean encrypting, decrypting;

	public SocketConnectionHandler(MinecraftBot bot, Protocol protocol, String server, int port) {
		this(bot, protocol, server, port, null);
	}

	public SocketConnectionHandler(MinecraftBot bot, Protocol protocol, String server, int port, ProxyData socksProxy) {
		this.bot = bot;
		this.protocol = protocol;
		packetProcessQueue = new ArrayDeque<Packet>();
		packetWriteQueue = new ArrayDeque<Packet>();

		pauseReading = new AtomicBoolean();
		pauseWriting = new AtomicBoolean();

		if(socksProxy != null)
			connection = new Connection(server, port, socksProxy);
		else
			connection = new Connection(server, port);
		bot.getEventBus().register(this);
	}

	@EventHandler
	public void onDisconnect(DisconnectEvent event) {
		if(isConnected())
			connection.disconnect();
	}

	@Override
	public void sendPacket(Packet packet) {
		synchronized(packetWriteQueue) {
			packetWriteQueue.offer(packet);
			packetWriteQueue.notifyAll();
		}
	}

	@Override
	public synchronized void connect() throws IOException {
		if(connection.isConnected())
			return;
		connection.connect();

		ExecutorService service = bot.getService();
		readTask = new ReadTask();
		writeTask = new WriteTask();
		readTask.future = service.submit(readTask);
		writeTask.future = service.submit(writeTask);
	}

	@Override
	public synchronized void disconnect(String reason) {
		if(!connection.isConnected() && readTask.future == null && writeTask.future == null)
			return;
		if(readTask != null)
			readTask.future.cancel(true);
		if(writeTask != null)
			writeTask.future.cancel(true);
		readTask = null;
		writeTask = null;
		sharedKey = null;
		encrypting = decrypting = false;
		connection.disconnect();
		bot.getEventBus().fire(new DisconnectEvent(reason));
	}

	@Override
	public synchronized void process() {
		Packet[] packets;
		synchronized(packetProcessQueue) {
			if(packetProcessQueue.size() == 0)
				return;
			packets = packetProcessQueue.toArray(new Packet[packetProcessQueue.size()]);
			packetProcessQueue.clear();
		}
		EventBus eventBus = bot.getEventBus();
		for(Packet packet : packets)
			eventBus.fire(new PacketProcessEvent(packet));
	}

	@Override
	public Protocol getProtocol() {
		return protocol;
	}

	@Override
	public boolean isConnected() {
		return connection.isConnected() && readTask != null && !readTask.future.isDone() && writeTask != null && !writeTask.future.isDone();
	}

	@Override
	public String getServer() {
		return connection.getHost();
	}

	@Override
	public int getPort() {
		return connection.getPort();
	}

	@Override
	public boolean supportsEncryption() {
		return true;
	}

	@Override
	public SecretKey getSharedKey() {
		return sharedKey;
	}

	@Override
	public void setSharedKey(SecretKey sharedKey) {
		if(this.sharedKey != null)
			throw new IllegalStateException("Shared key already set");
		this.sharedKey = sharedKey;
	}

	@Override
	public boolean isEncrypting() {
		return encrypting;
	}

	@Override
	public synchronized void enableEncryption() {
		if(!isConnected())
			throw new IllegalStateException("Not connected");
		if(encrypting)
			throw new IllegalStateException("Already encrypting");
		if(sharedKey == null)
			throw new IllegalStateException("Shared key not set");
		if(!pauseWriting.get() && (writeTask.thread == null || writeTask.thread != Thread.currentThread()))
			throw new IllegalStateException("Must be called from write thread");
		connection.setOutputStream(new DataOutputStream(EncryptionUtil.encryptOutputStream(connection.getOutputStream(), sharedKey)));
		encrypting = true;
	}

	@Override
	public boolean isDecrypting() {
		return decrypting;
	}

	@Override
	public synchronized void enableDecryption() {
		if(!isConnected())
			throw new IllegalStateException("Not connected");
		if(decrypting)
			throw new IllegalStateException("Already decrypting");
		if(sharedKey == null)
			throw new IllegalStateException("Shared key not set");
		if(!pauseReading.get() && (readTask.thread == null || readTask.thread != Thread.currentThread()))
			throw new IllegalStateException("Must be called from read thread");
		connection.setInputStream(new DataInputStream(EncryptionUtil.decryptInputStream(connection.getInputStream(), sharedKey)));
		decrypting = true;
	}

	@Override
	public boolean supportsPausing() {
		return true;
	}

	@Override
	public void pauseReading() {
		synchronized(pauseReading) {
			pauseReading.set(true);
			pauseReading.notifyAll();
		}
	}

	@Override
	public void pauseWriting() {
		synchronized(pauseWriting) {
			pauseWriting.set(true);
			pauseWriting.notifyAll();
		}
		synchronized(packetWriteQueue) {
			packetWriteQueue.notifyAll();
		}
	}

	@Override
	public void resumeReading() {
		synchronized(pauseReading) {
			pauseReading.set(false);
			pauseReading.notifyAll();
		}
	}

	@Override
	public void resumeWriting() {
		synchronized(pauseWriting) {
			pauseWriting.set(false);
			pauseWriting.notifyAll();
		}
		synchronized(packetWriteQueue) {
			packetWriteQueue.notifyAll();
		}
	}

	@Override
	public boolean isReadingPaused() {
		return pauseReading.get();
	}

	@Override
	public boolean isWritingPaused() {
		return pauseWriting.get();
	}

	private final class ReadTask implements Runnable {
		private Future<?> future;
		private Thread thread;

		@Override
		public void run() {
			thread = Thread.currentThread();
			try {
				Thread.sleep(500);
				while(isConnected()) {
					try {
						synchronized(pauseReading) {
							if(pauseReading.get()) {
								pauseReading.wait(500);
								continue;
							}
						}
					} catch(InterruptedException exception) {
						if(future == null || future.isCancelled())
							break;
						continue;
					}

					InputStream in = connection.getInputStream();
					Packet packet = protocol.read(in);

					bot.getEventBus().fire(new PacketReceivedEvent(packet));
					synchronized(packetProcessQueue) {
						packetProcessQueue.offer(packet);
					}
				}
			} catch(Throwable exception) {
				exception.printStackTrace();
				disconnect("Read error: " + exception);
			}
		}
	}

	private final class WriteTask implements Runnable {
		private Future<?> future;
		private Thread thread;

		@Override
		public void run() {
			thread = Thread.currentThread();
			try {
				Thread.sleep(500);
				while(isConnected()) {
					Packet packet = null;
					try {
						synchronized(pauseWriting) {
							if(pauseWriting.get()) {
								pauseWriting.wait(500);
								continue;
							}
						}

						synchronized(packetWriteQueue) {
							if(!packetWriteQueue.isEmpty())
								packet = packetWriteQueue.poll();
							else
								packetWriteQueue.wait(500);
						}
					} catch(InterruptedException exception) {
						if(future == null || future.isCancelled())
							break;
						continue;
					}
					
					if(packet != null) {
						if(!packet.getDirection().equals(Direction.TO_SERVER))
							throw new IOException("Attempted to write a packet in the wrong direction");
						
						OutputStream out = connection.getOutputStream();
						protocol.write(out, packet);
						out.flush();

						bot.getEventBus().fire(new PacketSentEvent(packet));
					}
				}
			} catch(Throwable exception) {
				exception.printStackTrace();
				disconnect("Write error: " + exception);
			}
		}
	}

	static {
		Security.addProvider(new BouncyCastleProvider());
	}
}