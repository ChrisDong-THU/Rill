package app.rill.android;

import android.net.VpnService;
import app.rill.core.RelayCore;
import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded, loopback-only TCP adapter for the native TUN engine. */
final class TunnelBridge implements Closeable {
  static final int MAX_SESSIONS = 16;
  private static final int HEADER_LIMIT = 42 * 1024;
  private final VpnService service;
  private final NetworkUpstream upstream;
  private final RelayCore relay;
  private final Set<InetAddress> destinations;
  private final byte[] token;
  private final ServerSocket listener;
  private final Set<Socket> sockets = new HashSet<>();
  private final ThreadPoolExecutor sessions = workers();
  private final ThreadPoolExecutor uploads = workers();

  TunnelBridge(
      VpnService service,
      NetworkUpstream upstream,
      RelayCore relay,
      Set<InetAddress> destinations,
      String token)
      throws IOException {
    this.service = service;
    this.upstream = upstream;
    this.relay = relay;
    this.destinations = destinations;
    this.token = token.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    listener = new ServerSocket(0, MAX_SESSIONS, InetAddress.getByName("127.0.0.1"));
    new Thread(this::accept, "Rill-tunnel-listener").start();
  }

  int port() {
    return listener.getLocalPort();
  }

  boolean isListening() {
    return !listener.isClosed();
  }

  private static ThreadPoolExecutor workers() {
    return new ThreadPoolExecutor(0, MAX_SESSIONS, 15, TimeUnit.SECONDS, new SynchronousQueue<>());
  }

  private void accept() {
    try {
      while (!listener.isClosed()) {
        Socket client = listener.accept();
        try {
          track(client);
          sessions.execute(() -> forward(client));
        } catch (IOException | RejectedExecutionException error) {
          release(client);
        }
      }
    } catch (IOException error) {
      close();
    }
  }

  private void forward(Socket client) {
    try {
      client.setSoTimeout(12000);
      BufferedInputStream input = new BufferedInputStream(client.getInputStream());
      OutputStream output = client.getOutputStream();
      InetSocketAddress destination = negotiate(input, output);
      if (!destinations.contains(destination.getAddress())) {
        reply(output, 2);
        return;
      }

      // The tunnel may hand us opaque TCP on an IP shared by several origins. Inspect only
      // configured HTTP ports and leave other protocols byte-for-byte intact.
      boolean inspect =
          relay.getTargets().origins.stream().anyMatch(o -> o.port == destination.getPort());
      if (inspect) {
        reply(output, 0);
        input.mark(HEADER_LIMIT);
        int first = input.read();
        if (first < 0) return;
        input.reset();
        if ((first == 'G' || first == 'H') && relay.serveHttp(client, input, destination.getPort()))
          return;
        input.reset();
      }
      forwardDirect(client, input, output, destination, inspect);
    } catch (IOException | RejectedExecutionException ignored) {
      // Closing the stream propagates connection failures to the player's TCP stack.
    } finally {
      release(client);
    }
  }

  private void forwardDirect(
      Socket client,
      InputStream input,
      OutputStream output,
      InetSocketAddress destination,
      boolean connectedReplySent)
      throws IOException {
    Socket remote = new Socket();
    try {
      track(remote);
      try {
        connectDirect(remote, destination);
      } catch (IOException error) {
        if (!connectedReplySent) reply(output, 5);
        throw error;
      }
      if (!connectedReplySent) reply(output, 0);
      client.setSoTimeout(0);
      uploads.execute(
          () -> {
            try {
              copy(input, remote.getOutputStream());
              remote.shutdownOutput();
            } catch (IOException error) {
              closeQuietly(remote);
            }
          });
      copy(remote.getInputStream(), output);
    } finally {
      release(remote);
    }
  }

  private void connectDirect(Socket socket, InetSocketAddress destination) throws IOException {
    if (!service.protect(socket)) throw new IOException("无法保护直连套接字");
    upstream.awaitNetwork().bindSocket(socket);
    socket.connect(destination, 8000);
  }

  private InetSocketAddress negotiate(InputStream input, OutputStream output) throws IOException {
    DataInputStream data = new DataInputStream(input);
    if (data.readUnsignedByte() != 5) throw new IOException("Invalid tunnel version");
    int methods = data.readUnsignedByte();
    boolean authenticate = false;
    for (int i = 0; i < methods; i++) authenticate |= data.readUnsignedByte() == 2;
    output.write(new byte[] {5, (byte) (authenticate ? 2 : 255)});
    if (!authenticate || data.readUnsignedByte() != 1)
      throw new IOException("Invalid tunnel authentication");
    byte[] username = new byte[data.readUnsignedByte()];
    data.readFully(username);
    byte[] password = new byte[data.readUnsignedByte()];
    data.readFully(password);
    boolean authorized =
        Arrays.equals(username, new byte[] {'r', 'i', 'l', 'l'})
            && java.security.MessageDigest.isEqual(token, password);
    output.write(new byte[] {1, (byte) (authorized ? 0 : 1)});
    if (!authorized) throw new IOException("Invalid tunnel credentials");
    if (data.readUnsignedByte() != 5
        || data.readUnsignedByte() != 1
        || data.readUnsignedByte() != 0) {
      reply(output, 7);
      throw new IOException("Unsupported tunnel command");
    }
    int type = data.readUnsignedByte();
    if (type != 1 && type != 4) {
      reply(output, 8);
      throw new IOException("Tunnel requires an IP address");
    }
    byte[] address = new byte[type == 1 ? 4 : 16];
    data.readFully(address);
    return new InetSocketAddress(InetAddress.getByAddress(address), data.readUnsignedShort());
  }

  private static void reply(OutputStream output, int code) throws IOException {
    output.write(new byte[] {5, (byte) code, 0, 1, 0, 0, 0, 0, 0, 0});
  }

  private static void copy(InputStream input, OutputStream output) throws IOException {
    byte[] buffer = new byte[16384];
    int length;
    while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
  }

  private synchronized void track(Socket socket) throws IOException {
    if (listener.isClosed()) throw new IOException("Tunnel stopped");
    sockets.add(socket);
  }

  private synchronized void release(Socket socket) {
    sockets.remove(socket);
    closeQuietly(socket);
  }

  private static void closeQuietly(Closeable value) {
    try {
      value.close();
    } catch (IOException ignored) {
    }
  }

  @Override
  public synchronized void close() {
    closeQuietly(listener);
    for (Socket socket : sockets) closeQuietly(socket);
    sockets.clear();
    sessions.shutdownNow();
    uploads.shutdownNow();
  }
}
