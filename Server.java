import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.security.MessageDigest;

public class Server {
    private static final int PORT = 5000;
    private static final String FOLDER_PATH = "Server-folder";

    private static class WsClient {
        public final OutputStream output;
        public final String ip;
        public WsClient(OutputStream output, String ip) {
            this.output = output;
            this.ip = ip;
        }
    }
    private static final List<WsClient> wsClients = new CopyOnWriteArrayList<>();

    public static void broadcastWebSocketExceptIp(String excludeIp, String jsonMessage) {
        for (WsClient client : wsClients) {
            if (!client.ip.equals(excludeIp)) {
                System.out.println("From : " + excludeIp + " | To : " + client.ip + ": " + jsonMessage);
                sendWsMessage(client.output, jsonMessage);
            }
        }
    }

    public static void sendWsMessage(OutputStream out, String msg) {
        synchronized(out) {
        try {
            byte[] raw = msg.getBytes("UTF-8");
            out.write(129); // text frame FIN
            if (raw.length <= 125) {
                out.write(raw.length);
            } else if (raw.length <= 65535) {
                out.write(126);
                out.write((raw.length >> 8) & 0xFF);
                out.write(raw.length & 0xFF);
            }
            out.write(raw);
            out.flush();
        } catch (IOException e) {
            // broken pipe handled by read loop
        }
        }
    }

    public static void main(String[] args) {
        File folder = new File(FOLDER_PATH);
        if (!folder.exists()) {
            folder.mkdir();
        }

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("HTTP Server started on port " + PORT);
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                
                // Skip inactive and loopback (127.0.0.1) interfaces
                if (iface.isLoopback() || !iface.isUp()) continue;

                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    
                    // Filter for IPv4 addresses that are site-local (like 192.168.x.x)
                    if (addr instanceof java.net.Inet4Address && addr.isSiteLocalAddress()) {
                        System.out.println("IP Domain : http://" + addr.getHostAddress() + ":" + PORT);
                    }
                }
            }

            while (true) {
                Socket clientSocket = serverSocket.accept();
                new Thread(new ClientHandler(clientSocket)).start();
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }

    private static class ClientHandler implements Runnable {
        private final Socket socket;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try (InputStream input = socket.getInputStream();
                 OutputStream output = socket.getOutputStream()) {

                String requestLine = readLine(input);
                if (requestLine == null || requestLine.isEmpty()) return;

                String[] requestParts = requestLine.split(" ");
                if (requestParts.length < 3) return;

                String method = requestParts[0];
                String path = requestParts[1];

                Map<String, String> headers = new HashMap<>();
                String headerLine;
                int contentLength = 0;
                while ((headerLine = readLine(input)) != null && !headerLine.isEmpty()) {
                    int colonIndex = headerLine.indexOf(":");
                    if (colonIndex > 0) {
                        String key = headerLine.substring(0, colonIndex).trim();
                        String value = headerLine.substring(colonIndex + 1).trim();
                        headers.put(key, value);
                        if (key.equalsIgnoreCase("Content-Length")) {
                            contentLength = Integer.parseInt(value);
                        }
                    }
                }

                if (method.equals("OPTIONS")) {
                    sendResponse(output, 204, "No Content", "text/plain", "");
                } else if (headers.containsKey("Upgrade") && "websocket".equalsIgnoreCase(headers.get("Upgrade"))) {
                    handleWebSocket(input, output, headers, socket);
                } else if (method.equals("GET")) {
                    if (path.equals("/") || path.equals("/index.html")) {
                        serveFile(output, "index.html", "text/html");
                    } else if (path.equals("/style.css") || path.equals("/../style.css")) {
                        serveParentFile(output, "style.css", "text/css");
                    } else if (path.equals("/script.js") || path.equals("/../script.js")) {
                        serveParentFile(output, "script.js", "application/javascript");
                    } else if (path.equals("/list")) {
                        listFiles(output);
                    } else if (path.startsWith("/download?file=")) {
                        String fileName = path.substring("/download?file=".length());
                        fileName = URLDecoder.decode(fileName, "UTF-8");
                        serveFileForDownload(output, fileName);
                    } else {
                        sendResponse(output, 404, "Not Found", "text/plain", "Not Found");
                    }
                } else if (method.equals("POST")) {
                    if (path.equals("/upload")) {
                        handleUpload(input, output, headers, contentLength);
                    } else if (path.equals("/delete")) {
                        handleDelete(input, output, contentLength);
                    } else {
                        sendResponse(output, 404, "Not Found", "text/plain", "Not Found");
                    }
                } else {
                    sendResponse(output, 405, "Method Not Allowed", "text/plain", "Method Not Allowed");
                }

            } catch (Exception e) {
                System.err.println("Error handling client: " + e.getMessage());
            } finally {
                try {
                    socket.close();
                } catch (IOException e) {
                    // Ignore close exception
                }
            }
        }

        private String readLine(InputStream is) throws IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = is.read()) != -1) {
                if (c == '\r') {
                    int next = is.read();
                    if (next == '\n') {
                        break;
                    }
                    sb.append((char) c);
                    if (next != -1) sb.append((char) next);
                } else if (c == '\n') {
                    break;
                } else {
                    sb.append((char) c);
                }
            }
            if (sb.length() == 0 && c == -1) return null;
            return sb.toString();
        }

        private void serveFile(OutputStream output, String filename, String contentType) throws IOException {
            File file = new File(FOLDER_PATH, filename);
            if (file.exists() && !file.isDirectory()) {
                byte[] content = Files.readAllBytes(file.toPath());
                sendResponse(output, 200, "OK", contentType, content);
            } else {
                sendResponse(output, 404, "Not Found", "text/plain", "File Not Found: " + filename);
            }
        }

        private void serveParentFile(OutputStream output, String filename, String contentType) throws IOException {
            File file = new File(filename);
            if (file.exists() && !file.isDirectory()) {
                byte[] content = Files.readAllBytes(file.toPath());
                sendResponse(output, 200, "OK", contentType, content);
            } else {
                sendResponse(output, 404, "Not Found", "text/plain", "File Not Found: " + filename);
            }
        }

        private void listFiles(OutputStream output) throws IOException {
            File folder = new File(FOLDER_PATH);
            File[] files = folder.listFiles();
            StringBuilder json = new StringBuilder("[");
            if (files != null) {
                boolean first = true;
                for (File file : files) {
                    if (file.isFile() && !file.getName().equals("index.html")) {
                        if (!first) json.append(",");
                        json.append("\"").append(file.getName()).append("\"");
                        first = false;
                    }
                }
            }
            json.append("]");
            
            String jsonStr = json.toString();
            String headers = "HTTP/1.1 200 OK\r\n" +
                             "Content-Type: application/json\r\n" +
                             "Content-Length: " + jsonStr.getBytes("UTF-8").length + "\r\n" +
                             "Cache-Control: no-store, no-cache, must-revalidate, max-age=0\r\n" +
                             "Access-Control-Allow-Origin: *\r\n" +
                             "Connection: close\r\n\r\n";
            output.write(headers.getBytes("UTF-8"));
            output.write(jsonStr.getBytes("UTF-8"));
            output.flush();
        }

        private void serveFileForDownload(OutputStream output, String filename) throws IOException {
            File file = new File(FOLDER_PATH, filename);
            if (file.exists() && !file.isDirectory()) {
                byte[] content = Files.readAllBytes(file.toPath());
                String contentType = "application/octet-stream";
                String headers = "HTTP/1.1 200 OK\r\n" +
                                 "Content-Type: " + contentType + "\r\n" +
                                 "Content-Length: " + content.length + "\r\n" +
                                 "Content-Disposition: attachment; filename=\"" + filename + "\"\r\n" +
                                 "Access-Control-Allow-Origin: *\r\n" +
                                 "Connection: close\r\n\r\n";
                output.write(headers.getBytes("UTF-8"));
                output.write(content);
                output.flush();
            } else {
                sendResponse(output, 404, "Not Found", "text/plain", "File Not Found: " + filename);
            }
        }

        private void handleUpload(InputStream input, OutputStream output, Map<String, String> headers, int contentLength) throws IOException {
            String originalFileName = headers.get("File-Name");
            if (originalFileName == null || originalFileName.trim().isEmpty()) {
                sendResponse(output, 400, "Bad Request", "text/plain", "Missing File-Name header");
                return;
            }
            
            originalFileName = originalFileName.replaceAll("[\n\r\"']", "").trim();
            String rawUploaderIp = socket.getInetAddress().getHostAddress();
            String ipDash = rawUploaderIp.replace(".", "-");
            String timeDash = new java.text.SimpleDateFormat("yyyy-MM-dd-HH-mm-ss").format(new java.util.Date());
            
            int dotIndex = originalFileName.lastIndexOf('.');
            String baseName = originalFileName;
            String ext = "";
            if (dotIndex > 0) {
                baseName = originalFileName.substring(0, dotIndex);
                ext = originalFileName.substring(dotIndex);
            } else if (dotIndex == 0) {
                baseName = "";
                ext = originalFileName;
            }
            
            String fileName = baseName + "-" + ipDash + "-" + timeDash + ext;

            if (contentLength > 0) {
                byte[] body = new byte[contentLength];
                int bytesRead = 0;
                while (bytesRead < contentLength) {
                    int read = input.read(body, bytesRead, contentLength - bytesRead);
                    if (read == -1) break;
                    bytesRead += read;
                }
                
                // Base64 decode string body
                String base64Body = new String(body, "UTF-8");
                // Base64 data from JS often starts with 'data:application/octet-stream;base64,' or similar.
                // We'll strip that out if it exists.
                if (base64Body.contains(",")) {
                    base64Body = base64Body.substring(base64Body.indexOf(",") + 1);
                }
                
                try {
                    byte[] decodedBytes = Base64.getDecoder().decode(base64Body);
                    File file = new File(FOLDER_PATH, fileName);
                    Files.write(file.toPath(), decodedBytes);
                    
                    String uploaderIp = socket.getInetAddress().getHostAddress();
                    String msg = "{\"type\":\"upload\", \"message\":\"" + uploaderIp + " successfully uploaded " + fileName + "\"}";
                    Server.broadcastWebSocketExceptIp(uploaderIp, msg);

                    sendResponse(output, 200, "OK", "text/plain", "File uploaded successfully");
                } catch (IllegalArgumentException e) {
                    sendResponse(output, 400, "Bad Request", "text/plain", "Invalid Base64 payload");
                }
            } else {
                sendResponse(output, 400, "Bad Request", "text/plain", "No content provided");
            }
        }

        private void handleDelete(InputStream input, OutputStream output, int contentLength) throws IOException {
             if (contentLength > 0) {
                byte[] body = new byte[contentLength];
                int bytesRead = 0;
                while (bytesRead < contentLength) {
                    int read = input.read(body, bytesRead, contentLength - bytesRead);
                    if (read == -1) break;
                    bytesRead += read;
                }
                String fileName = new String(body, "UTF-8").trim();

                // Strip any trailing carriage returns, new lines or quotes that curl or fetch might append.
                fileName = fileName.replaceAll("[\n\r\"']", "").trim();

                File file = new File(FOLDER_PATH, fileName);
                if (file.exists() && file.isFile()) {
                    if (file.delete()) {
                        String deleterIp = socket.getInetAddress().getHostAddress();
                        String msg = "{\"type\":\"delete\", \"message\":\"" + deleterIp + " deleted " + fileName + "\"}";
                        Server.broadcastWebSocketExceptIp(deleterIp, msg);
                        sendResponse(output, 200, "OK", "text/plain", "File deleted successfully");
                    } else {
                        sendResponse(output, 500, "Internal Server Error", "text/plain", "Failed to delete file");
                    }
                } else {
                    sendResponse(output, 404, "Not Found", "text/plain", "File not found: " + fileName);
                }
            } else {
                sendResponse(output, 400, "Bad Request", "text/plain", "No filename provided");
            }
        }

        private void sendResponse(OutputStream output, int statusCode, String statusText, String contentType, String body) throws IOException {
            sendResponse(output, statusCode, statusText, contentType, body.getBytes("UTF-8"));
        }

        private void sendResponse(OutputStream output, int statusCode, String statusText, String contentType, byte[] body) throws IOException {
            String headers = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n" +
                             "Content-Type: " + contentType + "\r\n" +
                             "Content-Length: " + body.length + "\r\n" +
                             "Access-Control-Allow-Origin: *\r\n" +
                             "Access-Control-Allow-Headers: File-Name, Content-Type\r\n" +
                             "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                             "Connection: close\r\n\r\n";
            output.write(headers.getBytes("UTF-8"));
            output.write(body);
            output.flush();
        }

        private void handleWebSocket(InputStream input, OutputStream output, Map<String, String> headers, Socket socket) throws IOException {
            try {
                socket.setTcpNoDelay(true); // Disable Nagle's algorithm for immediate transmission
                String wsKey = headers.get("Sec-WebSocket-Key");
                if (wsKey == null) return;
                String magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
                MessageDigest md = MessageDigest.getInstance("SHA-1");
                byte[] digest = md.digest((wsKey + magic).getBytes("UTF-8"));
                String acceptKey = Base64.getEncoder().encodeToString(digest);
                
                String response = "HTTP/1.1 101 Switching Protocols\r\n" +
                                  "Upgrade: websocket\r\n" +
                                  "Connection: Upgrade\r\n" +
                                  "Sec-WebSocket-Accept: " + acceptKey + "\r\n\r\n";
                output.write(response.getBytes("UTF-8"));
                output.flush();
                
                String ip = socket.getInetAddress().getHostAddress();
                
                WsClient client = new WsClient(output, ip);
                Server.wsClients.add(client);
                
                // Fetch the hostname in the background as it performs a reverse DNS lookup which can block for seconds
                new Thread(() -> {
                    String hostName = socket.getInetAddress().getHostName();
                    String name = hostName.equals(ip) ? "" : " (" + hostName + ")";
                    String connectMsg = "{\"type\":\"connect\", \"message\":\"" + ip + name + " connected.\"}";
                    Server.broadcastWebSocketExceptIp(ip, connectMsg);
                }).start();
                
                while (socket.isConnected() && !socket.isClosed()) {
                    int b = input.read();
                    if (b == -1) break;
                }
            } catch (Exception e) {
                if (!(e instanceof SocketException)) {
                    System.err.println("WS error: " + e.getMessage());
                }
            } finally {
                for (WsClient c : Server.wsClients) {
                    if (c.output == output) {
                        Server.wsClients.remove(c);
                        break;
                    }
                }
            }
        }
    }
}
