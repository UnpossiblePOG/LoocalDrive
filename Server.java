import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

public class Server {
    private static final int PORT = 5000;
    private static final String FOLDER_PATH = "Server-folder";

    public static void main(String[] args) {
        File folder = new File(FOLDER_PATH);
        if (!folder.exists()) {
            folder.mkdir();
        }

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("HTTP Server started on port " + PORT);

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
            sendResponse(output, 200, "OK", "application/json", json.toString());
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
            String fileName = headers.get("File-Name");
            if (fileName == null || fileName.trim().isEmpty()) {
                sendResponse(output, 400, "Bad Request", "text/plain", "Missing File-Name header");
                return;
            }

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
    }
}
