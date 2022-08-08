package org.loom.test.httpserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

import static java.nio.charset.StandardCharsets.UTF_8;

// Original code for this example: https://github.com/forax/loom-fiber/blob/master/src/main/java/fr/umlv/loom/example/_13_http_server.java

// A HTTP server that serve static files and answer to 3 services
//   GET /tasks returns a list of tasks as a json object
//   POST /tasks take the content as a JSON text and add it as a new task
//   DELETE /tasks/id delete a task by its id

// $JAVA_HOME/bin/java --enable-preview -cp target/loom-1.0-SNAPSHOT.jar  fr.umlv.loom.example._13_http_server
public class Server {

  private static void getRequest(HttpExchange exchange) throws IOException {
    System.err.println("thread " + Thread.currentThread());
    var uri = exchange.getRequestURI();
    System.out.println("GET query " + uri);

    try (exchange) {
      var path = Path.of(uri.toString());
      var count = Integer.parseInt(path.getFileName().toString());
      //System.out.println("count " + count);
      byte[] data = readBytes(count);
      int checksum = computeChecksum(data);
      exchange.sendResponseHeaders(200, 0);
      try (var writer = new OutputStreamWriter(exchange.getResponseBody(), UTF_8)) {
        writer.write(checksum);
      }
    }
  }

  private static int computeChecksum(byte[] data) {
    int result = 0;
    for (int i = 0; i < data.length; i++) {
      result += data[i];
    }
    return result;
  }

  private static byte[] readBytes(int count) {
    byte[] result = new byte[count];
    int i = 0;
    try (FileInputStream fis = new FileInputStream(filename)) {
      while (i < count) {
        int read = fis.read(result, i, Math.min(32, count - i));
        i += read;
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    return result;
  }

  private static void getStaticContent(HttpExchange exchange) throws IOException {
    System.err.println("thread " + Thread.currentThread());
    var uri = exchange.getRequestURI();
    var path = Path.of(".", uri.toString());
    System.out.println("GET query " + uri + " to " + path);

    try(exchange) {
      exchange.sendResponseHeaders(200, Files.size(path));
      Files.copy(path, exchange.getResponseBody());
    } catch(IOException e) {
      exchange.sendResponseHeaders(404, 0);
    }
  }

  private static String filename;

  public static void main(String[] args) throws IOException {
    filename = args[0];
    System.out.println("File content to servr: " + filename);
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    var localAddress = new InetSocketAddress(8080);
    System.out.println("server at http://localhost:" + localAddress.getPort() + "/todo.html");

    var server = HttpServer.create();
    server.setExecutor(executor);
    server.bind(localAddress, 0);
    server.createContext("/", exchange -> getStaticContent(exchange));
    server.createContext("/tasks/", exchange -> {
      switch (exchange.getRequestMethod()) {
        case "GET" -> getRequest(exchange);
        default -> throw new IllegalStateException("unknown");
      }
    });
    server.start();
  }
}
